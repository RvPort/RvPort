"""隔离 Compose 专用。先停 worker 执行 enqueue，重启 API 并启动 worker 后执行 resume。"""
import http.cookiejar
import json
import os
import re
import sys
import time
import uuid
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, build_opener, HTTPCookieProcessor

if not re.fullmatch(r"rvport-review-final-[0-9]+", os.environ.get("RVPORT_ACCEPTANCE_PROJECT", "")):
    raise SystemExit("需要独立本地验收实例")
client = build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
base = "http://backend:8080"


def request(path, method="GET", body=None, headers=None):
    hdr = dict(headers or {})
    if method != "GET":
        token = request("/api/auth/csrf")
        hdr[token["headerName"]] = token["token"]
        hdr["Content-Type"] = "application/json"
    req = Request(base + path, data=None if body is None else json.dumps(body).encode(), method=method, headers=hdr)
    with client.open(req, timeout=20) as response:
        return json.load(response)["data"]


def login():
    password = "LocalReview123!"
    try:
        me = request("/api/auth/login", "POST", {"username": "admin", "password": "admin123"})
    except HTTPError as error:
        if error.code != 401:
            raise
        me = request("/api/auth/login", "POST", {"username": "admin", "password": password})
    if me["user"]["mustChangePassword"]:
        request("/api/auth/change-password", "POST", {"currentPassword": "admin123", "newPassword": password})
        request("/api/auth/login", "POST", {"username": "admin", "password": password})


def event(project, cursor=None):
    route = "/api/projects/" + project + "/task-events"
    headers = {"Accept": "text/event-stream"}
    if cursor is None:
        route += "?afterEventId=0"
    else:
        headers["Last-Event-ID"] = str(cursor)
    with client.open(Request(base + route, headers=headers), timeout=20) as stream:
        for line in stream:
            if line.startswith(b"id:"):
                return int(line[3:].strip())
    raise AssertionError("事件流未返回持久游标")


def retired_routes(project, source):
    prefix = "/api/projects/" + project
    ref = str(uuid.uuid4())
    migration = prefix + "/source-revisions/" + source + "/migration-inputs"
    kb = "/api/kb-ai-extensions/" + ref
    routes = [("GET", prefix + "/kb-ai-extensions"), ("GET", kb), ("GET", kb + "/artifacts"),
              ("GET", kb + "/artifacts/" + ref + "/download"), ("POST", prefix + "/kb-scans/" + ref + "/ai-extensions"),
              ("GET", prefix + "/kb-scans/" + ref + "/ai-extension-readiness"), ("GET", migration),
              ("POST", migration + ":import"), ("POST", migration + ":auto-merge"),
              ("GET", "/api/migration-inputs/" + ref), ("PATCH", "/api/migration-inputs/" + ref)]
    for method, path in routes:
        try:
            request(path, method, None if method == "GET" else {})
        except HTTPError as exc:
            assert exc.code == 404, (path, exc.code, exc.read()[:200])
        else:
            raise AssertionError(path)


if __name__ == "__main__":
    phase, project, state_path = sys.argv[1:]
    project = str(uuid.UUID(project))
    root = "/api/projects/" + project + "/reports/evaluation"
    state = Path(state_path)
    login()
    if phase in ("enqueue", "race"):
        ready = request(root + "/readiness")
        tasks = "/api/projects/" + project + "/tasks"
        before = request(tasks)["total"]
        retired_routes(project, ready["sourceRevisionId"])
        assert request(tasks)["total"] == before
        body = {"sourceRevisionId": ready["sourceRevisionId"], "mode": "STANDARD", "selectionToken": ready["selectionToken"],
                "baseReportId": ready["regenerationBaseReportId"], "expectedDraftVersion": ready["regenerationBaseDraftVersion"]}
        key = {"Idempotency-Key": str(uuid.uuid4())}
        cursor = event(project)
        created = request(root, "POST", body, key)
        task = created["id"]
        assert request("/api/tasks/" + task)["status"] in ("PENDING", "QUEUED")
        state.write_text(json.dumps([cursor, task, created["reportId"], body, key]))
        if phase == "race":
            work = "/api/reports/" + ready["currentReport"]["reportId"]
            draft = request(work + "/review-items")
            ids = [row["id"] for row in draft["items"] if row["reviewState"] == "PENDING_REVIEW"]
            assert ids and len(ids) == draft["draftSummary"]["pendingReviewCount"]
            assert len(ids) > 1
            def post(path, method, body):
                return request(work + path, method, body, {"Idempotency-Key": str(uuid.uuid4())})

            changes = {"expectedVersion": draft["draftVersion"], "items": [{"id": item, "action": "INCLUDE"} for item in ids[:-1]]}
            updated = post("/review-items", "PATCH", changes)
            version = updated["draftSummary"]["version"]
            page = request(work + "/review-items?pageSize=1&sortBy=reviewState&sortOrder=desc")
            assert page["items"][0]["reviewState"] == "TO_FIX" and page["draftSummary"]["pendingReviewCount"] == 1
            try:
                post("/fix-list-confirmations", "POST", {"expectedVersion": version})
            except HTTPError as exc:
                assert exc.code == 409 and "REPORT_PENDING_REVIEW" in exc.read().decode()
            else:
                raise AssertionError("分页隐藏的待复核项未阻止确认")
            updated = post("/review-items", "PATCH", {"expectedVersion": version, "items": [{"id": ids[-1], "action": "INCLUDE"}]})
            nodes = request(work + "/source-files?keyword=unused.c")["items"]
            file = next(node for node in nodes if node["kind"] == "FILE" and node["findingCount"] == 0)
            context = request(work + "/source-files/" + file["fileKey"] + "/context?startLine=1&endLine=2")
            added = post("/review-items", "POST", {"expectedVersion": updated["draftSummary"]["version"], "fileKey": file["fileKey"],
                         "sourceContentHash": context["contentHash"], "range": {"startLine": 1, "startColumn": 1, "endLine": 2, "endColumn": 1},
                         "description": "核对选中的声明", "repairReason": "需要检查平台兼容性"})
            assert added["item"]["manualDetails"]["snippet"] == context["lines"][0]["text"] + chr(10)
            assert added["item"]["origin"] == "MANUAL" and added["item"]["reviewState"] == "TO_FIX"
            updated = added
            confirmed = request(work + "/fix-list-confirmations", "POST", {"expectedVersion": updated["draftSummary"]["version"]},
                                {"Idempotency-Key": str(uuid.uuid4())})
            assert confirmed["itemCount"] == len(ids) + 1
            print("B11_B12_HTTP_PASS: multiline manual selection and pending item on another page")
        print("B08_ENQUEUED: task persisted with worker stopped; eleven retired routes returned 404")
    elif phase in ("resume", "check-race"):
        cursor, task, report, body, key = json.loads(state.read_text())
        assert event(project, cursor) > cursor
        for _ in range(120):
            status = request("/api/tasks/" + task)["status"]
            if status in ("SUCCEEDED", "FAILED", "CANCELLED", "TIMEOUT"):
                break
            time.sleep(1)
        latest = request(root + "/latest")
        if phase == "check-race":
            assert status == "FAILED", status
            assert latest["currentReport"]["reportId"] == body["baseReportId"]
            assert latest["currentFixList"]["reasonCode"] == "CONFIRMED_WITH_ITEMS"
            assert latest["latestGeneration"]["reportId"] == report
            print("B15_LATE_PUBLICATION_REJECTED: worker retries preserved newer draft and immutable confirmation")
            sys.exit(0)
        assert status == "SUCCEEDED", status
        assert latest["currentReport"]["reportId"] == report
        assert latest["latestGeneration"]["taskId"] == task
        assert request(root, "POST", body, key)["id"] == task
        print("B08_RECOVERED: persisted task, SSE resume, idempotent replay and published report verified after restart")
    else:
        raise ValueError(phase)
