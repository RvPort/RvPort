"""本地 Compose worker 的临时模拟模型。仅绑定回环；记录已匹配的夹具别名，不记录请求或密钥。"""
import sys
from http.server import ThreadingHTTPServer
from pathlib import Path
from agent_runtime_acceptance import MockModel


class ReportModel(MockModel):
    def report_script(self):
        original = super().report_script()
        scenario_file = self.server.audit.parent / "scenario"
        if not scenario_file.exists():
            return original
        scenario = scenario_file.read_text()
        transforms = {
            "missing": "out=[]",
            "duplicate": "out=out+out",
            "unknown": 'out[0]["inputId"]="00000000-0000-4000-8000-000000000000"',
            "evidence": 'out[0]["evidenceRefs"]=["unavailable"]',
            "confirmed": 'out[0].update(verdict="CONFIRMED_ISSUE",reason="源码调用仍存在",reasonEn="Call remains in this revision")',
            "suspected": 'out[0].update(verdict="SUSPECTED_FALSE_POSITIVE",reason="路径可能未启用",reasonEn="Path may be inactive")',
        }
        if scenario == "timeout":
            return "import time; time.sleep(25)"
        if scenario == "invalid-json":
            return 'import pathlib; pathlib.Path("output/results.jsonl").write_text("{"); print("rvport-probe-ok")'
        return original.replace('pathlib.Path("output/results.jsonl").write_text',
                                transforms[scenario] + '; pathlib.Path("output/results.jsonl").write_text')

    def expected_api_key(self):
        alias = self.path.split("/")[1]
        if alias not in ("alice", "admin") or self.headers.get("Authorization") != "Bearer fixture-" + alias:
            raise ValueError("fixture identity mismatch")
        with self.server.audit.open("a") as output:
            print(alias, file=output)
        return "fixture-" + alias


if __name__ == "__main__":
    root = Path(sys.argv[1])
    with ThreadingHTTPServer(("127.0.0.1", 0), ReportModel) as server:
        server.audit = root / "aliases"
        server.audit.touch()
        server.requests, server.omit_usage, server.failure_marker = [], False, None
        (root / "port").write_text(str(server.server_port))
        server.serve_forever()
