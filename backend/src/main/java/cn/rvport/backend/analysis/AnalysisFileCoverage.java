package cn.rvport.backend.analysis;

import java.util.*;

/** 文件清单只证明范围，不能由 PLANNED 推断工具实际完成。头文件可属于多个语言范围。 */
public final class AnalysisFileCoverage {
    private AnalysisFileCoverage() { }
    public static List<Map<String,Object>> byLanguage(List<? extends Map<?,?>> files) {
        Map<String,Map<String,Integer>> groups=new TreeMap<>();
        for (var file:files) {
            List<?> languages=file.get("languages") instanceof List<?> values ? values
                    : Collections.singletonList(file.get("language"));
            if (languages.isEmpty()) languages=List.of("unknown");
            for (String language:languages.stream().map(value->Objects.toString(value,"unknown")).distinct().toList()) {
                String status=Objects.toString(file.get("status"),"UNKNOWN");
                groups.computeIfAbsent(language,key->new TreeMap<>()).merge(status,1,Integer::sum);
            }
        }
        return groups.entrySet().stream().map(entry->Map.<String,Object>of("language",entry.getKey(),
                "knownFileCount",entry.getValue().values().stream().mapToInt(Integer::intValue).sum(),
                "manifestFileCounts",Map.copyOf(entry.getValue()),"executionKnown",false)).toList();
    }
}
