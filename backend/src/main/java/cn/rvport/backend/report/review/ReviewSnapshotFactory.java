package cn.rvport.backend.report.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ReviewSnapshotFactory {
    private static final Set<String> VOLATILE=Set.of("id","projectId","sourceRevisionId","taskId","scanRunId","runId",
            "sourceRunId","sourceRefId","dependencyAnalysisRunId","createdAt","updatedAt");
    private final ObjectMapper mapper;
    public ReviewSnapshotFactory(ObjectMapper mapper) { this.mapper=mapper; }

    public List<ReviewItemSnapshot> create(ReportInputSelectionPolicy.Selection selection,ReportSourceSnapshotService.Manifest source,
            UUID actor,ReviewWorkspaceRepository.Draft base,List<Map<String,Object>> previous) {
        boolean same=base!=null && Objects.equals(base.sourceRevisionId(),selection.sourceRevisionId());
        var files=new HashMap<String,ReportSourceSnapshotService.File>(); source.files().forEach(f->files.put(f.path(),f));
        List<ReviewItemSnapshot> result=new ArrayList<>();
        var unique=new LinkedHashMap<String,Map<String,Object>>();
        for(var raw:selection.candidates()) unique.putIfAbsent(text(raw.get("sourceType"))+":"+uuid(raw.get("sourceRefId")),raw);
        Map<String,Long> occurrences=unique.values().stream().collect(java.util.stream.Collectors.groupingBy(this::identity,java.util.stream.Collectors.counting()));
        for(var raw:unique.values()) {
            String type=text(raw.get("sourceType")); UUID ref=uuid(raw.get("sourceRefId"));
            String path=safePath(text(raw.get("filePath")));
            var file=files.get(path);
            Integer line=number(raw.get(type.equals("KB_FINDING")?"lineStart":"startLine"));
            Integer end=number(raw.get(type.equals("KB_FINDING")?"lineEnd":"endLine"));
            Integer column=number(raw.get("startColumn")),endColumn=number(raw.get("endColumn"));
            String title=text(raw.get(type.equals("DEPENDENCY_RESULT")?"dependencyName":"title"));
            if(title.isBlank()) title="扫描结果待复核";
            String titleEn=text(raw.get("titleEn")); if(titleEn.isBlank()) titleEn="Scan result requires review";
            Map<String,Object> original=new LinkedHashMap<>(raw);
            original.put("riskLevel",raw.get(type.equals("DEPENDENCY_RESULT")?"riskLevel":"severity"));
            String identity=identity(raw);
            Map<String,Object> evidence=new TreeMap<>(original); VOLATILE.forEach(evidence::remove);
            evidence.put("boundContentHash",file==null?null:file.contentHash());
            String digest=hash(evidence);
            original.put("locations", locations(raw,files,file,line,end));
            original.put("groupKey", file!=null && line!=null ? hash(List.of(file.path(),line))
                    : type.equals("DEPENDENCY_RESULT") ? identity : ref.toString());
            List<Map<String,Object>> matches=same?previous.stream().filter(p->"SCANNER".equals(p.get("origin"))
                    && identity.equals(p.get("identityHash"))).toList():List.of();
            boolean ambiguous=occurrences.get(identity)>1 || matches.size()>1;
            boolean anchored=!text(raw.get("fingerprint")).isBlank() || !text(raw.get("externalFindingId")).isBlank()
                    || (type.equals("DEPENDENCY_RESULT")?!text(raw.get("dependencyName")).isBlank():file!=null && line!=null);
            Map<String,Object> old=!ambiguous && anchored && matches.size()==1 && digest.equals(matches.getFirst().get("evidenceHash"))
                    ?matches.getFirst():null;
            boolean automatic = old != null && (!Boolean.TRUE.equals(old.get("hasManualDecision"))
                    || ReviewState.PENDING_REVIEW.name().equals(old.get("reviewState")));
            if (automatic) old = null;
            ReviewDecision decision=old==null?new ReviewDecision(ReviewState.PENDING_REVIEW,null,null):decision(old);
            result.add(new ReviewItemSnapshot(UUID.randomUUID(),"SCANNER",type,uuid(raw.get("sourceRunId")),ref,identity,digest,
                    Collections.unmodifiableMap(original),title,titleEn,file==null?null:file.fileKey(),path,line,column,end,endColumn,
                    file==null?null:file.contentHash(),null,null,decision,old==null?null:(String)old.get("exclusionReasonEn"),
                    automatic ? Map.of("inherited",false,"reasonCode","NO_MANUAL_DECISION")
                            : same && ambiguous?notInherited("MATCH_AMBIGUOUS"):
                            same && !anchored?notInherited("LOCATION_UNVERIFIABLE"):
                            same && old==null && !matches.isEmpty()?notInherited("EVIDENCE_CHANGED"):inheritance(old,base,same),actor));
        }
        if(same) for(var old:previous) {
            if(!"MANUAL".equals(old.get("origin"))) continue;
            var file=files.get(text(old.get("relativePath")));
            if(file==null || file.contentHash()==null || !Objects.equals(file.contentHash(),old.get("sourceContentHash"))) continue;
            var details=object(old.get("manualDetails"));
            if(details==null || !(details.get("snippet") instanceof String snippet)
                    || !SourceSelection.hash(snippet.getBytes(StandardCharsets.UTF_8)).equals(old.get("snippetHash"))) continue;
            result.add(new ReviewItemSnapshot(UUID.randomUUID(),"MANUAL",null,null,null,text(old.get("identityHash")),text(old.get("evidenceHash")),
                    object(old.get("originalSnapshot")),text(old.get("title")),text(old.get("titleEn")),file.fileKey(),file.path(),
                    number(old.get("startLine")),number(old.get("startColumn")),number(old.get("endLine")),number(old.get("endColumn")),
                    file.contentHash(),text(old.get("snippetHash")),object(old.get("manualDetails")),decision(old),
                    old.get("exclusionReasonEn")==null?null:text(old.get("exclusionReasonEn")),inheritance(old,base,true),uuid(old.get("createdBy"))));
        }
        return List.copyOf(result);
    }
    private List<Map<String,Object>> locations(Map<String,Object> raw,Map<String,ReportSourceSnapshotService.File> files,
            ReportSourceSnapshotService.File primary,Integer start,Integer end) {
        Set<Map<String,Object>> result=new LinkedHashSet<>();
        addLocation(result,primary,start,end);
        Object occurrences=field(field(field(raw,"evidence"),"finding"),"occurrences");
        if(occurrences instanceof List<?> entries) for(Object entry:entries) {
            Object location=field(entry,"location"), span=field(location,"span");
            String path=safePath(text(field(location,"file")));
            addLocation(result,files.get(path),number(field(field(span,"start"),"line")),number(field(field(span,"end"),"line")));
        }
        return List.copyOf(result);
    }
    private static Object field(Object value,String key) { return value instanceof Map<?,?> map ? map.get(key) : null; }
    private static void addLocation(Set<Map<String,Object>> result,ReportSourceSnapshotService.File file,Integer start,Integer end) {
        if(file==null || start==null || start<1) return;
        int last=end==null ? start : end;
        if(last<start || file.totalLines()!=null && last>file.totalLines()) return;
        result.add(Map.of("fileKey",file.fileKey(),"path",file.path(),"startLine",start,"endLine",last));
    }
    private String identity(Map<String,Object> raw) {
        String type=text(raw.get("sourceType")), path=safePath(text(raw.get("filePath")));
        Integer line=number(raw.get(type.equals("KB_FINDING")?"lineStart":"startLine")), column=number(raw.get("startColumn"));
        String fingerprint=text(raw.get("fingerprint"));
            if(fingerprint.isBlank()) fingerprint=text(raw.get("externalFindingId"));
            String identity;
            if(!fingerprint.isBlank()) identity=hash(Arrays.asList(type,"FINGERPRINT",fingerprint));
            else if(type.equals("DEPENDENCY_RESULT")) identity=hash(Arrays.asList(type,raw.get("dependencyType"),raw.get("dependencyName")));
            else identity=hash(Arrays.asList(type,raw.get("ruleId"),raw.get("findingCode"),path,line,column,raw.get("symbol")));
        return identity;
    }
    private Map<String,Object> inheritance(Map<String,Object> old,ReviewWorkspaceRepository.Draft base,boolean same) {
        if(old==null) return notInherited(base==null || same?"NEW_ITEM":"SOURCE_VERSION_CHANGED");
        return Map.of("inherited",true,"manualDecision",true,"reasonCode","SAME_SOURCE_EVIDENCE","fromReportId",base.reportId(),"fromItemId",old.get("id"));
    }
    private Map<String,Object> notInherited(String reason) { return Map.of("inherited",false,"reasonCode",reason); }
    private ReviewDecision decision(Map<String,Object> old) {
        return new ReviewDecision(ReviewState.valueOf(text(old.get("reviewState"))),old.get("restoreState")==null?null:
                ReviewState.valueOf(text(old.get("restoreState"))),(String)old.get("exclusionReason"));
    }
    private String hash(Object value) {
        try { return SourceSelection.hash(mapper.writeValueAsBytes(canonical(value))); }
        catch(java.io.IOException ex) { throw new IllegalArgumentException("REPORT_INPUT_SNAPSHOT_INVALID",ex); }
    }
    private Object canonical(Object value) {
        if(value instanceof Map<?,?> map) {
            Map<String,Object> sorted=new TreeMap<>(); map.forEach((k,v)->sorted.put(String.valueOf(k),canonical(v))); return sorted;
        }
        if(value instanceof List<?> list) return list.stream().map(this::canonical).toList();
        if(value instanceof java.time.temporal.TemporalAccessor || value instanceof UUID) return value.toString();
        return value;
    }
    private String safePath(String value) {
        String path=value.replace('\\','/');
        if(path.isBlank() || path.startsWith("/") || path.matches("^[A-Za-z]:.*") || path.indexOf('\0')>=0) return null;
        for(String part:path.split("/",-1)) if(part.equals("..") || part.isEmpty()) return null;
        while(path.startsWith("./")) path=path.substring(2);
        return path;
    }
    @SuppressWarnings("unchecked") private Map<String,Object> object(Object value) { return (Map<String,Object>)value; }
    private static Integer number(Object value) { return value instanceof Number n?n.intValue():null; }
    private static UUID uuid(Object value) { return UUID.fromString(value.toString()); }
    private static String text(Object value) { return value==null?"":value.toString(); }
}
