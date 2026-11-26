"""报告交接验收：仅在显式命名的独立本地 Compose 网络执行，不输出凭据。"""
import hashlib
import importlib.util
import json
import os
import secrets
import statistics
import sys
import time
import uuid
from pathlib import Path

if os.environ.get("RVPORT_ACCEPTANCE_PROJECT") != "rvport-report-handoff-1125":
    raise SystemExit("需要本轮独立本地验收资源")
spec = importlib.util.spec_from_file_location("client", "/tmp/client.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
state_path = Path("/tmp/handoff-state.json")
state = json.loads(state_path.read_text())
admin = module.Client("handoff-admin")
admin.request("/api/auth/login", {"username": "admin", "password": state["password"]})
results_path = Path("/tmp/handoff-results.json")
results = json.loads(results_path.read_text()) if results_path.exists() else {}


def key():
    return {"Idempotency-Key": str(uuid.uuid4())}


def body(project, mode="STANDARD"):
    ready = admin.request(f"/api/projects/{project}/reports/evaluation/readiness")
    return {"sourceRevisionId": ready["sourceRevisionId"], "mode": mode, "selectionToken": ready["selectionToken"],
            "baseReportId": ready.get("regenerationBaseReportId"), "expectedDraftVersion": ready.get("regenerationBaseDraftVersion")}


def generate(project, mode="STANDARD"):
    request, headers = body(project, mode), key()
    task = admin.request(f"/api/projects/{project}/reports/evaluation", request, headers=headers)
    replay = admin.request(f"/api/projects/{project}/reports/evaluation", request, headers=headers)
    assert replay["id"] == task["id"]
    done = admin.task(task["id"])
    assert done["status"] == "SUCCEEDED", done.get("errorCode")
    return task["reportId"]


def raw(path):
    return admin.http.open(module.BASE + path, timeout=60).read()


phase = sys.argv[1]
if phase == "main":
    project = admin.request("/api/projects", {"name": "报告边界与大文件验收", "sourceArch": "x86", "targetArch": "riscv64"})["id"]
    state["handoffProject"] = project
    # 5 万行，约 4.8 MB；只有三处字面匹配，其中一处位于尚未加载的末尾。
    lines = ["// " + str(i).zfill(5) + " " + "a" * 86 for i in range(1, 50001)]
    for index in (1, 500, 49998):
        lines[index] = lines[index][:20] + "handoff-needle" + lines[index][34:]
    large = "\n".join(lines) + "\n"
    admin.archive(project, {"large.c": large, "scan.c": "#include <immintrin.h>\n__m256 add(__m256 a,__m256 b){return _mm256_add_ps(a,b);}\n"})
    scan = admin.request(f"/api/projects/{project}/kb-scans", {})
    assert admin.task(scan["id"])["status"] == "SUCCEEDED"
    headers = key()
    for invalid in ({"useAiEnhancement": False}, {"unknown": "private"}, b"{", b"null", b"", {"mode": "wrong"}, {"sourceRevisionId": "bad"}):
        result = admin.request(f"/api/projects/{project}/reports/evaluation", invalid,
                               headers={**headers, "Content-Type": "application/json"}, expected=400)
        assert result["code"] == "REPORT_REQUEST_INVALID"
    request = body(project)
    task = admin.request(f"/api/projects/{project}/reports/evaluation", request, headers=headers)
    assert admin.request(f"/api/projects/{project}/reports/evaluation", request, headers=headers)["id"] == task["id"]
    assert admin.request(f"/api/projects/{project}/reports/evaluation", {**request, "mode": "AI_ENHANCED"}, headers=headers, expected=409)["code"] == "IDEMPOTENCY_KEY_REUSED"
    assert admin.task(task["id"])["status"] == "SUCCEEDED"
    report = task["reportId"]
    state["handoffReport"] = report
    download = f"/api/reports/{report}/download?format=json"
    original = raw(download)
    document = json.loads(original)
    assert document["displayEvidenceOrigin"] == "SNAPSHOT"
    detail = admin.request(f"/api/reports/{report}")
    assert detail["sourceMetadataOrigin"] == "SNAPSHOT" and detail["reviewAvailable"]
    file = next(f for f in admin.request(f"/api/reports/{report}/source-files?keyword=large.c")["items"] if f["kind"] == "FILE")
    file_key = file["fileKey"]
    url = f"/api/reports/{report}/source-files/{file_key}"
    first = admin.request(url + "/context?startLine=1")
    assert len(first["lines"]) == 500
    assert admin.request(url + "/context?startLine=501")["lines"][0]["lineNumber"] == 501
    admin.request(url + "/search?keyword=handoff-needle")  # 预热
    measurements = []
    for _ in range(5):
        start = time.perf_counter()
        search = admin.request(url + "/search?keyword=handoff-needle")
        measurements.append((time.perf_counter() - start) * 1000)
    assert [m["lineNumber"] for m in search["matches"]] == [2, 501, 49999]
    assert not search["truncated"] and search["returnedCount"] == 3
    assert admin.request(url + "/search?keyword=handoff-needle&limit=2")["truncated"]
    assert admin.request(url + "/search?keyword=not-present")["available"]
    for query in ("?keyword=x&limit=201", "?keyword=x&limit=no", "?keyword=x&caseSensitive=nope", "?keyword=%0A", "?keyword="):
        assert admin.request(url + "/search" + query, expected=400)["code"] == "REPORT_REQUEST_INVALID"
    times, matched = [], []
    for start_line in range(1, 50001, 500):
        start = time.perf_counter()
        segment = admin.request(url + f"/context?startLine={start_line}")
        times.append((time.perf_counter() - start) * 1000)
        matched.extend(line["lineNumber"] for line in segment["lines"] if "handoff-needle" in line["text"])
    assert matched == [2, 501, 49999]
    results["performance"] = {"bytes": len(large.encode()), "lines": 50000, "fullSegmentRequests": len(times),
            "fullSegmentMs": sum(times), "firstNineSegmentMs": sum(times[:9]), "nineMedianMs": statistics.median(times[:9]),
            "searchRequests": 1, "searchMedianMs": statistics.median(measurements), "matches": matched}
    listing = admin.request(f"/api/reports/{report}/review-items")
    manual = {"expectedVersion": listing["draftVersion"], "fileKey": file_key, "sourceContentHash": first["contentHash"],
              "range": {"startLine": 500, "startColumn": 1, "endLine": 501, "endColumn": 10},
              "title": "跨段人工问题", "titleEn": "Cross-page manual finding", "description": "manual-only-text",
              "descriptionEn": "Manual evidence", "repairReason": "修复理由", "repairReasonEn": "Repair reason"}
    added = admin.request(f"/api/reports/{report}/review-items", manual, headers=key())
    assert admin.request(f"/api/reports/{report}/review-items?keyword=MANUAL-ONLY-TEXT")["total"] == 1
    listing = admin.request(f"/api/reports/{report}/review-items?pageSize=200")
    changes = [{"id": row["id"], "action": "INCLUDE"} for row in listing["items"] if row["reviewState"] == "PENDING_REVIEW"]
    assert changes
    admin.request(f"/api/reports/{report}/review-items", {"expectedVersion": listing["draftVersion"], "items": changes}, method="PATCH", headers=key())
    listing = admin.request(f"/api/reports/{report}/review-items")
    for action in ("EXCLUDE", "RESTORE"):
        item = listing["items"][0]
        admin.request(f"/api/reports/{report}/review-items", {"expectedVersion": listing["draftVersion"],
                "items": [{"id": item["id"], "action": action, "reason": "人工检查", "reasonEn": "Manual decision"}]}, method="PATCH", headers=key())
        listing = admin.request(f"/api/reports/{report}/review-items")
    confirmation_body, confirm_key = {"expectedVersion": listing["draftVersion"]}, key()
    confirmation = admin.request(f"/api/reports/{report}/fix-list-confirmations", confirmation_body, headers=confirm_key)
    assert admin.request(f"/api/reports/{report}/fix-list-confirmations", confirmation_body, headers=confirm_key)["confirmationId"] == confirmation["confirmationId"]
    confirmed = admin.request(download + "&confirmationId=" + confirmation["confirmationId"])
    assert confirmed["displayEvidence"] == document["displayEvidence"]
    current = generate(project)
    inherited = admin.request(f"/api/reports/{current}/review-items")
    assert inherited["draftSummary"]["pendingReviewCount"] == 0
    assert any(i["origin"] == "MANUAL" for i in inherited["items"])
    assert raw(download) == original
    assert not admin.request(f"/api/reports/{report}/review-items")["writable"]
    admin.request(f"/api/reports/{report}/fix-list-confirmations", confirmation_body, headers=key(), expected=409)
    results["main"] = {"projectId": project, "reportId": report, "currentReportId": current, "fileKey": file_key,
                       "originalSha256": hashlib.sha256(original).hexdigest(), "status": "PASS"}
elif phase == "missing":
    project = state["projectId"]
    missing = "22222222-2222-4222-8222-222222222222"
    history = admin.request(f"/api/projects/{project}/reports/evaluation")
    assert any(row["id"] == missing and not row["reviewAvailable"] for row in history["items"])
    detail = admin.request(f"/api/reports/{missing}")
    assert detail["review"] is None and detail["reviewUnavailableReasonCode"] == "REPORT_REVIEW_UNAVAILABLE"
    for path in ("review-items", "source-files"):
        assert admin.request(f"/api/reports/{missing}/" + path, expected=409)["code"] == "REPORT_REVIEW_UNAVAILABLE"
    assert admin.request(f"/api/reports/{missing}/fix-list-confirmations", {"expectedVersion": 0}, headers=key(), expected=409)["code"] == "REPORT_REVIEW_UNAVAILABLE"
    results["missingRun"] = "PASS"
elif phase == "ai-setup":
    runtimes = admin.request("/api/me/agent-runtimes")
    runtimes = runtimes.get("items", []) if isinstance(runtimes, dict) else runtimes
    runtime = next(r for r in runtimes if r["agentType"] == "CODEX")
    profile = admin.request("/api/me/model-profiles", {"name": "handoff-local-fixture-" + uuid.uuid4().hex[:8], "enabled": True,
            "modelProviderType": "OPENAI_COMPATIBLE", "baseUrl": "http://127.0.0.1:" + sys.argv[2] + "/admin/v1",
            "model": "fixture-model", "wireApi": "RESPONSES", "apiKey": "fixture-admin", "timeoutSeconds": 30, "maxRuntimeSeconds": 60})
    admin.request("/api/me/model-stage-assignments", {"assignments": [{"stage": "REPORT_AI_REVIEW",
            "modelProfileId": profile["id"], "agentRuntimeId": runtime["id"]}]}, method="PATCH")
    state["aiProfile"] = profile["id"]
elif phase == "ai":
    project = state["projectId"]
    ready = admin.request(f"/api/projects/{project}/reports/evaluation/readiness")
    assert ready["aiConfigurationReady"] and ready["aiExecutionCapability"]["status"] == "AVAILABLE", ready["aiExecutionCapability"]
    report = generate(project, "AI_ENHANCED")
    document = admin.request(f"/api/reports/{report}/download?format=json")
    assert document["aiSummary"]["validatedItems"] > 0
    assert len(document["aiReviews"]) == document["aiSummary"]["validatedItems"]
    results["ai"] = {"reportId": report, "summary": document["aiSummary"], "status": "PASS"}
elif phase == "permissions":
    clients = {}
    for alias in ("a", "b"):
        username = "handoff-" + alias + "-" + uuid.uuid4().hex[:8]
        user = admin.request("/api/admin/users", {"username": username, "email": username + "@example.invalid"})
        password = "T!" + secrets.token_hex(6)
        admin.request("/api/admin/users/" + user["id"] + "/reset-password", {"newPassword": password, "mustChangePassword": False})
        clients[alias] = module.Client("handoff-" + alias)
        clients[alias].request("/api/auth/login", {"username": username, "password": password})
    owner, other = clients["a"], clients["b"]
    project = owner.request("/api/projects", {"name": "报告权限空清单", "sourceArch": "x86", "targetArch": "riscv64"})["id"]
    owner.archive(project, {"empty.c": "int main(void){return 0;}\n"})
    scan = owner.request(f"/api/projects/{project}/kb-scans", {})
    assert owner.task(scan["id"])["status"] == "SUCCEEDED"
    ready = owner.request(f"/api/projects/{project}/reports/evaluation/readiness")
    assert not ready["aiConfigurationReady"]  # admin 的绑定不属于普通用户
    request = {"mode": "STANDARD", "sourceRevisionId": ready["sourceRevisionId"], "selectionToken": ready["selectionToken"]}
    task = owner.request(f"/api/projects/{project}/reports/evaluation", request, headers=key())
    assert owner.task(task["id"])["status"] == "SUCCEEDED"
    report = task["reportId"]
    listing = owner.request(f"/api/reports/{report}/review-items")
    assert listing["total"] == 0
    confirmation = owner.request(f"/api/reports/{report}/fix-list-confirmations", {"expectedVersion": listing["draftVersion"]}, headers=key())
    assert confirmation["confirmationState"] == "CONFIRMED_EMPTY"
    file_key = owner.request(f"/api/reports/{report}/source-files")["items"][0]["fileKey"]
    for path in (f"/api/reports/{report}", f"/api/reports/{report}/review-items", f"/api/reports/{report}/source-files/{file_key}/search?keyword=main",
                 f"/api/reports/{report}/download?format=json", f"/api/projects/{project}/reports/evaluation/readiness"):
        other.request(path, expected=404)
        admin.request(path)
    other.request(f"/api/reports/{report}/fix-list-confirmations", {"expectedVersion": listing["draftVersion"]}, headers=key(), expected=404)
    for client in (owner, other):
        client.request("/api/admin/users", expected=403)
        client.request("/api/reports/22222222-2222-4222-8222-222222222222", expected=404)
    results["permissionsAndEmptyConfirmation"] = {"status": "PASS", "projectId": project, "reportId": report}
elif phase == "queue":
    project = state["projectId"]
    task = admin.request(f"/api/projects/{project}/reports/evaluation", body(project), headers=key())
    state["recoveryTask"] = task["id"]
    state["recoveryReport"] = task["reportId"]
    print(json.dumps({"taskId": task["id"], "reportId": task["reportId"]}), flush=True)
elif phase == "recovered":
    done = admin.task(state["recoveryTask"])
    assert done["status"] == "FAILED" and done["errorCode"] == "TASK_DELIVERY_EXHAUSTED", done.get("errorCode")
    results["recovery"] = {"taskId": done["id"], "reportId": state["recoveryReport"], "status": "PASS"}
else:
    raise SystemExit("未知验收阶段")
state_path.write_text(json.dumps(state))
results_path.write_text(json.dumps(results, ensure_ascii=False, indent=2))
print(json.dumps({"phase": phase, "status": "PASS"}, ensure_ascii=False), flush=True)
