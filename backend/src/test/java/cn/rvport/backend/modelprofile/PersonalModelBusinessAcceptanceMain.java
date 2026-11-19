package cn.rvport.backend.modelprofile;

import cn.rvport.backend.RvPortBackendApplication;
import cn.rvport.backend.report.review.*;
import cn.rvport.backend.report.review.ReportMode;
import cn.rvport.backend.report.ReportGenerateRequest;
import cn.rvport.backend.testcomparison.TestComparisonService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** 本地 Compose 验收夹具：只在隔离数据库造已完成测试，不连接 SSH。 */
public final class PersonalModelBusinessAcceptanceMain {
    public static void main(String[] args) throws Exception {
        String projectName = System.getenv().getOrDefault("RVPORT_ACCEPTANCE_PROJECT", "");
        boolean reviewFixture = projectName.matches("rvport-(report-review|review-final)-[0-9]+");
        if (!reviewFixture && !projectName.equals("rvport-personal-model")) {
            throw new IllegalStateException("isolated acceptance project required");
        }
        try (var context = new SpringApplicationBuilder(RvPortBackendApplication.class)
                .web(WebApplicationType.NONE).run("--rvport.worker.enabled=false", "--spring.liquibase.enabled=false")) {
            var db = context.getBean(DSLContext.class);
            var json = context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class);
            boolean standard=args.length==1 && args[0].equals("standard-review");
            boolean personal=args.length==1 && args[0].equals("personal-review");
            if(personal && !reviewFixture) throw new IllegalStateException("isolated review worker required");
            UUID alice = personal?fixtureAccount(db,false):db.fetchOne("select id from users where username=?",standard?"admin":"alice").get("id", UUID.class);
            UUID admin = personal?fixtureAccount(db,true):db.fetchOne("select id from users where username='admin'").get("id", UUID.class);
            if (args.length > 0 && !standard && !personal) {
                retryScenario(context, db, alice, args[0]);
                return;
            }
            UUID project = UUID.randomUUID(), revision = UUID.randomUUID(), scan = UUID.randomUUID();
            Path source = Path.of("/app/backend/runtime/sources/personal-acceptance-" + project);
            Files.createDirectories(source);
            Files.writeString(source.resolve("main.c"), "int main(void) { return 0; }\n");
            Path archive=source.resolveSibling("review-"+project+".zip");
            try(var zip=new java.util.zip.ZipOutputStream(Files.newOutputStream(archive))) {
                for(var name:java.util.List.of("main.c","unused.c")) {
                    zip.putNextEntry(new java.util.zip.ZipEntry(name));
                    zip.write((name.equals("main.c")?"int main(void) { return 0; }\n":"int unused;\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
            db.execute("insert into projects(id,name,stage,status,created_by) values (?,'Personal acceptance','PROJECT_CREATED','ACTIVE',?)", project, alice);
            db.execute("insert into source_revisions(id,project_id,revision_type,working_copy_path,archive_path,is_active,created_by) values (?,?,'ARCHIVE',?,?,true,?)", revision, project, source.toString(), archive.toString(), alice);
            UUID credential = UUID.randomUUID(), environment = UUID.randomUUID(), batch = UUID.randomUUID();
            db.execute("insert into credentials(id,project_id,owner_user_id,kind,name,encrypted_payload,kms_key_version,status) values (?,?,?,'SSH_PASSWORD','never-used',?,'fixture','DISABLED')", credential, project, alice, new byte[]{1});
            db.execute("insert into environments(id,project_id,name,role,arch,host,username,credential_id,status,created_by) values (?,?,'local-fixture','SOURCE_X86','x86_64','invalid.invalid','fixture',?,'DISABLED',?)", environment, project, credential, alice);
            UUID build=UUID.randomUUID();
            db.execute("insert into project_runtime_environments(project_id,source_environment_id,version) values(?,?,1)",project,environment);
            db.execute("insert into build_runs(id,project_id,task_id,source_revision_id,environment_id,command,status,build_scope,environment_config_version,finished_at) values(?,?,?,?,?,'fixture','SUCCEEDED','SOURCE_ONLY',1,now())",
                    build,project,completedTask(db,project,alice,"BUILD_VALIDATION"),revision,environment);
            UUID scanTask = completedTask(db, project, alice, "KB_SCAN");
            db.execute("insert into kb_scan_runs(id,project_id,task_id,source_revision_id,scope,status,finished_at,source_environment_id,runtime_environment_version,build_run_id) values (?,?,?,?,'{}','SUCCEEDED',now(),?,1,?)", scan, project, scanTask, revision,environment,build);
            db.execute("insert into findings(project_id,scan_run_id,source_revision_id,rule_id,category,severity,title,file_path,line_start,fingerprint,status) values (?,?,?,'fixture','PORTABILITY','MEDIUM','fixture','main.c',1,'fixture','DETECTED')", project, scan, revision);
            var kb = context.getBean(ReportReviewGenerationService.class);
            if(personal) {
                loopbackReview(context,db,project,alice,admin);
                return;
            }
            if(standard) {
                standardReview(context,db,project,revision,admin);
                return;
            }
            if (!Boolean.TRUE.equals(kb.readiness(project, revision, alice, false).get("canGenerateAi")) || !Boolean.TRUE.equals(kb.readiness(project, revision, admin, true).get("canGenerateAi"))) {
                throw new AssertionError("personal readiness failed");
            }
            int before = upstreamCount(json);
            var first = generate(kb,db,project,alice,false);
            awaitTask(db, first);
            assertUpstream(json, before, "alice");
            before = upstreamCount(json);
            var second = generate(kb,db,project,admin,true);
            awaitTask(db, second);
            assertUpstream(json, before, "admin");
            assertOwner(db, first, alice);
            assertOwner(db, second, admin);
            if (cn.rvport.backend.task.TaskStatus.SUCCEEDED != context.getBean(cn.rvport.backend.report.ReportService.class).get(db.fetchOne("select id from reports where task_id=?",first).get("id",UUID.class),admin,true).status()) {
                throw new AssertionError("authorized historical viewer lost project result");
            }
            UUID sourceTest = testRun(db, project, revision, environment, batch, alice, "SOURCE");
            UUID targetTest = testRun(db, project, revision, environment, batch, alice, "TARGET");
            var comparisons = context.getBean(TestComparisonService.class);
            before = upstreamCount(json);
            comparisons.onAutomatedTestFinished(sourceTest);
            var comparison = db.fetchOne("select id,task_id from test_comparison_runs where source_test_run_id=? and target_test_run_id=?", sourceTest, targetTest);
            UUID comparisonTask = comparison.get("task_id", UUID.class);
            awaitTask(db, comparisonTask);
            assertUpstream(json, before, "alice");
            assertOwner(db, comparisonTask, alice);
            comparisons.onAutomatedTestFinished(targetTest);
            if (!comparisonTask.equals(db.fetchOne("select task_id from test_comparison_runs where id=?", comparison.get("id")).get("task_id", UUID.class))) {
                throw new AssertionError("duplicate completion dispatched another task");
            }
            long audits = db.fetchCount(org.jooq.impl.DSL.table("task_logs"),
                    org.jooq.impl.DSL.field("task_id").in(first, second, comparisonTask));
            if (audits < 3) { throw new AssertionError("missing execution audit logs"); }
            Files.writeString(Path.of("/tmp/personal-business-acceptance.json"),
                    context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class).writeValueAsString(java.util.Map.of(
                            "projectId", project, "scanId", scan, "aliceTask", first, "adminTask", second,
                            "comparisonTask", comparisonTask, "status", "PASS")));
            System.out.println("PERSONAL_BUSINESS_PASS: KB owner/admin retrigger, project history, automatic comparison, duplicate completion, worker execution");
        }
    }

    private static UUID fixtureAccount(DSLContext db,boolean elevated) {
        UUID actor=UUID.randomUUID();
        db.execute("insert into users(id,username,password_hash,is_super_admin,must_change_password,status) values(?,?,?, ?,false,'ACTIVE')",
                actor,"review-fixture-"+actor,"fixture-without-a-login-hash",elevated);
        return actor;
    }

    private static void loopbackReview(org.springframework.context.ConfigurableApplicationContext context,DSLContext db,
            UUID project,UUID alice,UUID admin) throws Exception {
        Path state=Files.createTempDirectory("rvport-review-fixture-");
        var mock=new ProcessBuilder("python3","-B","/tmp/report-workflow/fixtures/report_review_loopback.py",state.toString())
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try {
            Path file=state.resolve("port");
            for(int i=0; i<150 && !Files.exists(file) && mock.isAlive(); i++) Thread.sleep(100);
            int port=Integer.parseInt(Files.readString(file));
            var profiles=context.getBean(ModelProfileService.class);
            var generation=context.getBean(ReportReviewGenerationService.class);
            for(UUID actor:java.util.List.of(alice,admin)) {
                boolean elevated=actor.equals(admin);
                String alias=elevated?"admin":"alice";
                UUID runtime=db.fetchOne("select id from agent_runtimes where agent_type=?",elevated?"OPENCODE":"CODEX").get(0,UUID.class);
                var profile=profiles.create(new ModelProfileRequest("review-fixture-"+actor,true,null,"OPENAI_COMPATIBLE",
                        "http://127.0.0.1:"+port+"/"+alias+"/v1","fixture-model","CHAT","fixture-"+alias,true,60,120,
                        java.util.Set.of("name","enabled","modelProviderType","baseUrl","model","wireApi","apiKey","timeoutSeconds","maxRuntimeSeconds")),actor);
                profiles.saveAssignments(new ModelStageAssignmentRequest(java.util.List.of(new ModelStageAssignmentRequest.Entry(
                        "REPORT_AI_REVIEW",profile.id(),runtime))),actor);
                int previous=Files.readAllLines(state.resolve("aliases")).size();
                UUID task=generate(generation,db,project,actor,elevated);
                awaitTask(db,task); assertOwner(db,task,actor);
                var calls=Files.readAllLines(state.resolve("aliases")); var own=calls.subList(previous,calls.size());
                if(own.isEmpty() || own.stream().anyMatch(name->!alias.equals(name))) throw new AssertionError("wrong personal upstream");
                var result=db.fetchOne("select r.configuration_user_id,r.model_profile_id,r.agent_runtime_id,r.publish_state, a.verdict from report_review_runs r join reports p on p.id=r.report_id join report_ai_review_results a on a.report_id=p.id where p.task_id=?",task);
                if(result==null || !actor.equals(result.get(0,UUID.class)) || !profile.id().equals(result.get(1,UUID.class)) ||
                        !runtime.equals(result.get(2,UUID.class)) || !"CURRENT".equals(result.get(3)) || !"INSUFFICIENT_EVIDENCE".equals(result.get(4)))
                    throw new AssertionError("worker failed to publish the selected user's review");
            }
            checkAgentReviewMatrix(context,db,project,admin,port,state);
            int baseline=Files.readAllLines(state.resolve("aliases")).size();
            var old=profiles.resolveAgentForStage(ModelProfileStage.REPORT_AI_REVIEW,alice);
            var lastCurrent=db.fetchOne("select report_id from report_review_runs where project_id=? and publish_state='CURRENT'",project).get(0,UUID.class);
            var tx=new org.springframework.transaction.support.TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
            UUID queued=tx.execute(transaction->{
                UUID task=generate(generation,db,project,alice,false);
                UUID replacement=UUID.randomUUID();
                db.execute("insert into model_profiles(id,owner_user_id,name,enabled,model_provider_type,base_url,model,wire_api,api_key_encrypted,timeout_seconds,max_runtime_seconds,created_by) select ?,owner_user_id,?,true,model_provider_type,base_url,model,wire_api,api_key_encrypted,timeout_seconds,max_runtime_seconds,created_by from model_profiles where id=?",
                        replacement,"replacement-"+replacement,old.modelProfile().getId());
                profiles.saveAssignments(new ModelStageAssignmentRequest(java.util.List.of(new ModelStageAssignmentRequest.Entry(
                        "REPORT_AI_REVIEW",replacement,old.runtime().getId()))),alice);
                db.execute("update model_profiles set enabled=false where id=?",old.modelProfile().getId());
                return task;
            });
            boolean failed=false;
            for(int i=0;i<240;i++) {
                var task=db.fetchOne("select status,retry_count,input_snapshot->'modelExecution'->>'modelProfileId' as profile from tasks where id=?",queued);
                if("FAILED".equals(task.get("status")) && task.get("retry_count",Integer.class)>0) {
                    if(!old.modelProfile().getId().toString().equals(task.get("profile"))) throw new AssertionError("retry silently rebound profile");
                    failed=true; break;
                }
                Thread.sleep(500);
            }
            if(!failed || baseline!=Files.readAllLines(state.resolve("aliases")).size()
                    || !lastCurrent.equals(db.fetchOne("select report_id from report_review_runs where project_id=? and publish_state='CURRENT'",project).get(0,UUID.class)))
                throw new AssertionError("automatic retry did not preserve identity, previous result and zero upstream calls");
            var sourceRun=db.fetchOne("select id,source_revision_id,build_run_id from kb_scan_runs where project_id=?",project);
            verifySourceMatrix(context,db,project,sourceRun.get("source_revision_id",UUID.class),admin,
                    sourceRun.get("id",UUID.class),sourceRun.get("build_run_id",UUID.class));
            var ready=generation.readiness(project,null,admin,true);
            var sources=(java.util.List<?>)ready.get("sources");
            long planned=sources.stream().map(java.util.Map.class::cast).mapToLong(part->((Number)part.get("aiInputCount")).longValue()).sum();
            UUID mixed=generate(generation,db,project,admin,true);
            awaitTask(db,mixed);
            var proof=db.fetchOne("""
                    select count(*) as total,count(a.input_id) as reviewed,count(*) filter(where i.review_state='EXCLUDED' and a.input_id is null) as skipped,
                    count(distinct i.source_type) as sources from report_review_items i join reports r on i.report_id=r.id
                    left join report_ai_review_results a on a.report_id=i.report_id and a.input_id=i.id where r.task_id=?
                    """,mixed);
            if(planned!=6 || proof.get("reviewed",Long.class)!=planned || proof.get("total",Long.class)!=7 ||
                    proof.get("skipped",Long.class)!=1 || proof.get("sources",Integer.class)!=4)
                throw new AssertionError("four-source AI manifest differs from preview or inherited exclusion: "+proof);
            System.out.println("B05_AI_MIXED_PASS: all four sources, six reviewed IDs and one inherited exclusion");
            System.out.println("PERSONAL_RETRY_PASS: disabled pinned profile rejected after automatic retry; zero upstream calls, current report unchanged");
            System.out.println("PERSONAL_REVIEW_PASS: actual worker used owner's Codex, then triggering administrator's OpenCode and distinct model credentials");
        } finally {
            mock.destroy();
            if(!mock.waitFor(3,java.util.concurrent.TimeUnit.SECONDS)) mock.destroyForcibly().waitFor();
            try(var paths=Files.walk(state)) { for(var path:paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path); }
        }
    }
    private static void checkAgentReviewMatrix(org.springframework.context.ConfigurableApplicationContext context,DSLContext db,
            UUID project,UUID admin,int port,Path state) throws Exception {
        var profiles=context.getBean(ModelProfileService.class);
        var generation=context.getBean(ReportReviewGenerationService.class);
        Path scenario=state.resolve("scenario");
        for (String[] mode:java.util.List.of(new String[]{"DEEPSEEK_HARNESS","CHAT","suspected","PENDING_REVIEW"},
                new String[]{"CODEX","RESPONSES","confirmed","TO_FIX"})) {
            UUID runtime=db.fetchOne("select id from agent_runtimes where agent_type=?",mode[0]).get(0,UUID.class);
            var profile=profiles.create(new ModelProfileRequest("review-"+mode[0]+admin,true,null,"OPENAI_COMPATIBLE",
                    "http://127.0.0.1:"+port+"/admin/v1","fixture-model",mode[1],"fixture-admin",true,60,120,
                    java.util.Set.of("name","enabled","modelProviderType","baseUrl","model","wireApi","apiKey","timeoutSeconds","maxRuntimeSeconds")),admin);
            profiles.saveAssignments(new ModelStageAssignmentRequest(java.util.List.of(new ModelStageAssignmentRequest.Entry(
                    "REPORT_AI_REVIEW",profile.id(),runtime))),admin);
            Files.writeString(scenario,mode[2]);
            UUID task=generate(generation,db,project,admin,true);
            awaitTask(db,task);
            var row=db.fetchOne("""
                    select i.review_state,i.ai_review->>'verdict' as verdict from report_review_items i
                    join reports r on i.report_id=r.id where r.task_id=?
                    """,task);
            if (!mode[3].equals(row.get("review_state")) || !("confirmed".equals(mode[2])?"CONFIRMED_ISSUE":"SUSPECTED_FALSE_POSITIVE").equals(row.get("verdict")))
                throw new AssertionError("draft did not follow the validated agent verdict");
        }
        UUID good=db.fetchOne("select report_id from report_review_runs where project_id=? and publish_state='CURRENT'",project).get(0,UUID.class);
        if(db.fetchCount(org.jooq.impl.DSL.table("report_fix_list_confirmations"),org.jooq.impl.DSL.condition("report_id=?",good))!=0)
            throw new AssertionError("AI submitted a final list");
        for(String fault:java.util.List.of("missing","duplicate","unknown","evidence","invalid-json","timeout")) {
            if("timeout".equals(fault)) {
                UUID active=profiles.resolveAgentForStage(ModelProfileStage.REPORT_AI_REVIEW,admin).modelProfile().getId();
                db.execute("update model_profiles set max_runtime_seconds=10 where id=?",active);
            }
            Files.writeString(scenario,fault);
            UUID task=generate(generation,db,project,admin,true);
            String status="";
            for(int i=0;i<360;i++) {
                status=db.fetchOne("select status from tasks where id=?",task).get(0,String.class);
                if(java.util.Set.of("SUCCEEDED","FAILED","TIMEOUT","CANCELLED").contains(status)) break;
                Thread.sleep(500);
            }
            var stateAfter=db.fetchOne("""
                    select r.publish_state,r.mode,p.status from report_review_runs r join reports p on p.id=r.report_id where p.task_id=?
                    """,task);
            if (!java.util.Set.of("FAILED","TIMEOUT").contains(status) || "CURRENT".equals(stateAfter.get("publish_state")) || !"AI_ENHANCED".equals(stateAfter.get("mode"))
                    || !good.equals(db.fetchOne("select report_id from report_review_runs where project_id=? and publish_state='CURRENT'",project).get(0,UUID.class)))
                throw new AssertionError("bad agent output published or replaced the last valid report: "+fault+" "+status);
        }
        var workspace=context.getBean(ReviewWorkspaceService.class);
        UUID item=db.fetchOne("select id from report_review_items where report_id=?",good).get(0,UUID.class);
        workspace.patch(good,new ReviewPatchRequest(0L,java.util.List.of(new ReviewPatchRequest.Item(item,ReviewAction.EXCLUDE,
                "人工排除","Excluded manually"))),"matrix-exclude",admin,true);
        long requests=Files.size(state.resolve("aliases"));
        UUID skipped=generate(generation,db,project,admin,true);
        awaitTask(db,skipped);
        var skip=db.fetchOne("""
                select rr.ai_summary->>'execution',i.review_state from reports r join report_review_runs rr on rr.report_id=r.id
                join report_review_items i on i.report_id=r.id where r.task_id=?
                """,skipped);
        if(!"NOT_REQUIRED".equals(skip.get(0)) || !"EXCLUDED".equals(skip.get(1)) || requests!=Files.size(state.resolve("aliases")))
            throw new AssertionError("reliably excluded input reached the model or lost its disposition");
        Files.delete(scenario);
        System.out.println("B06_B07_PASS: three verdicts, four CLI/protocol pairs, five invalid outputs and a process timeout rejected by actual worker; inherited exclusions made zero model requests, no automatic confirmation or fallback");
    }

    private static void standardReview(org.springframework.context.ConfigurableApplicationContext context,DSLContext db,
            UUID project,UUID source,UUID actor) throws Exception {
        var generation=context.getBean(ReportReviewGenerationService.class);
        var workspace=context.getBean(ReviewWorkspaceService.class);
        var files=context.getBean(ReportSourceWorkspaceService.class);
        var current=context.getBean(CurrentFixListResolver.class);
        var readiness=generation.readiness(project,source,actor,true);
        if(!Boolean.FALSE.equals(readiness.get("canGenerateAi"))) throw new AssertionError("fixture expects an unbound user");
        var body=new ReportGenerateRequest(source,ReportMode.STANDARD,(String)readiness.get("selectionToken"),null,null);
        String key=UUID.randomUUID().toString();
        var started=generation.create(project,body,key,actor,true);
        UUID task=UUID.fromString(started.get("id").toString()),report=(UUID)started.get("reportId");
        awaitTask(db,task);
        if(!task.toString().equals(generation.create(project,body,key,actor,true).get("id").toString()))
            throw new AssertionError("generation replay created a duplicate");
        var row=db.fetchOne("select id from report_review_items where report_id=? and origin='SCANNER'",report);
        UUID original=row.get(0,UUID.class);
        try { workspace.confirm(report,0L,"early",actor,true); throw new AssertionError("pending item allowed confirmation"); }
        catch(cn.rvport.backend.common.error.ApiException e) { if(!"REPORT_PENDING_REVIEW".equals(e.code())) throw e; }
        var excluded=new ReviewPatchRequest(0L,java.util.List.of(new ReviewPatchRequest.Item(original,ReviewAction.EXCLUDE,"不适用","Not applicable")));
        workspace.patch(report,excluded,"exclude",actor,true);
        workspace.patch(report,excluded,"exclude",actor,true);
        UUID empty=(UUID)workspace.confirm(report,1L,"empty",actor,true).get("confirmationId");
        var selected=current.require(project,empty);
        if(!selected.reasonCode().equals("CONFIRMED_EMPTY")) throw new AssertionError(selected.reasonCode());
        byte[] historical;
        try(var in=workspace.download(report,empty,"JSON",actor,true).resource().getInputStream()) { historical=in.readAllBytes(); }
        var file=files.files(report,actor,true,null,"unused.c",1,20).items().getFirst();
        if(!file.get("findingCount").equals(0)) throw new AssertionError("zero finding source missing");
        String fileKey=(String)file.get("fileKey");
        var contents=files.context(report,fileKey,actor,true,1,1);
        var add=new ManualReviewItemRequest(1L,fileKey,(String)contents.get("contentHash"),new SourceSelection.Range(1,1,1,4),
                null,null,"补充发现",null,"需人工适配",null);
        workspace.add(report,add,"manual",actor,true);
        if(!"NEEDS_CONFIRMATION".equals(current.current(project).reasonCode())) throw new AssertionError("old confirmation still current");
        UUID updated=(UUID)workspace.confirm(report,2L,"with-item",actor,true).get("confirmationId");
        if(current.require(project,updated).itemCount()!=1 || empty.equals(updated)) throw new AssertionError("manual item missing");
        try(var in=workspace.download(report,empty,"JSON",actor,true).resource().getInputStream()) {
            if(!java.util.Arrays.equals(historical,in.readAllBytes())) throw new AssertionError("historical download changed");
        }
        verifyRegeneration(context, db, project, source, actor, report, updated, fileKey);
        System.out.println("STANDARD_REVIEW_PASS: worker generation, idempotency, pending gate, empty list, manual source selection, versioned download");
    }

    private static void verifyRegeneration(org.springframework.context.ConfigurableApplicationContext context, DSLContext db,
            UUID project, UUID source, UUID actor, UUID oldReport, UUID confirmed, String fileKey) throws Exception {
        var generation = context.getBean(ReportReviewGenerationService.class);
        var workspace = context.getBean(ReviewWorkspaceService.class);
        var current = context.getBean(CurrentFixListResolver.class);
        var reports = context.getBean(cn.rvport.backend.report.ReportService.class);
        byte[] initial = read(reports.download(oldReport,"HTML",actor,true));
        UUID other = UUID.randomUUID();
        reject("PROJECT_NOT_FOUND", () -> workspace.download(oldReport,confirmed,"JSON",other,false));
        reject("PROJECT_NOT_FOUND", () -> reports.download(oldReport,"HTML",other,false));
        UUID regenerated = standard(context,db,project,source,actor);
        var rows=db.fetch("select id,origin,review_state from report_review_items where report_id=?",regenerated);
        if (rows.size()!=2 || !rows.stream().allMatch(r -> r.get("origin").equals("SCANNER")
                ? r.get("review_state").equals("EXCLUDED") : r.get("review_state").equals("TO_FIX")))
            throw new AssertionError("same revision lost explicit exclusion or manual selection");
        reject("REPORT_READ_ONLY", () -> workspace.confirm(oldReport,2L,"history",actor,true));
        if (!java.util.Arrays.equals(initial,read(reports.download(oldReport,"HTML",actor,true))))
            throw new AssertionError("historical report bytes changed");
        var manual = rows.stream().filter(r->"MANUAL".equals(r.get("origin"))).findFirst().orElseThrow().get("id",UUID.class);
        var excluded = rows.stream().filter(r->"SCANNER".equals(r.get("origin"))).findFirst().orElseThrow().get("id",UUID.class);
        var restore = new ReviewPatchRequest.Item(excluded,ReviewAction.RESTORE,null,null);
        var badBatch = new ReviewPatchRequest(0L,java.util.List.of(restore,new ReviewPatchRequest.Item(
                UUID.randomUUID(),ReviewAction.INCLUDE,null,null)));
        reject("REPORT_REVIEW_ITEM_NOT_FOUND",()->workspace.patch(regenerated,badBatch,"bad-batch",actor,true));
        if (!"EXCLUDED".equals(workspace.detail(regenerated,excluded,actor,true).get("reviewState")))
            throw new AssertionError("partial batch was written");
        workspace.patch(regenerated,new ReviewPatchRequest(0L,java.util.List.of(restore)),"restore",actor,true);
        var page = workspace.list(regenerated,new ReviewItemQuery(1,1,1L,null,"MANUAL",null,null,null,null,null,null,null),actor,true);
        if (((Number)((java.util.Map<?,?>)page.get("draftSummary")).get("pendingReviewCount")).intValue()!=1
                || ((Number)((java.util.Map<?,?>)page.get("filteredSummary")).get("pendingReviewCount")).intValue()!=0)
            throw new AssertionError("filtered list hid global pending count");
        reject("REPORT_PENDING_REVIEW",()->workspace.confirm(regenerated,1L,"pending",actor,true));
        reject("REPORT_DRAFT_CONFLICT",()->workspace.patch(regenerated,badBatch,"stale",actor,true));
        var include=new ReviewPatchRequest.Item(excluded,ReviewAction.INCLUDE,null,null);
        workspace.patch(regenerated,new ReviewPatchRequest(1L,java.util.List.of(include,
                new ReviewPatchRequest.Item(manual,ReviewAction.INCLUDE,null,null))),"include",actor,true);
        try (var threads=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<UUID> submit = () -> {
                start.await();
                return (UUID) workspace.confirm(regenerated,2L,UUID.randomUUID().toString(),actor,true).get("confirmationId");
            };
            var first=threads.submit(submit);
            var second=threads.submit(submit);
            start.countDown();
            if (!first.get().equals(second.get())) throw new AssertionError("two confirmations for one version");
        }
        System.out.println("B10_B11_B12_B14_B15_B16_B18_PASS: atomic batch, global pending, immutable history, isolation, concurrent confirmation");
        UUID next = UUID.randomUUID();
        var tx=new org.springframework.transaction.support.TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        var late=tx.execute(status -> {
            var ready=generation.readiness(project,source,actor,true);
            var result=generation.create(project,new ReportGenerateRequest(source,ReportMode.STANDARD,
                    (String)ready.get("selectionToken"),regenerated,2L),"queued-before-source-update",actor,true);
            db.execute("update source_revisions set is_active=false where id=?",source);
            db.execute("""
                    insert into source_revisions(id,project_id,revision_type,working_copy_path,archive_path,is_active,created_by)
                    select ?,project_id,revision_type,working_copy_path,archive_path,true,created_by from source_revisions where id=?
                    """,next,source);
            return result;
        });
        awaitTask(db,UUID.fromString(late.get("id").toString()));
        if (!"HISTORICAL_CONTEXT_CHANGED".equals(db.fetchOne("select publish_state from report_review_runs where report_id=?",
                late.get("reportId")).get(0,String.class))) throw new AssertionError("late generation was published as current");
        var empty=generation.readiness(project,next,actor,true);
        if (!Boolean.FALSE.equals(empty.get("canGenerate")) || current.current(project).available()) throw new AssertionError("stale context accepted");
        reject("REPORT_ANALYSIS_NOT_READY",()->generation.create(project,new ReportGenerateRequest(next,ReportMode.STANDARD,
                (String)empty.get("selectionToken"),null,null),"no-input",actor,true));
        UUID build=UUID.randomUUID(), scan=UUID.randomUUID();
        db.execute("""
                insert into build_runs(id,project_id,task_id,source_revision_id,environment_id,command,status,build_scope,environment_config_version,finished_at)
                select ?,project_id,?,?,environment_id,command,status,build_scope,environment_config_version,now()
                from build_runs where project_id=? and source_revision_id=? limit 1
                """,build,completedTask(db,project,actor,"BUILD_VALIDATION"),next,project,source);
        db.execute("""
                insert into kb_scan_runs(id,project_id,task_id,source_revision_id,scope,status,finished_at,source_environment_id,runtime_environment_version,build_run_id)
                select ?,project_id,?,?,scope,'SUCCEEDED',now(),source_environment_id,runtime_environment_version,?
                from kb_scan_runs where project_id=? and source_revision_id=? limit 1
                """,scan,completedTask(db,project,actor,"KB_SCAN"),next,build,project,source);
        UUID newReport=standard(context,db,project,next,actor);
        UUID target=(UUID)workspace.confirm(newReport,0L,"zero",actor,true).get("confirmationId");
        if (current.require(project,target).itemCount()!=0) throw new AssertionError("new revision inherited old items");
        var compare=context.getBean(cn.rvport.backend.source.SourceCompareService.class);
        var pair=new cn.rvport.backend.source.RecheckCreateRequest(source,next,confirmed,target);
        var task=compare.create(project,pair,actor,true,null);
        awaitTask(db,task.id());
        var pairCounts=db.fetchOne("select (summary->>'baseConfirmedCount')::int b,(summary->>'targetConfirmedCount')::int t from recheck_runs where task_id=?",task.id());
        if(pairCounts.get("b",Integer.class)!=1 || pairCounts.get("t",Integer.class)!=0) throw new AssertionError("excluded item counted as a repair");
        if (!compare.create(project,pair,actor,true,null).id().equals(task.id())) throw new AssertionError("pair not idempotent");
        var diff=db.fetch("select diff_status,old_snapshot,new_snapshot from diff_items where recheck_run_id=(select id from recheck_runs where task_id=?)",task.id());
        if (diff.size()!=1 || !"NOT_OBSERVED".equals(diff.getFirst().get("diff_status")) || diff.getFirst().get("old_snapshot")==null
                || diff.getFirst().get("new_snapshot")!=null) throw new AssertionError("unobserved manual issue lost its original evidence");
        reject("FIX_LIST_CONFIRMATION_REQUIRED",()->compare.create(project,new cn.rvport.backend.source.RecheckCreateRequest(
                source,next,target,confirmed),actor,true,null));
        verifySourceMatrix(context,db,project,next,actor,scan,build);
        System.out.println("B01_B03_B09_B13_B17_RECHECK_PASS: source change while queued, zero result, pinned historical baseline and empty target");
    }

    private static void verifySourceMatrix(org.springframework.context.ConfigurableApplicationContext context, DSLContext db,
            UUID project, UUID revision, UUID actor, UUID scan, UUID build) {
        var policy=context.getBean(ReportInputSelectionPolicy.class);
        UUID environment=db.fetchOne("select environment_id from build_runs where id=?",build).get(0,UUID.class);
        UUID target=UUID.randomUUID();
        db.execute("""
                insert into environments(id,project_id,name,role,arch,host,username,credential_id,status,created_by)
                select ?,project_id,'target-fixture','TARGET_RISCV','riscv64',host,username,credential_id,'DISABLED',created_by from environments where id=?
                """,target,environment);
        db.execute("update project_runtime_environments set target_environment_id=? where project_id=?",target,project);
        db.execute("update build_runs set build_facts=?::jsonb where id=?", "{\"compileDatabase\":{\"available\":true}}",build);
        requireSources(policy.current(project),"KB_FINDING");
        db.execute("update kb_scan_runs set status='FAILED' where id=?",scan);
        UUID fp=UUID.randomUUID(), memory=UUID.randomUUID();
        for(String analyzer:java.util.List.of("FLOATING_POINT","MEMORY_ORDER")) {
            UUID run=analyzer.equals("FLOATING_POINT")?fp:memory;
            db.execute("""
                    insert into specialized_analysis_runs(id,project_id,analyzer_type,source_revision_id,build_run_id,
                    environment_config_version,request_schema_version,result_schema_version,status,analysis_mode,finished_at)
                    values(?,?,?,?,?,?,'v1','v1','PARTIAL','AST_ONLY',now())
                    """,run,project,analyzer,revision,build,analyzer.equals("FLOATING_POINT")?1:null);
            requireSources(policy.current(project));
            db.execute("update specialized_analysis_runs set status='COMPLETE' where id=?",run);
            requireSources(policy.current(project),analyzer+"_FINDING");
            db.execute("update specialized_analysis_runs set status='PARTIAL' where id=?",run);
        }
        UUID ss=UUID.randomUUID(),ts=UUID.randomUUID(),dep=UUID.randomUUID(),task=completedTask(db,project,actor,"DEPENDENCY_ANALYSIS");
        for(var env:java.util.List.of(environment,target)) {
            db.execute("""
                    insert into environment_snapshots(id,environment_id,project_id,task_id,status,collected_at,environment_config_version)
                    values(?,?,?,?,'SUCCEEDED',now(),1)
                    """,env.equals(target)?ts:ss,env,project,completedTask(db,project,actor,"ENV_COLLECT"));
        }
        db.execute("""
                insert into dependency_analysis_runs(id,project_id,task_id,source_revision_id,source_build_run_id,target_environment_id,
                environment_config_version,status,summary,finished_at) values(?,?,?,?,?,?,1,'SUCCEEDED',
                jsonb_build_object('sourceSnapshotId',?::text,'targetSnapshotId',?::text),now())
                """,dep,project,task,revision,build,target,ss.toString(),ts.toString());
        for(var fact:java.util.List.of(new String[]{"missing","MISSING","HIGH"},new String[]{"system","TARGET_INSTALLED","LOW"},
                new String[]{"unknown","MANUAL_CONFIRM_REQUIRED","INFO"})) {
            db.execute("""
                    insert into dependency_analysis_result_items(project_id,source_revision_id,task_id,dependency_analysis_run_id,
                    dependency_type,dependency_name,target_match_status,risk_level) values(?,?,?,?,'LINK_LIBRARY',?,?,?)
                    """,project,revision,task,dep,fact[0],fact[1],fact[2]);
        }
        requireSources(policy.current(project),"DEPENDENCY_RESULT");
        db.execute("update kb_scan_runs set status='SUCCEEDED' where id=?",scan);
        db.execute("update specialized_analysis_runs set status='COMPLETE' where id in (?,?)",fp,memory);
        for(String kind:java.util.List.of("FINDING","SOURCE_CANDIDATE","INCONCLUSIVE","DISCHARGED"))
            db.execute("""
                    insert into specialized_analysis_findings(run_id,project_id,source_revision_id,analyzer_type,external_finding_id,
                    finding_kind,severity,result_status,title) values(?,?,?,'MEMORY_ORDER',?,?,'LOW','OPEN',?)
                    """,memory,project,revision,kind,kind,kind);
        db.execute("""
                insert into specialized_analysis_findings(run_id,project_id,source_revision_id,analyzer_type,external_finding_id,
                    finding_kind,severity,result_status,title) values(?,?,?,'FLOATING_POINT','fp','FINDING','MEDIUM','OPEN','FP')
                """,fp,project,revision);
        var selection=policy.current(project);
        requireSources(selection,"KB_FINDING","DEPENDENCY_RESULT","FLOATING_POINT_FINDING","MEMORY_ORDER_FINDING");
        var depSource=selection.sources().stream().filter(part->part.type().equals("DEPENDENCY_RESULT")).findFirst().orElseThrow();
        var memorySource=selection.sources().stream().filter(part->part.type().equals("MEMORY_ORDER_FINDING")).findFirst().orElseThrow();
        if(depSource.findings().size()!=3 || depSource.candidates().size()!=2 || memorySource.candidates().size()!=3
                || ((Number)memorySource.execution().get("dischargedCount")).intValue()!=1)
            throw new AssertionError("dependency cutoff or formal/candidate separation failed");
        System.out.println("B02_B05_SOURCE_MATRIX_PASS: four single-input selections, partial runs, four-source mix, risk cutoff, memory candidates and discharged count");
    }

    private static void requireSources(ReportInputSelectionPolicy.Selection selection,String... sources) {
        var actual=selection.sources().stream().filter(ReportInputSelectionPolicy.Source::available)
                .map(ReportInputSelectionPolicy.Source::type).collect(java.util.stream.Collectors.toSet());
        if(!actual.equals(java.util.Set.of(sources))) throw new AssertionError("selected "+actual+" expected "+java.util.Arrays.toString(sources));
    }

    private static byte[] read(cn.rvport.backend.report.ReportService.DownloadableReport file) throws Exception {
        try(var stream=file.resource().getInputStream()) { return stream.readAllBytes(); }
    }

    private static void reject(String expected,Runnable operation) {
        try { operation.run(); } catch(cn.rvport.backend.common.error.ApiException e) {
            if(expected.equals(e.code())) return;
            throw e;
        }
        throw new AssertionError("expected " + expected);
    }

    private static UUID standard(org.springframework.context.ConfigurableApplicationContext context, DSLContext db,
            UUID project,UUID source,UUID actor) throws Exception {
        var generation=context.getBean(ReportReviewGenerationService.class);
        var ready=generation.readiness(project,source,actor,true);
        var body=new ReportGenerateRequest(source,ReportMode.STANDARD,(String)ready.get("selectionToken"),
                (UUID)ready.get("regenerationBaseReportId"),(Long)ready.get("regenerationBaseDraftVersion"));
        var started=generation.create(project,body,UUID.randomUUID().toString(),actor,true);
        awaitTask(db,UUID.fromString(started.get("id").toString()));
        return (UUID)started.get("reportId");
    }

    private static UUID generate(ReportReviewGenerationService service,DSLContext db,UUID project,UUID actor,boolean admin) {
        var ready=service.readiness(project,null,actor,admin);
        var base=(UUID)ready.get("regenerationBaseReportId");
        Long version=(Long)ready.get("regenerationBaseDraftVersion");
        var result=service.create(project,new ReportGenerateRequest((UUID)ready.get("sourceRevisionId"),ReportMode.AI_ENHANCED,
                (String)ready.get("selectionToken"),base,version),UUID.randomUUID().toString(),actor,admin);
        return UUID.fromString(result.get("id").toString());
    }

    private static void retryScenario(org.springframework.context.ConfigurableApplicationContext context,
            DSLContext db, UUID alice, String phase) throws Exception {
        var json = context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class);
        Path file = Path.of("/tmp/personal-business-acceptance.json");
        var state = json.readTree(Files.readString(file));
        UUID project = UUID.fromString(state.get("projectId").asText());
        if (phase.equals("queue-retry")) {
            var run = generate(context.getBean(ReportReviewGenerationService.class),db,project,alice,false);
            UUID original = db.fetchOne("select (input_snapshot->'modelExecution'->>'modelProfileId')::uuid as id from tasks where id=?",run).get("id",UUID.class), replacement = UUID.randomUUID();
            db.execute("insert into model_profiles(id,owner_user_id,name,enabled,model_provider_type,base_url,model,wire_api,api_key_encrypted,timeout_seconds,max_runtime_seconds,created_by) select ?,owner_user_id,?,true,model_provider_type,base_url,model,wire_api,api_key_encrypted,timeout_seconds,max_runtime_seconds,created_by from model_profiles where id=?",
                    replacement, "retry replacement " + replacement, original);
            db.execute("update model_stage_assignments set model_profile_id=? where owner_user_id=? and stage='REPORT_AI_REVIEW'", replacement, alice);
            db.execute("update model_profiles set enabled=false where id=?", original);
            ((com.fasterxml.jackson.databind.node.ObjectNode) state).put("retryTask", run.toString())
                    .put("originalProfile", original.toString()).put("beforeRetryRequests", upstreamCount(json));
            Files.writeString(file, json.writeValueAsString(state));
            System.out.println("PERSONAL_RETRY_QUEUED: fixed reference disabled after another binding selected");
        } else if (phase.equals("check-retry")) {
            UUID task = UUID.fromString(state.get("retryTask").asText());
            for (int i = 0; i < 120; i++) {
                var row = db.fetchOne("select status,retry_count,input_snapshot from tasks where id=?", task);
                if (row.get("status").equals("FAILED")) {
                    if (row.get("retry_count", Integer.class) < 1) { throw new AssertionError("automatic retry was not exercised"); }
                    var selected = json.readTree(row.get("input_snapshot", org.jooq.JSONB.class).data()).get("modelExecution");
                    if (!selected.get("configurationUserId").asText().equals(alice.toString())
                            || !selected.get("modelProfileId").asText().equals(state.get("originalProfile").asText())
                            || upstreamCount(json) != state.get("beforeRetryRequests").asInt()) {
                        throw new AssertionError("retry changed actor/reference or reached an unauthorized model");
                    }
                    UUID original = UUID.fromString(state.get("originalProfile").asText());
                    db.execute("update model_profiles set enabled=true where id=?", original);
                    db.execute("update model_stage_assignments set model_profile_id=? where owner_user_id=? and stage='REPORT_AI_REVIEW'", original, alice);
                    ((com.fasterxml.jackson.databind.node.ObjectNode) state).put("automaticRetry", "PASS");
                    Files.writeString(file, json.writeValueAsString(state));
                    System.out.println("PERSONAL_RETRY_PASS: original actor/reference preserved, zero upstream calls");
                    return;
                }
                Thread.sleep(500);
            }
            throw new AssertionError("retry did not converge");
        } else { throw new IllegalArgumentException("unknown phase"); }
    }

    private static int upstreamCount(com.fasterxml.jackson.databind.ObjectMapper json) throws Exception {
        try (var stream = java.net.URI.create("http://personal-model-mock:8089/__metrics").toURL().openStream()) {
            return json.readTree(stream).get("requests").asInt();
        }
    }

    private static void assertUpstream(com.fasterxml.jackson.databind.ObjectMapper json, int before, String actor) throws Exception {
        try (var stream = java.net.URI.create("http://personal-model-mock:8089/__identities").toURL().openStream()) {
            var calls = json.readTree(stream);
            if (calls.size() <= before) { throw new AssertionError("business did not call model"); }
            for (int index = before; index < calls.size(); index++) {
                var call = calls.get(index);
                if (!call.get("path").asText().startsWith("/" + actor + "/")
                        || !call.get("key").asText().equals("Bearer fixture-" + actor)) {
                    throw new AssertionError("business reached another user's upstream");
                }
            }
        }
    }

    private static UUID completedTask(DSLContext db, UUID project, UUID actor, String type) {
        UUID id = UUID.randomUUID();
        db.execute("insert into tasks(id,project_id,type,status,input_snapshot,created_by,finished_at) values (?,?,?,'SUCCEEDED',?::jsonb,?,now())",
                id, project, type, "{\"configurationUserId\":\"" + actor + "\"}", actor);
        return id;
    }
    private static UUID testRun(DSLContext db, UUID project, UUID revision, UUID environment, UUID batch, UUID actor, String role) {
        UUID id = UUID.randomUUID(), task = completedTask(db, project, actor, "AUTOMATED_TEST");
        db.execute("insert into test_runs(id,project_id,task_id,source_revision_id,environment_id,command,status,exit_code,test_batch_id,test_batch_role,finished_at) values (?,?,?,?,?,'fixture only','SUCCEEDED',0,?,?,now())",
                id, project, task, revision, environment, batch, role);
        return id;
    }
    private static void awaitTask(DSLContext db, UUID task) throws Exception {
        for (int attempt = 0; attempt < 240; attempt++) {
            String status = db.fetchOne("select status from tasks where id=?", task).get("status", String.class);
            if (status.equals("SUCCEEDED")) { return; }
            if (java.util.List.of("FAILED", "CANCELLED", "TIMEOUT").contains(status)) {
                throw new AssertionError(db.fetchOne("select status,error_code,error_message from tasks where id=?", task));
            }
            Thread.sleep(500);
        }
        throw new AssertionError("business task did not converge: " + task);
    }
    private static void assertOwner(DSLContext db, UUID task, UUID actor) {
        var row = db.fetchOne("select t.created_by, p.owner_user_id from tasks t join model_profiles p on p.id=(t.input_snapshot->'modelExecution'->>'modelProfileId')::uuid where t.id=?", task);
        if (!actor.equals(row.get("created_by")) || !actor.equals(row.get("owner_user_id"))) {
            throw new AssertionError("task used another owner's model");
        }
    }
}
