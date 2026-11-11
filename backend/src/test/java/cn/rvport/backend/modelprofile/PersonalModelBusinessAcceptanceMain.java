package cn.rvport.backend.modelprofile;

import cn.rvport.backend.RvPortBackendApplication;
import cn.rvport.backend.kb.ai.KbAiExtensionService;
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
        if (!"rvport-personal-model".equals(System.getenv("RVPORT_ACCEPTANCE_PROJECT"))) {
            throw new IllegalStateException("isolated acceptance project required");
        }
        try (var context = new SpringApplicationBuilder(RvPortBackendApplication.class)
                .web(WebApplicationType.NONE).run("--rvport.worker.enabled=false", "--spring.liquibase.enabled=false")) {
            var db = context.getBean(DSLContext.class);
            var json = context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class);
            UUID alice = db.fetchOne("select id from users where username='alice'").get("id", UUID.class);
            UUID admin = db.fetchOne("select id from users where username='admin'").get("id", UUID.class);
            if (args.length > 0) {
                retryScenario(context, db, alice, args[0]);
                return;
            }
            UUID project = UUID.randomUUID(), revision = UUID.randomUUID(), scan = UUID.randomUUID();
            Path source = Path.of("/app/backend/runtime/sources/personal-acceptance-" + project);
            Files.createDirectories(source);
            Files.writeString(source.resolve("main.c"), "int main(void) { return 0; }\n");
            db.execute("insert into projects(id,name,stage,status,created_by) values (?,'Personal acceptance','PROJECT_CREATED','ACTIVE',?)", project, alice);
            db.execute("insert into source_revisions(id,project_id,revision_type,working_copy_path,is_active,created_by) values (?,?,'ARCHIVE',?,true,?)", revision, project, source.toString(), alice);
            UUID scanTask = completedTask(db, project, alice, "KB_SCAN");
            db.execute("insert into kb_scan_runs(id,project_id,task_id,source_revision_id,scope,status,finished_at) values (?,?,?,?,'{}','SUCCEEDED',now())", scan, project, scanTask, revision);
            db.execute("insert into findings(project_id,scan_run_id,source_revision_id,rule_id,category,severity,title,file_path,line_start,fingerprint,status) values (?,?,?,'fixture','PORTABILITY','MEDIUM','fixture','main.c',1,'fixture','DETECTED')", project, scan, revision);
            var kb = context.getBean(KbAiExtensionService.class);
            if (!kb.readiness(project, scan, alice, false).ready() || !kb.readiness(project, scan, admin, true).ready()) {
                throw new AssertionError("personal readiness failed");
            }
            int before = upstreamCount(json);
            var first = kb.create(project, scan, null, alice, false, null);
            awaitTask(db, first.task().id());
            assertUpstream(json, before, "alice");
            before = upstreamCount(json);
            var second = kb.create(project, scan, null, admin, true, null);
            awaitTask(db, second.task().id());
            assertUpstream(json, before, "admin");
            assertOwner(db, first.task().id(), alice);
            assertOwner(db, second.task().id(), admin);
            if (!kb.get(first.run().id(), admin, true).status().equals("SUCCEEDED")) {
                throw new AssertionError("authorized historical viewer lost project result");
            }
            UUID credential = UUID.randomUUID(), environment = UUID.randomUUID(), batch = UUID.randomUUID();
            db.execute("insert into credentials(id,project_id,owner_user_id,kind,name,encrypted_payload,kms_key_version,status) values (?,?,?,'SSH_PASSWORD','never-used',?,'fixture','DISABLED')", credential, project, alice, new byte[]{1});
            db.execute("insert into environments(id,project_id,name,role,arch,host,username,credential_id,status,created_by) values (?,?,'local-fixture','SOURCE_X86','x86_64','invalid.invalid','fixture',?,'DISABLED',?)", environment, project, credential, alice);
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
                    org.jooq.impl.DSL.field("task_id").in(first.task().id(), second.task().id(), comparisonTask));
            if (audits < 3) { throw new AssertionError("missing execution audit logs"); }
            Files.writeString(Path.of("/tmp/personal-business-acceptance.json"),
                    context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class).writeValueAsString(java.util.Map.of(
                            "projectId", project, "scanId", scan, "aliceTask", first.task().id(), "adminTask", second.task().id(),
                            "comparisonTask", comparisonTask, "status", "PASS")));
            System.out.println("PERSONAL_BUSINESS_PASS: KB owner/admin retrigger, project history, automatic comparison, duplicate completion, worker execution");
        }
    }

    private static void retryScenario(org.springframework.context.ConfigurableApplicationContext context,
            DSLContext db, UUID alice, String phase) throws Exception {
        var json = context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class);
        Path file = Path.of("/tmp/personal-business-acceptance.json");
        var state = json.readTree(Files.readString(file));
        UUID project = UUID.fromString(state.get("projectId").asText());
        if (phase.equals("queue-retry")) {
            var run = context.getBean(KbAiExtensionService.class).create(project,
                    UUID.fromString(state.get("scanId").asText()), null, alice, false, null);
            UUID original = run.run().modelProfileId(), replacement = UUID.randomUUID();
            db.execute("insert into model_profiles(id,owner_user_id,name,enabled,model_provider_type,base_url,model,wire_api,api_key_encrypted,timeout_seconds,max_runtime_seconds,created_by) select ?,owner_user_id,?,true,model_provider_type,base_url,model,wire_api,api_key_encrypted,timeout_seconds,max_runtime_seconds,created_by from model_profiles where id=?",
                    replacement, "retry replacement " + replacement, original);
            db.execute("update model_stage_assignments set model_profile_id=? where owner_user_id=? and stage='KB_AI_EXTENSION'", replacement, alice);
            db.execute("update model_profiles set enabled=false where id=?", original);
            ((com.fasterxml.jackson.databind.node.ObjectNode) state).put("retryTask", run.task().id().toString())
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
                    db.execute("update model_stage_assignments set model_profile_id=? where owner_user_id=? and stage='KB_AI_EXTENSION'", original, alice);
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
