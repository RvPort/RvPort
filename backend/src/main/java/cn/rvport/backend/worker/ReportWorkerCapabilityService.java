package cn.rvport.backend.worker;

import cn.rvport.backend.agentruntime.AgentRuntimeRepository;
import cn.rvport.backend.config.TaskQueueProperties;
import cn.rvport.backend.config.WorkerProperties;
import cn.rvport.backend.jooq.tables.records.AgentRuntimesRecord;
import cn.rvport.backend.report.review.SourceSelection;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ReportWorkerCapabilityService {
    public static final String POLICY="report-sandbox.v1";
    private final ReportWorkerCapabilityRepository repository;
    private final AgentRuntimeRepository runtimes;
    private final ReportSandboxProbe probe;
    private final TaskQueueProperties queue;
    private final WorkerProperties workers;
    private Map<String,Object> cached;
    private OffsetDateTime nextProbe=OffsetDateTime.MIN;
    public ReportWorkerCapabilityService(ReportWorkerCapabilityRepository repository,AgentRuntimeRepository runtimes,
            ReportSandboxProbe probe,TaskQueueProperties queue,WorkerProperties workers) {
        this.repository=repository;this.runtimes=runtimes;this.probe=probe;this.queue=queue;this.workers=workers;
    }
    public void heartbeat(String worker) {
        OffsetDateTime now=OffsetDateTime.now();
        if(!now.isBefore(nextProbe)) {
            Map<String,Object> identities=new LinkedHashMap<>();
            for(var runtime:runtimes.list()) if(Boolean.TRUE.equals(runtime.getEnabled())) identities.put(runtime.getId().toString(),identity(runtime));
            String status=probe.probe();
            OffsetDateTime observed=OffsetDateTime.now();
            cached=Map.of("status",status,"policyVersion",POLICY,"runtimes",identities,
                    "observedAt",observed.toString(),"expiresAt",observed.plusSeconds(90).toString());
            nextProbe=observed.plusSeconds(30);
        }
        repository.publish(worker,queue.queue(),cached);
    }
    public Map<String,Object> current(AgentRuntimesRecord runtime,boolean required) {
        if(!required) return result("NOT_REQUIRED",false,null,null,null);
        if(runtime==null) return result("UNKNOWN",true,"WORKER_CAPABILITY_UNKNOWN",null,null);
        return aggregate(repository.observations(queue.queue(),OffsetDateTime.now().minusSeconds(workers.offlineTimeoutSeconds())),runtime,OffsetDateTime.now());
    }
    static Map<String,Object> aggregate(List<Map<String,Object>> values,AgentRuntimesRecord runtime,OffsetDateTime now) {
        if(values.isEmpty()) return result("UNKNOWN",true,"WORKER_CAPABILITY_UNKNOWN",null,null);
        Set<String> statuses=new HashSet<>();OffsetDateTime oldest=null,expires=null;
        for(var value:values) {
            if(!POLICY.equals(value.get("policyVersion")) || !(value.get("runtimes") instanceof Map<?,?> ids)
                    || !identity(runtime).equals(ids.get(runtime.getId().toString())))
                return result("UNKNOWN",true,"WORKER_CAPABILITY_UNKNOWN",null,null);
            try {
                var observed=OffsetDateTime.parse(String.valueOf(value.get("observedAt")));
                var end=OffsetDateTime.parse(String.valueOf(value.get("expiresAt")));
                if(!end.isAfter(now) || observed.isAfter(now) || !end.isAfter(observed) || end.isAfter(observed.plusSeconds(90)))
                    return result("UNKNOWN",true,"WORKER_CAPABILITY_STALE",null,null);
                oldest=oldest==null || observed.isBefore(oldest)?observed:oldest;
                expires=expires==null || end.isBefore(expires)?end:expires;
            } catch(java.time.DateTimeException ex) { return result("UNKNOWN",true,"WORKER_CAPABILITY_UNKNOWN",null,null); }
            statuses.add(Objects.toString(value.get("status"),"UNKNOWN"));
        }
        if(statuses.size()!=1) return result("UNKNOWN",true,"WORKER_CAPABILITY_MIXED",oldest,expires);
        String status=statuses.iterator().next();
        if(!List.of("AVAILABLE","UNAVAILABLE").contains(status)) return result("UNKNOWN",true,"WORKER_CAPABILITY_UNKNOWN",oldest,expires);
        return result(status,true,status.equals("UNAVAILABLE")?"REPORT_AI_SANDBOX_UNAVAILABLE":null,oldest,expires);
    }
    static String identity(AgentRuntimesRecord runtime) {
        return SourceSelection.hash((POLICY+"|"+runtime.getId()+"|"+runtime.getAgentType()+"|"+runtime.getExecutablePath()+"|"
                +runtime.getDefaultArgs()+"|"+runtime.getConfig()).getBytes(StandardCharsets.UTF_8));
    }
    private static Map<String,Object> result(String status,boolean required,String code,OffsetDateTime observed,OffsetDateTime expires) {
        var result=new LinkedHashMap<String,Object>();
        result.put("status",status);result.put("executionRequired",required);result.put("reasonCode",code);
        result.put("observedAt",observed);result.put("expiresAt",expires);
        result.put("message",code==null?null:status.equals("UNAVAILABLE")?"报告 AI 执行沙箱不可用":"尚不能确认 Worker 的报告 AI 执行能力");
        result.put("messageEn",code==null?null:status.equals("UNAVAILABLE")?"The report AI execution sandbox is unavailable":"Report AI execution capability has not been confirmed");
        return result;
    }
}
