package cn.rvport.backend.report.review;

import cn.rvport.backend.common.error.ApiException;
import java.util.*;
import org.springframework.http.HttpStatus;

/** 固定文件的有界字面搜索；所有位置始终指向原始 UTF-16 文本。 */
final class ReportSourceSearch {
    private ReportSourceSearch() { }
    static void validate(String keyword,int limit) {
        if(keyword==null || keyword.isBlank() || keyword.length()>256 || keyword.indexOf('\n')>=0
                || keyword.indexOf('\r')>=0 || limit<1 || limit>200 || !validUnicode(keyword))
            throw new ApiException(HttpStatus.BAD_REQUEST,"REPORT_REQUEST_INVALID","搜索参数不正确",
                    Map.of("messageEn","Invalid source search parameters"));
    }
    static Map<String,Object> find(String content,String keyword,boolean sensitive,int limit) {
        validate(keyword,limit);
        List<Map<String,Object>> matches=new ArrayList<>();
        String[] lines=content.split("\\r\\n|\\r|\\n",-1);
        for(int line=0;line<lines.length;line++) {
            String text=lines[line];
            for(int offset=0;offset<=text.length()-keyword.length();) {
                if(text.regionMatches(!sensitive,offset,keyword,0,keyword.length())
                        && boundary(text,offset) && boundary(text,offset+keyword.length())) {
                    if(matches.size()==limit) return result(matches,true);
                    matches.add(match(text,line+1,offset,offset+keyword.length()));
                    offset+=keyword.length();
                } else offset++;
            }
        }
        return result(matches,false);
    }
    private static Map<String,Object> match(String line,int number,int start,int end) {
        int from=Math.max(0,start-128);
        if(!boundary(line,from)) from++;
        int to=Math.min(line.length(),from+512);
        if(!boundary(line,to)) to--;
        return Map.of("lineNumber",number,"startColumn",start+1,"endColumn",end+1,
                "preview",line.substring(from,to),"previewStartColumn",from+1,
                "previewTruncated",from>0 || to<line.length());
    }
    private static Map<String,Object> result(List<Map<String,Object>> matches,boolean truncated) {
        return Map.of("matches",matches,"returnedCount",matches.size(),"truncated",truncated);
    }
    private static boolean boundary(String value,int index) {
        return index==0 || index==value.length() || !Character.isHighSurrogate(value.charAt(index-1))
                || !Character.isLowSurrogate(value.charAt(index));
    }
    private static boolean validUnicode(String value) {
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if(Character.isHighSurrogate(c)) {
                if(++i==value.length() || !Character.isLowSurrogate(value.charAt(i))) return false;
            } else if(Character.isLowSurrogate(c)) return false;
        }
        return true;
    }
}
