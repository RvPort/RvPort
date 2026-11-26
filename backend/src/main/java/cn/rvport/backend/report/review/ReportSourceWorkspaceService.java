package cn.rvport.backend.report.review;

import cn.rvport.backend.artifact.StoredArtifactService;
import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.common.web.PageResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ReportSourceWorkspaceService {
    private final ReviewWorkspaceService access;
    private final ReviewWorkspaceRepository reviews;
    private final StoredArtifactService artifacts;
    public ReportSourceWorkspaceService(ReviewWorkspaceService access,ReviewWorkspaceRepository reviews,StoredArtifactService artifacts) {
        this.access=access; this.reviews=reviews; this.artifacts=artifacts;
    }
    public PageResponse<Map<String,Object>> files(UUID report,UUID actor,boolean admin,String parentKey,String keyword,int page,int size) {
        access.authorized(report,actor,admin); pagination(page,size);
        var manifest=reviews.manifest(report).source();
        Map<String,Map<String,Object>> nodes=new TreeMap<>();
        List<Map<String,Object>> items=reviews.items(report);
        for(var file:manifest.files()) {
            String[] parts=file.path().split("/"); String parent="";
            for(int i=0;i<parts.length;i++) {
                String path=parent.isEmpty()?parts[i]:parent+"/"+parts[i]; boolean directory=i<parts.length-1;
                String key=directory?directoryKey(path):file.fileKey();
                if(!nodes.containsKey(path)) {
                    Map<String,Object> node=new LinkedHashMap<>(); node.put("fileKey",key); node.put("path",path); node.put("name",parts[i]);
                    node.put("parentKey",parent.isEmpty()?null:directoryKey(parent)); node.put("kind",directory?"DIRECTORY":"FILE");
                    node.put("contextAvailable",!directory && file.contextAvailable());
                    node.put("reasonCode",directory?null:file.reasonCode());
                    List<Map<String,Object>> related=items.stream().filter(item->directory
                            ? item.get("fileKey")!=null && Objects.toString(item.get("relativePath"),"").startsWith(path+"/")
                            : file.fileKey().equals(item.get("fileKey"))).toList();
                    node.put("findingCount",related.size());
                    node.put("pendingReviewCount",count(related,ReviewState.PENDING_REVIEW));
                    node.put("toFixCount",count(related,ReviewState.TO_FIX));
                    node.put("excludedCount",count(related,ReviewState.EXCLUDED));
                    nodes.put(path,node);
                }
                parent=path;
            }
        }
        if(parentKey!=null && nodes.values().stream().noneMatch(n->parentKey.equals(n.get("fileKey")) && "DIRECTORY".equals(n.get("kind"))))
            throw missing();
        String search=keyword==null?"":keyword.trim().toLowerCase(Locale.ROOT);
        var selected=nodes.values().stream().filter(node->search.isEmpty()?Objects.equals(parentKey,node.get("parentKey")):
                node.get("path").toString().toLowerCase(Locale.ROOT).contains(search)).toList();
        return PageResponse.of(selected.stream().skip((long)(page-1)*size).limit(size).toList(),page,size,selected.size());
    }
    public Map<String,Object> context(UUID report,String fileKey,UUID actor,boolean admin,Integer start,Integer end) {
        access.authorized(report,actor,admin);
        var manifest=reviews.manifest(report).source();
        var file=manifest.files().stream().filter(f->f.fileKey().equals(fileKey)).findFirst().orElseThrow(ReportSourceWorkspaceService::missing);
        int from=start==null?1:start;
        int to=end==null?(int)Math.min((long)from+499,file.totalLines()==null?0:file.totalLines()):end;
        if(from<1 || (end!=null && to<from) || (long)to-from+1>500) throw invalid();
        Map<String,Object> response=new LinkedHashMap<>(); response.put("fileKey",fileKey); response.put("path",file.path());
        response.put("sourceRevisionId",manifest.sourceRevisionId()); response.put("contentHash",file.contentHash()); response.put("totalLines",file.totalLines());
        if(!file.contextAvailable() || file.artifactId()==null) return unavailable(response,file.reasonCode());
        try(var input=artifacts.download(file.artifactId()).resource().getInputStream()) {
            byte[] bytes=input.readAllBytes();
            if(!SourceSelection.hash(bytes).equals(file.contentHash())) return unavailable(response,"SOURCE_CONTENT_CHANGED");
            String content=new String(bytes,StandardCharsets.UTF_8);
            String[] lines=content.isEmpty()?new String[0]:content.split("\\r\\n|\\r|\\n",-1);
            if((lines.length==0 && from!=1) || (lines.length>0 && from>lines.length) || (end!=null && to>lines.length)) throw invalid();
            List<Map<String,Object>> selected=new ArrayList<>();
            for(int line=from;line<=to;line++) selected.add(Map.of("lineNumber",line,"text",lines[line-1]));
            response.put("contextAvailable",true); response.put("startLine",from); response.put("endLine",to); response.put("lines",selected);
            return response;
        } catch(IOException ex) { return unavailable(response,"SOURCE_CONTEXT_UNAVAILABLE"); }
        catch(ApiException ex) {
            if("ARTIFACT_NOT_FOUND".equals(ex.code()) || "ARTIFACT_OBJECT_NOT_FOUND".equals(ex.code()))
                return unavailable(response,"SOURCE_CONTEXT_UNAVAILABLE");
            throw ex;
        }
    }
    private Map<String,Object> unavailable(Map<String,Object> response,String reason) {
        response.put("contextAvailable",false); response.put("reasonCode",reason==null?"SOURCE_CONTEXT_UNAVAILABLE":reason);
        response.put("message","该版本的源码内容不可用"); response.put("messageEn","Source content for this version is unavailable");
        response.put("lines",List.of()); return response;
    }
    public Map<String,Object> search(UUID report,String fileKey,UUID actor,boolean admin,String keyword,boolean sensitive,int limit) {
        access.authorized(report,actor,admin);
        ReportSourceSearch.validate(keyword,limit);
        var manifest=reviews.manifest(report).source();
        var file=manifest.files().stream().filter(f->f.fileKey().equals(fileKey)).findFirst().orElseThrow(ReportSourceWorkspaceService::missing);
        Map<String,Object> response=new LinkedHashMap<>();
        response.put("fileKey",fileKey); response.put("path",file.path());
        response.put("sourceRevisionId",manifest.sourceRevisionId()); response.put("contentHash",file.contentHash());
        if(!file.contextAvailable() || file.artifactId()==null) return searchUnavailable(response,file.reasonCode());
        try(var input=artifacts.download(file.artifactId()).resource().getInputStream()) {
            byte[] bytes=input.readNBytes(10*1024*1024+1);
            if(bytes.length>10*1024*1024) return searchUnavailable(response,"FILE_TOO_LARGE");
            if(!SourceSelection.hash(bytes).equals(file.contentHash())) return searchUnavailable(response,"SOURCE_CONTENT_CHANGED");
            String content;
            try {
                content=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            } catch(java.nio.charset.CharacterCodingException ex) { return searchUnavailable(response,"INVALID_UTF8"); }
            if(content.indexOf('\0')>=0) return searchUnavailable(response,"BINARY_FILE");
            response.put("available",true); response.put("reasonCode",null); response.put("message",null); response.put("messageEn",null);
            response.putAll(ReportSourceSearch.find(content,keyword,sensitive,limit)); return response;
        } catch(IOException ex) { return searchUnavailable(response,"SOURCE_CONTEXT_UNAVAILABLE"); }
        catch(ApiException ex) {
            if("ARTIFACT_NOT_FOUND".equals(ex.code()) || "ARTIFACT_OBJECT_NOT_FOUND".equals(ex.code()))
                return searchUnavailable(response,"SOURCE_CONTEXT_UNAVAILABLE");
            throw ex;
        }
    }
    private Map<String,Object> searchUnavailable(Map<String,Object> response,String reason) {
        response.put("available",false); response.put("reasonCode",reason==null?"SOURCE_CONTEXT_UNAVAILABLE":reason);
        response.put("message","该版本的源码内容不可用"); response.put("messageEn","Source content for this version is unavailable");
        response.put("matches",List.of()); response.put("returnedCount",0); response.put("truncated",false); return response;
    }
    static String directoryKey(String path) { return "dir-"+SourceSelection.hash(path.getBytes(StandardCharsets.UTF_8)); }
    private long count(List<Map<String,Object>> items,ReviewState state) { return items.stream().filter(item->state.name().equals(item.get("reviewState"))).count(); }
    private void pagination(int page,int size) { if(page<1 || size<1 || size>200) throw invalid(); }
    private static ApiException invalid() { return new ApiException(HttpStatus.BAD_REQUEST,"REPORT_REQUEST_INVALID","源码查询范围不正确"); }
    private static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND,"REPORT_SOURCE_FILE_NOT_FOUND","报告源码文件不存在"); }
}
