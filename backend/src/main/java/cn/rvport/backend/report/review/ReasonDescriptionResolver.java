package cn.rvport.backend.report.review;

import java.util.*;

/** 来源原因码的展示投影；不修改扫描事实或参与 evidenceHash。 */
public final class ReasonDescriptionResolver {
    public static final String VERSION="report-reasons.v1";
    private static final Map<String,List<String>> COMMON=Map.ofEntries(
            Map.entry("NEW_ITEM",List.of("新增条目，没有可继承的人工决定","New item; there is no prior manual decision to inherit")),
            Map.entry("SOURCE_VERSION_CHANGED",List.of("源码版本变化，需要重新核对","The source version changed; review is required again")),
            Map.entry("ANALYSIS_NOT_READY",List.of("该分析尚无可纳入的有效结果","This analysis has no valid result available for inclusion")),
            Map.entry("SOURCE_NOT_IN_FIXED_SNAPSHOT",List.of("源文件不在本次固定源码快照中","The source file is not in the fixed source snapshot")),
            Map.entry("NOT_IN_COMPILE_DATABASE",List.of("该文件未列入所用编译数据库","The file is not listed in the selected compilation database")),
            Map.entry("COMPILE_DATABASE_UNAVAILABLE",List.of("编译数据库不可用，未取得相应构建证据","Compilation database unavailable; the corresponding build evidence is missing")),
            Map.entry("BITCODE_UNAVAILABLE",List.of("bitcode 不可用，缺少相应中间表示证据","Bitcode unavailable; the corresponding intermediate representation evidence is missing")),
            Map.entry("OPTIONAL_ARTIFACT_UNAVAILABLE",List.of("绑定的可选构建产物不可用","The bound optional build artifact is unavailable")),
            Map.entry("SOURCE_CONTEXT_UNAVAILABLE",List.of("该版本的源码内容不可用","Source content for this version is unavailable")),
            Map.entry("SOURCE_CONTENT_CHANGED",List.of("源码制品内容与固定摘要不一致","Source artifact content does not match the fixed digest")),
            Map.entry("FILE_TOO_LARGE",List.of("文件超出源码展示大小上限","The file exceeds the source display size limit")),
            Map.entry("BINARY_FILE",List.of("二进制文件不能作为文本源码读取","A binary file cannot be read as source text")),
            Map.entry("INVALID_UTF8",List.of("文件不是有效 UTF-8 文本","The file is not valid UTF-8 text")),
            Map.entry("EVIDENCE_CHANGED",List.of("关键证据已变化，未继承原人工决定","Key evidence changed; the prior manual decision was not inherited")),
            Map.entry("MATCH_AMBIGUOUS",List.of("条目匹配存在歧义，未继承原人工决定","Item matching is ambiguous; the prior manual decision was not inherited")),
            Map.entry("LOCATION_UNVERIFIABLE",List.of("无法验证条目的源码位置","The item's source location could not be verified")),
            Map.entry("NO_MANUAL_DECISION",List.of("没有可继承的人工决定","There is no manual decision to inherit")),
            Map.entry("SAME_SOURCE_EVIDENCE",List.of("同版本源码及关键证据一致，继承人工决定","The source version and key evidence match; the manual decision was inherited")));
    private static final List<String> CHILDREN=List.of("evidence","evidenceChain","conclusion","classification","detail","details","summary",
            "analysisReasonCodes","analysisEvidence","missingEvidence","limitations","inheritance","rawFinding","sourceDetail","breaks");
    private ReasonDescriptionResolver() { }
    public static Map<String,Object> describe(String source,String code) {
        List<String> text=COMMON.get(code);
        if("KB_FINDING".equals(source) && "input-or-relation-unknown".equals(code))
            text=List.of("输入信息或关联关系尚无法确定","Input information or relationships could not be determined");
        if(text==null) text=List.of("未提供该原因码的解释","No description is available for this reason code");
        return Map.of("code",code,"message",text.get(0),"messageEn",text.get(1),"catalogVersion",VERSION);
    }
    public static List<Map<String,Object>> evidence(String source,Object value) {
        var codes=new LinkedHashSet<String>(); collect(value,codes,0,false);
        return codes.stream().map(code->describe(source,code)).toList();
    }
    private static void collect(Object value,Set<String> codes,int depth,boolean codeValue) {
        if(depth>12) return;
        if(value instanceof String text) {
            if(codeValue && text.matches("[A-Za-z0-9_-]{1,128}")) codes.add(text);
        } else if(value instanceof List<?> list) {
            list.forEach(v->collect(v,codes,depth+1,codeValue));
        } else if(value instanceof Map<?,?> map) {
            for(String key:List.of("reasonCode","reason","code")) collect(map.get(key),codes,depth+1,true);
            for(String key:CHILDREN) collect(map.get(key),codes,depth+1,key.equals("analysisReasonCodes") || key.equals("missingEvidence"));
        }
    }
    public static Map<String,Object> snapshot(List<ReviewItemSnapshot> items,ReportInputSelectionPolicy.Selection selection) {
        Map<String,Object> itemValues=new LinkedHashMap<>();
        for(var item:items) {
            var value=new LinkedHashMap<String,Object>();value.put("evidence",item.originalSnapshot());value.put("inheritance",item.inheritance());
            itemValues.put(item.id().toString(),evidence(item.sourceType(),value));
        }
        Map<String,Object> sources=new LinkedHashMap<>();
        for(var source:selection.sources()) {
            var value=new LinkedHashMap<String,Object>(source.execution()); value.put("reasonCode",source.reasonCode());
            sources.put(source.type(),evidence(source.type(),value));
        }
        return Map.of("catalogVersion",VERSION,"items",itemValues,"sources",sources);
    }
}
