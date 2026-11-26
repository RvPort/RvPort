package cn.rvport.backend.report.review;

import cn.rvport.backend.analysis.SpecializedBitcodeCapability;
import cn.rvport.backend.analysis.SpecializedBitcodeCapabilityResolver;
import cn.rvport.backend.buildvalidation.BuildValidationRepository;
import cn.rvport.backend.buildvalidation.BuildValidationRunView;
import cn.rvport.backend.dependency.DependencyAnalysisRepository;
import cn.rvport.backend.dependency.DependencyRiskLevel;
import cn.rvport.backend.dependency.DependencyTargetMatchStatus;
import cn.rvport.backend.kb.KbScanContextService;
import cn.rvport.backend.project.runtime.ProjectRuntimeEnvironmentRepository;
import cn.rvport.backend.project.runtime.ProjectRuntimeEnvironmentView;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportInputSelectionPolicy {
    public static final String POLICY_VERSION="report-input-v1";
    private final KbScanContextService contexts;
    private final ProjectRuntimeEnvironmentRepository environments;
    private final BuildValidationRepository builds;
    private final DependencyAnalysisRepository dependencies;
    private final SpecializedBitcodeCapabilityResolver capabilities;
    private final ReportSourceRepository sources;
    public ReportInputSelectionPolicy(KbScanContextService contexts, ProjectRuntimeEnvironmentRepository environments,
            BuildValidationRepository builds, DependencyAnalysisRepository dependencies,
            SpecializedBitcodeCapabilityResolver capabilities, ReportSourceRepository sources) {
        this.contexts=contexts; this.environments=environments; this.builds=builds; this.dependencies=dependencies;
        this.capabilities=capabilities; this.sources=sources;
    }
    public record Source(String type, UUID runId, String status, String resultVersion, String reasonCode,
            Map<String,Object> execution, List<Map<String,Object>> findings, List<Map<String,Object>> candidates) {
        public boolean available() { return runId!=null; }
    }
    public record Selection(UUID projectId, UUID sourceRevisionId, String contextHash, List<Source> sources) {
        public boolean canGenerate() { return sourceRevisionId!=null && sources.stream().anyMatch(Source::available); }
        public List<Map<String,Object>> candidates() { return sources.stream().flatMap(s->s.candidates().stream()).toList(); }
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Selection current(UUID project) {
        var kb=contexts.current(project);
        var runtime=environments.find(project).orElseGet(()->ProjectRuntimeEnvironmentView.empty(project));
        UUID revision=kb.sourceRevisionId();
        List<Source> selected=new ArrayList<>();
        selected.add(source("KB_FINDING",kb.ready()?sources.kb(project,revision,kb.sourceEnvironmentId(),
                kb.runtimeEnvironmentVersion(),kb.build().id()):Optional.empty()));
        BuildValidationRunView dependencyBuild=null, specializedBuild=null;
        if(revision!=null && runtime.sourceEnvironmentId()!=null) {
            dependencyBuild=builds.findLatestSucceededSourceBuild(project,revision,runtime.sourceEnvironmentId(),runtime.version()).orElse(null);
            specializedBuild=builds.findLatestSucceededSourceBuildWithCompileDatabase(project,revision,runtime.sourceEnvironmentId(),runtime.version()).orElse(null);
        }
        Optional<Map<String,Object>> dependency=Optional.empty();
        UUID sourceSnapshot=null,targetSnapshot=null;
        if(dependencyBuild!=null && runtime.targetEnvironmentId()!=null) {
            sourceSnapshot=dependencies.findLatestSucceededSnapshotId(project,runtime.sourceEnvironmentId()).orElse(null);
            targetSnapshot=dependencies.findLatestSucceededSnapshotId(project,runtime.targetEnvironmentId()).orElse(null);
            if(sourceSnapshot!=null && targetSnapshot!=null) dependency=sources.dependency(project,revision,
                    runtime.targetEnvironmentId(),runtime.version(),dependencyBuild.id(),sourceSnapshot,targetSnapshot);
        }
        selected.add(source("DEPENDENCY_RESULT",dependency));
        SpecializedBitcodeCapability capability=specializedBuild==null?null:capabilities.resolve(specializedBuild.id());
        for(String analyzer:List.of("FLOATING_POINT","MEMORY_ORDER")) {
            selected.add(source(analyzer+"_FINDING",capability==null?Optional.empty():sources.specialized(project,revision,
                    analyzer,specializedBuild.id(),analyzer.equals("FLOATING_POINT")?runtime.version():null,
                    capability.mode().name(),capability.artifactSetId())));
        }
        // 迁移结果作为第五类来源（loop change）：最新 SUCCEEDED 运行（无则 unavailable，不阻塞报告生成）
        selected.add(source("AI_MIGRATION_RESULT",revision==null?Optional.empty():sources.migration(project,revision)));
        String identity=POLICY_VERSION+"|"+project+"|"+revision+"|"+runtime.sourceEnvironmentId()+"|"+runtime.targetEnvironmentId()
                +"|"+runtime.version()+"|"+(kb.build()==null?null:kb.build().id())+"|"+sourceSnapshot+"|"+targetSnapshot
                +"|"+(dependencyBuild==null?null:dependencyBuild.id())+"|"+(specializedBuild==null?null:specializedBuild.id())
                +"|"+(capability==null?null:capability.mode())+"|"+(capability==null?null:capability.artifactSetId());
        for(Source source:selected) identity+="|"+source.type()+":"+source.runId()+":"+source.resultVersion();
        return new Selection(project,revision,SourceSelection.hash(identity.getBytes(StandardCharsets.UTF_8)),List.copyOf(selected));
    }

    private Source source(String type, Optional<Map<String,Object>> selected) {
        if(selected.isEmpty()) return new Source(type,null,null,null,"ANALYSIS_NOT_READY",Map.of(),List.of(),List.of());
        Map<String,Object> run=selected.get();
        UUID id=UUID.fromString(run.get("id").toString());
        String status=string(run.get("status"));
        if (!java.util.Set.of("SUCCEEDED","COMPLETE","COMPLETE_WITH_RISK").contains(status)) {
            return new Source(type,null,status,null,"ANALYSIS_"+status,
                    java.util.Collections.unmodifiableMap(new LinkedHashMap<>(run)),List.of(),List.of());
        }
        List<Map<String,Object>> findings=sources.findings(type,id);
        List<Map<String,Object>> candidates=new ArrayList<>();
        for(Map<String,Object> finding:findings) {
            var candidate=new LinkedHashMap<>(finding);
            if(type.equals("DEPENDENCY_RESULT")) {
                var decision=DependencyReviewPolicy.decide(enumValue(DependencyTargetMatchStatus.class,finding.get("targetMatchStatus")),
                        enumValue(DependencyRiskLevel.class,finding.get("riskLevel")),string(finding.get("providerVersionStatus")),
                        string(finding.get("versionEvidenceConfidence")));
                if(!decision.included()) continue;
                candidate.put("dependencyDecision",decision);
            }
            candidate.put("resultVersion",run.get("resultSchemaVersion"));
            candidate.put("sourceType",type); candidate.put("sourceRunId",id);
            candidate.put("sourceRefId",finding.get("id"));
            candidates.add(java.util.Collections.unmodifiableMap(candidate));
        }
        return new Source(type,id,string(run.get("status")),string(run.get("resultSchemaVersion")),null,
                java.util.Collections.unmodifiableMap(new LinkedHashMap<>(run)),List.copyOf(findings),List.copyOf(candidates));
    }
    private static String string(Object value) { return value==null?null:value.toString(); }
    private static <T extends Enum<T>> T enumValue(Class<T> type,Object value) {
        try { return value==null?null:Enum.valueOf(type,value.toString()); }
        catch(IllegalArgumentException ex) { return null; }
    }
}
