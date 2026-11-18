package cn.rvport.backend.report.review;

import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.common.web.PageResponse;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;

public record ReviewItemQuery(Integer page,Integer pageSize,Long draftVersion,String reviewState,String origin,
        String sourceType,String findingKind,String riskLevel,String fileKey,String keyword,String sortBy,String sortOrder) {
    public PageResponse<Map<String,Object>> apply(List<Map<String,Object>> values) {
        return filteredPage(values).page();
    }

    public record Result(PageResponse<Map<String,Object>> page, Map<String,Long> summary) { }

    public Result filteredPage(List<Map<String,Object>> values) {
        int number=page==null?1:page, size=pageSize==null?50:pageSize;
        String order=sortOrder==null?"asc":sortOrder;
        String field=sortBy==null?"createdAt":sortBy;
        if(number<1 || size<1 || size>200 || !List.of("asc","desc").contains(order)
                || !List.of("createdAt","path","line","riskLevel","reviewState","title").contains(field)) throw invalid();
        Comparator<Map<String,Object>> comparator=comparator(field);
        if(order.equals("desc")) comparator=comparator.reversed();
        var filtered=values.stream().filter(this::matches).sorted(comparator.thenComparing(v->String.valueOf(v.get("id")))).toList();
        long offset=(long)(number-1)*size;
        var items=filtered.stream().skip(offset).limit(size).toList();
        var counts = filtered.stream().collect(java.util.stream.Collectors.groupingBy(
                item -> text(item.get("reviewState")), java.util.stream.Collectors.counting()));
        var summary = Map.of("total", (long) filtered.size(),
                "pendingReviewCount", counts.getOrDefault("PENDING_REVIEW", 0L),
                "toFixCount", counts.getOrDefault("TO_FIX", 0L),
                "excludedCount", counts.getOrDefault("EXCLUDED", 0L),
                "manualCount", filtered.stream().filter(item -> "MANUAL".equals(item.get("origin"))).count());
        return new Result(PageResponse.of(items,number,size,filtered.size()), summary);
    }
    private boolean matches(Map<String,Object> item) {
        for(var entry:Map.of("reviewState",text(reviewState),"origin",text(origin),"sourceType",text(sourceType),
                "findingKind",text(findingKind),"riskLevel",text(riskLevel),"fileKey",text(fileKey)).entrySet()) {
            if(!entry.getValue().isEmpty() && !entry.getValue().equals(text(value(item,entry.getKey())))) return false;
        }
        if(keyword==null || keyword.isBlank()) return true;
        String search=keyword.toLowerCase(Locale.ROOT);
        return List.of("title","titleEn","relativePath").stream().anyMatch(k->text(item.get(k)).toLowerCase(Locale.ROOT).contains(search));
    }
    private Comparator<Map<String,Object>> comparator(String field) {
        if(field.equals("line")) return Comparator.comparingLong(v->v.get("startLine") instanceof Number n?n.longValue():Long.MAX_VALUE);
        if(field.equals("riskLevel")) return Comparator.comparingInt(v->{
            String risk=text(value(v,"riskLevel"));
            return switch(risk) { case "CRITICAL"->0; case "HIGH"->1; case "MEDIUM"->2; case "LOW"->3; case "INFO"->4; case "COMMENT"->5; default->6; };
        });
        String key=field.equals("path")?"relativePath":field;
        return Comparator.comparing(v->text(v.get(key)));
    }
    private Object value(Map<String,Object> item,String key) {
        if(item.containsKey(key)) return item.get(key);
        if(item.get("originalSnapshot") instanceof Map<?,?> original) return original.get(key);
        return null;
    }
    private static String text(Object value) { return value==null?"":value.toString(); }
    private static ApiException invalid() { return new ApiException(HttpStatus.BAD_REQUEST,"REPORT_REQUEST_INVALID","分页或排序参数不正确"); }
}
