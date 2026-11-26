package cn.rvport.backend.report.review;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;

class ReviewSnapshotFactoryTests {
    final UUID source=UUID.randomUUID(), project=UUID.randomUUID(), actor=UUID.randomUUID(), report=UUID.randomUUID(), run=UUID.randomUUID();
    final ReportSourceSnapshotService.File file=new ReportSourceSnapshotService.File("key","src/a.c","hash",20,UUID.randomUUID(),true,null);
    final ReportSourceSnapshotService.Manifest fixed=new ReportSourceSnapshotService.Manifest(source,"digest",List.of(file),null);
    final ReviewSnapshotFactory factory=new ReviewSnapshotFactory(new ObjectMapper());
    ReviewWorkspaceRepository.Draft base(UUID revision) { return new ReviewWorkspaceRepository.Draft(report,project,revision,"ctx","CURRENT",4,7,true,null,null); }
    Map<String,Object> fact(String type) {
        var row=new LinkedHashMap<String,Object>(); row.put("sourceType",type);row.put("sourceRunId",run);row.put("sourceRefId",UUID.randomUUID());
        row.put("filePath","./src/a.c");row.put("lineStart",2);row.put("fingerprint","stable");row.put("severity","LOW");
        row.put("evidence",List.of(Map.of("symbol","local")));return row;
    }
    List<ReviewItemSnapshot> items(List<Map<String,Object>> rows, ReviewWorkspaceRepository.Draft base,List<Map<String,Object>> previous) {
        var selection=new ReportInputSelectionPolicy.Selection(project,source,"ctx",List.of(new ReportInputSelectionPolicy.Source(
                "KB_FINDING",run,"SUCCEEDED","v1",null,Map.of(),rows,rows)));
        return factory.create(selection,fixed,actor,base,previous);
    }
    Map<String,Object> old(ReviewItemSnapshot item) {
        return Map.of("origin","SCANNER","id",item.id(),"identityHash",item.identityHash(),"evidenceHash",item.evidenceHash(),
                "reviewState","EXCLUDED","restoreState","PENDING_REVIEW","exclusionReason","不适用","exclusionReasonEn","Not applicable","hasManualDecision",true);
    }
    @Test void locationsBelongToThePinnedSourceAndKeepDistinctOccurrences() {
        var row=fact("KB_FINDING");
        var entries=new ArrayList<Map<String,Object>>();
        for(var pair:List.of(List.of("src/a.c","4","7"),List.of("src/a.c","4","7"),List.of("other.c","4","7"),
                List.of("src/a.c","7","4"),List.of("src/a.c","4","21"),List.of("src/a.c","0","1"))) {
            entries.add(Map.of("location",Map.of("file",pair.get(0),"span",Map.of("start",Map.of("line",Integer.valueOf(pair.get(1))),
                    "end",Map.of("line",Integer.valueOf(pair.get(2)))))));
        }
        row.put("evidence",Map.of("finding",Map.of("occurrences",entries))); row.put("lineStart",1); row.remove("lineEnd");
        var item=items(List.of(row),null,List.of()).getFirst();
        assertThat(item.originalSnapshot().get("locations")).isEqualTo(List.of(
                Map.of("fileKey","key","path","src/a.c","startLine",1,"endLine",1),
                Map.of("fileKey","key","path","src/a.c","startLine",4,"endLine",7)));
        assertThat(item.originalSnapshot().get("groupKey")).isNotNull();
    }
    @Test void stableOccurrenceCanInheritOnlyWithinOneRevisionAndWithUnchangedEvidence() {
        var row=fact("KB_FINDING");var first=items(List.of(row,row),null,List.of()).getFirst();
        assertThat(first.relativePath()).isEqualTo("src/a.c");assertThat(first.fileKey()).isEqualTo("key");
        var prior=old(first); row.put("sourceRunId",UUID.randomUUID());row.put("sourceRefId",UUID.randomUUID());
        var current=items(List.of(row,row),base(source),List.of(prior));
        assertThat(current).singleElement().satisfies(i->{
            assertThat(i.decision().state()).isEqualTo(ReviewState.EXCLUDED);
            assertThat(i.inheritance()).containsEntry("fromItemId",first.id());
            assertThat(i.originalSnapshot()).containsEntry("riskLevel","LOW");
        });
        assertThat(items(List.of(row),base(UUID.randomUUID()),List.of(prior)).getFirst().inheritance()).containsEntry("reasonCode","SOURCE_VERSION_CHANGED");
        row.put("evidence",List.of(Map.of("symbol","changed")));
        assertThat(items(List.of(row),base(source),List.of(prior)).getFirst().inheritance()).containsEntry("reasonCode","EVIDENCE_CHANGED");
        assertThat(items(List.of(row),base(source),List.of(prior)).getFirst().decision().state()).isEqualTo(ReviewState.PENDING_REVIEW);
    }
    @Test void onlyAnExplicitDispositionSurvivesAnotherGeneration() {
        var row = fact("KB_FINDING");
        var first = items(List.of(row), null, List.of()).getFirst();
        var prior = new HashMap<>(old(first));
        prior.put("reviewState", "TO_FIX");
        prior.remove("restoreState");
        prior.remove("exclusionReason");
        prior.remove("exclusionReasonEn");
        prior.put("hasManualDecision", false);
        var automatic = items(List.of(row), base(source), List.of(prior)).getFirst();
        assertThat(automatic.decision().state()).isEqualTo(ReviewState.PENDING_REVIEW);
        assertThat(automatic.inheritance()).containsEntry("inherited", false);
        prior.put("hasManualDecision", true);
        var kept = items(List.of(row), base(source), List.of(prior)).getFirst();
        assertThat(kept.decision().state()).isEqualTo(ReviewState.TO_FIX);
        assertThat(kept.inheritance()).containsEntry("manualDecision", true);
        prior.put("reviewState", "PENDING_REVIEW");
        assertThat(items(List.of(row), base(source), List.of(prior)).getFirst().inheritance())
                .containsEntry("inherited", false);
    }
    @Test void changedAnalysisScopeOrEvidenceAvailabilityRequiresHumanRecheck() {
        var row=fact("KB_FINDING");
        row.put("analysisEvidence",Map.of("effectiveInputDigest","source-only","missingEvidence",List.of("BITCODE_UNAVAILABLE")));
        var first=items(List.of(row),null,List.of()).getFirst();
        assertThat(items(List.of(row),base(source),List.of(old(first))).getFirst().decision().state())
                .isEqualTo(ReviewState.EXCLUDED);
        row.put("analysisEvidence",Map.of("effectiveInputDigest","enhanced","missingEvidence",List.of()));
        var regenerated=items(List.of(row),base(source),List.of(old(first))).getFirst();
        assertThat(regenerated.decision().state()).isEqualTo(ReviewState.PENDING_REVIEW);
        assertThat(regenerated.inheritance()).containsEntry("reasonCode","EVIDENCE_CHANGED");
        assertThat(first.originalSnapshot().get("analysisEvidence")).isEqualTo(Map.of(
                "effectiveInputDigest","source-only","missingEvidence",List.of("BITCODE_UNAVAILABLE")));
    }
    @Test void duplicateIdentityOnEitherSideNeverCopiesAUsersExclusion() {
        var row=fact("KB_FINDING");var prior=old(items(List.of(row),null,List.of()).getFirst());
        var second=new LinkedHashMap<>(row);second.put("sourceRefId",UUID.randomUUID());
        assertThat(items(List.of(row,second),base(source),List.of(prior))).hasSize(2).allSatisfy(i->{
            assertThat(i.decision().state()).isEqualTo(ReviewState.PENDING_REVIEW);
            assertThat(i.inheritance()).containsEntry("reasonCode","MATCH_AMBIGUOUS");
        });
        assertThat(items(List.of(row),base(source),List.of(prior,prior)).getFirst().decision().state()).isEqualTo(ReviewState.PENDING_REVIEW);
    }
    @Test void unanchoredOrUnsafePathsDoNotCreateAFileAndCandidatesKeepTheirOwnRisk() {
        for(String path:List.of("","/tmp/a.c","D:/tmp/a.c","src/../a.c","src//a.c","/","unsafe"+(char)0+"path")){
            var row=fact("MEMORY_ORDER_FINDING");row.remove("fingerprint");row.remove("severity");row.put("filePath",path);
            row.put("findingKind","SOURCE_CANDIDATE");row.put("startLine",1);
            var fresh=items(List.of(row),null,List.of()).getFirst();assertThat(fresh.fileKey()).isNull();
            assertThat(fresh.originalSnapshot()).containsEntry("riskLevel",null).containsEntry("findingKind","SOURCE_CANDIDATE");
            assertThat(items(List.of(row),base(source),List.of(old(fresh))).getFirst().decision().state()).isEqualTo(ReviewState.PENDING_REVIEW);
        }
        var dep=fact("DEPENDENCY_RESULT");dep.remove("fingerprint");dep.put("dependencyName","libx");dep.put("riskLevel","HIGH");
        var found=items(List.of(dep),null,List.of()).getFirst();
        assertThat(found.title()).isEqualTo("libx"); assertThat(found.originalSnapshot()).containsEntry("riskLevel","HIGH");
    }
    @Test void manualItemsKeepTheirOriginalAuthorOnlyForSameFileContent() {
        var manual=new LinkedHashMap<String,Object>();manual.put("origin","MANUAL");manual.put("id",UUID.randomUUID());manual.put("createdBy",actor);
        manual.put("sourceContentHash","hash");manual.put("identityHash","i");manual.put("evidenceHash","e");manual.put("relativePath","src/a.c");
        manual.put("title","人工");manual.put("titleEn","Manual");manual.put("originalSnapshot",Map.of());manual.put("manualDetails",Map.of("snippet","foo"));
        manual.put("reviewState","TO_FIX");manual.put("snippetHash",SourceSelection.hash("foo".getBytes(java.nio.charset.StandardCharsets.UTF_8)));manual.put("startLine",2);manual.put("startColumn",1);manual.put("endLine",2);manual.put("endColumn",4);
        assertThat(items(List.of(),base(source),List.of(manual))).singleElement().satisfies(i->{
            assertThat(i.origin()).isEqualTo("MANUAL");assertThat(i.createdBy()).isEqualTo(actor);assertThat(i.snippetHash()).isEqualTo(manual.get("snippetHash"));});
        manual.put("sourceContentHash","different");assertThat(items(List.of(),base(source),List.of(manual))).isEmpty();
        manual.put("relativePath","missing.c");assertThat(items(List.of(),base(source),List.of(manual))).isEmpty();
    }

    Map<String,Object> previous(Map<String,Object> base, Map<String,Object> originalSnapshot) {
        var prev=new LinkedHashMap<>(base);
        prev.put("originalSnapshot",originalSnapshot);
        return prev;
    }
    @Test void migrationStateInheritsWhenEvidenceUnchangedWhateverTheReviewDecision() {
        var row=fact("KB_FINDING"); row.put("titleEn","Custom EN");
        var first=items(List.of(row),null,List.of()).getFirst();
        assertThat(first.titleEn()).isEqualTo("Custom EN");
        var runId="11111111-2222-3333-4444-555555555555";
        // 复核已决（EXCLUDED）：迁移状态仍继承，复核结论原样保留
        var decided=previous(old(first),new LinkedHashMap<>(Map.of("migrationState","MIGRATED","migrationRunId",runId)));
        var second=items(List.of(row),base(source),List.of(decided)).getFirst();
        assertThat(second.originalSnapshot().get("migrationState")).isEqualTo("MIGRATED");
        assertThat(second.originalSnapshot().get("migrationRunId")).isEqualTo(runId);
        assertThat(second.decision().state()).isEqualTo(ReviewState.EXCLUDED);
        assertThat(second.inheritance()).containsEntry("reasonCode","SAME_SOURCE_EVIDENCE");
        // 自动继承（人工未决）：决策回到 PENDING_REVIEW，迁移状态仍带入
        var pending=new LinkedHashMap<>(old(first));
        pending.put("hasManualDecision",false); pending.put("reviewState","PENDING_REVIEW");
        var third=items(List.of(row),base(source),List.of(previous(pending,Map.of("migrationState","MIGRATION_NO_WORK")))).getFirst();
        assertThat(third.originalSnapshot().get("migrationState")).isEqualTo("MIGRATION_NO_WORK");
        assertThat(third.decision().state()).isEqualTo(ReviewState.PENDING_REVIEW);
        assertThat(third.inheritance()).containsEntry("reasonCode","NO_MANUAL_DECISION");
        // 上一代无 migrationState：不添加键；有 state 无 runId：不添加 migrationRunId
        var plain=items(List.of(row),base(source),List.of(previous(old(first),Map.of("reviewState","EXCLUDED")))).getFirst();
        assertThat(plain.originalSnapshot().containsKey("migrationState")).isFalse();
        var noRun=items(List.of(row),base(source),List.of(previous(old(first),Map.of("migrationState","MIGRATED")))).getFirst();
        assertThat(noRun.originalSnapshot().get("migrationState")).isEqualTo("MIGRATED");
        assertThat(noRun.originalSnapshot().containsKey("migrationRunId")).isFalse();
        // 证据变化：条目不继承任何复核结论（迁移状态同样不会出现在旧条目丢失的路径上）
        var changed=items(List.of(row),base(UUID.randomUUID()),List.of()).getFirst();
        assertThat(changed.originalSnapshot().containsKey("migrationState")).isFalse();
        assertThat(changed.inheritance()).containsEntry("reasonCode","SOURCE_VERSION_CHANGED");
    }
    @Test void manualItemsReattachOnlyWhenFileContentAndSnippetIntact() {
        var good=new LinkedHashMap<String,Object>();
        good.put("origin","MANUAL"); good.put("id",UUID.randomUUID()); good.put("createdBy",UUID.randomUUID().toString()); good.put("identityHash","idh"); good.put("evidenceHash","evh");
        good.put("relativePath","src/a.c"); good.put("sourceContentHash","hash"); good.put("title","人工补充"); good.put("titleEn","Manual");
        good.put("snippetHash",SourceSelection.hash("snippet".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        good.put("manualDetails",Map.of("snippet","snippet")); good.put("reviewState","TO_FIX"); good.put("startLine",1); good.put("endLine",3);
        good.put("exclusionReasonEn","Not applicable");
        var attached=items(List.of(),base(source),List.of(good));
        assertThat(attached).anySatisfy(i->{
            assertThat(i.origin()).isEqualTo("MANUAL");
            assertThat(i.exclusionReasonEn()).isEqualTo("Not applicable");
            assertThat(i.decision().state()).isEqualTo(ReviewState.TO_FIX);
        });
        var stale=new LinkedHashMap<>(good); stale.put("sourceContentHash","other-hash");
        assertThat(items(List.of(),base(source),List.of(stale))).noneMatch(i->"MANUAL".equals(i.origin()));
        var badSnippet=new LinkedHashMap<>(good); badSnippet.put("snippetHash","mismatch");
        assertThat(items(List.of(),base(source),List.of(badSnippet))).noneMatch(i->"MANUAL".equals(i.origin()));
        var noSnippet=new LinkedHashMap<>(good); noSnippet.put("manualDetails",Map.of());
        assertThat(items(List.of(),base(source),List.of(noSnippet))).noneMatch(i->"MANUAL".equals(i.origin()));
        var noExclusion=new LinkedHashMap<>(good); noExclusion.remove("exclusionReasonEn");
        assertThat(items(List.of(),base(source),List.of(noExclusion))).anySatisfy(i->{
            if("MANUAL".equals(i.origin())) assertThat(i.exclusionReasonEn()).isNull();
        });
    }
}
