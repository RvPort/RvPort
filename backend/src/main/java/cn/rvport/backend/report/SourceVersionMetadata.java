package cn.rvport.backend.report;

import java.util.LinkedHashMap;
import java.util.Map;

/** 展示元数据独立于来源选择和证据身份。 */
public final class SourceVersionMetadata {
    private SourceVersionMetadata() { }
    public static Map<String,Object> of(String label,String commit,String origin) {
        var value=new LinkedHashMap<String,Object>();
        value.put("sourceVersionLabel",label);value.put("sourceGitCommit",commit);value.put("sourceMetadataOrigin",origin);
        return value;
    }
    @SuppressWarnings("unchecked")
    static Map<String,Object> resolve(ReportView report,Map<String,Object> fallback) {
        if(report.summary().get("sourceMetadata") instanceof Map<?,?> value) return (Map<String,Object>)value;
        return fallback==null?of(null,null,"UNAVAILABLE"):fallback;
    }
}
