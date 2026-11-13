"""仅在本地 Docker 中执行真实 CLI + 确定性模拟模型的协议验收。"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import shlex
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class MockModel(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def expected_api_key(self):
        return "local-dummy-key-two" if self.path.startswith("/two/") else "local-dummy-key"

    def do_GET(self):
        if self.path != '/__metrics':
            self.send_error(404)
            return
        payload = json.dumps({'requests': len(self.server.requests)}).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_POST(self):
        if self.path.startswith("/failure/"):
            scenario = self.path.split("/")[2]
            self.rfile.read(int(self.headers["Content-Length"]))
            self.server.requests.append({'failure': scenario})
            if self.server.failure_marker:
                self.server.failure_marker.touch()
            if scenario.isdigit():
                self.send_response(int(scenario)); self.end_headers()
                self.wfile.write(b"PRIVATE upstream error local-dummy-key"); return
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.end_headers()
            if scenario == "malformed":
                self.wfile.write(b"data: {bad}\n\n"); return
            if scenario == "unsupported":
                self.event({"choices": [{"index": 0, "delta": {"reasoning_content": "not-supported"}, "finish_reason": "stop"}]})
                self.wfile.write(b"data: [DONE]\n\n"); return
            if scenario in ("unknown-finish", "invalid-content", "truncated-json"):
                content = 42 if scenario == "invalid-content" else '{"status":"ok"}'
                self.event({"choices": [{"index": 0, "delta": {"content": content}, "finish_reason": None}]})
                if scenario != "truncated-json":
                    self.event({"choices": [{"index": 0, "delta": {}, "finish_reason":
                                             "unexpected_reason" if scenario == "unknown-finish" else "stop"}]})
                    self.wfile.write(b"data: [DONE]\n\n")
                return
            self.event({"choices": [{"index": 0, "delta": {"content": "partial"}, "finish_reason": None}]})
            if scenario in ("slow", "cancel"):
                time.sleep(15)
            return  # 断流以及缺少 DONE 均不伪装为完成。
        request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        self.server.requests.append(request)
        expected_key = self.expected_api_key()
        assert self.headers.get("Authorization") == "Bearer " + expected_key
        native = self.path.endswith("/responses")
        history = request.get("input", []) if native else request["messages"]
        if isinstance(history, str):
            history = [{"role": "user", "content": history}]
        user_messages = [json.dumps(item.get("content", ""), ensure_ascii=False)
                         for item in history if item.get("role") == "user"]
        prompt = next((text for text in reversed(user_messages)
                       if any(marker in text for marker in ("UTF-8 中文传输校验", "custom-output.txt",
                                                            "results-0001.jsonl", "comparison-result.json"))), "")
        business = "kb" if "results-0001.jsonl" in prompt else "comparison" if "comparison-result.json" in prompt else None
        mode = "workspace-write" if "probe-output.json" in prompt else "workspace-read" if "probe-input.json" in prompt else "text"
        custom = "custom-output.txt" in prompt
        if not request.get("tools"):
            mode, custom = "text", False  # 标题/摘要等辅助请求没有工具，不属于通用 probe。
        results = [item for item in history if item.get("role") == "tool" or item.get("type", "").endswith("call_output")]
        if results and not custom:
            assert "rvport-probe-ok" in json.dumps(results, ensure_ascii=False), "真实工具未返回 fixture marker"
        tool = None
        if (mode != "text" or custom or business) and not results:
            names = [item.get("name", item.get("function", {}).get("name")) for item in request.get("tools", [])]
            command = "cat probe-input.json"
            if business == "kb":
                script = ('import json,pathlib; row=json.loads(pathlib.Path("input/findings-0001.jsonl").read_text()); '
                          'row={"findingId":row["findingId"]}; '
                          'row.update(aiSummary="rvport-probe-ok",aiSuggestion="review",confidence=0.9,actionType="REVIEW"); '
                          'pathlib.Path("output/results-0001.jsonl").write_text(json.dumps(row)+"\\n"); print("rvport-probe-ok")')
                command = 'python3 -c ' + shlex.quote(script)
            elif business == "comparison":
                stats = {'status':'SUCCEEDED','exitCode':0,'totalTests':1,'executedTests':1,'passedTests':1,
                         'failedTests':0,'skippedTests':0,'failedItems':[],'extractionConfidence':.9,'testCountBasis':'local fixture'}
                result = {'overallStatus':'DIFFERENT_BLOCKING','riskLevel':'HIGH','confidence':.9,'summary':'local difference',
                          'recommendation':'inspect target','sourceStats':stats,'targetStats':stats,
                          'findings':[{'differenceType':'STDERR','severity':'HIGH','confidence':.9,'title':'local difference',
                          'description':'target differs','sourceSummary':'source','targetSummary':'target','recommendation':'inspect'}]}
                command = ('cat comparison-input.json && printf %s ' + shlex.quote(json.dumps(result))
                           + ' > comparison-result.json && echo rvport-probe-ok')
            if mode == "workspace-write":
                command += " && printf '%s' '{\"status\":\"ok\",\"marker\":\"rvport-probe-ok\"}' > probe-output.json"
            if custom:
                name = "apply_patch"
                arguments = {"input": "*** Begin Patch\n*** Add File: custom-output.txt\n+rvport-probe-ok\n*** End Patch\n"}
            elif "exec_command" in names:
                name = "exec_command"
                arguments = {"cmd": command, "yield_time_ms": 1000, "max_output_tokens": 1024}
            else:
                name = "bash"
                arguments = {"command": command, "description": "Read local fixture and verify marker"}
            assert name in names, (name, names)
            tool = {"id": "call_local", "type": "function", "function": {"name": name, "arguments": json.dumps(arguments, ensure_ascii=False)}}
        agent = next((candidate for candidate in ("DEEPSEEK_HARNESS", "OPENCODE", "CODEX") if candidate in prompt), "CODEX")
        final = {"status": "ok", "agent": agent, "mode": mode}
        if mode == "workspace-read":
            final["marker"] = "rvport-probe-ok"
        text = json.dumps(final, ensure_ascii=False)
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.end_headers()
        if native:
            if tool:
                item = {"id": "fc_local", "type": "function_call", "call_id": tool["id"], "status": "completed", **tool["function"]}
            else:
                item = {"id": "msg_local", "type": "message", "role": "assistant", "status": "completed",
                        "content": [{"type": "output_text", "text": text, "annotations": []}]}
            response = {"id": "resp_local", "object": "response", "created_at": 1, "status": "completed", "output": [item],
                        "usage": {"input_tokens": 1, "output_tokens": 1, "total_tokens": 2}}
            self.event({"type": "response.created", "response": {**response, "status": "in_progress", "output": []}})
            self.event({"type": "response.output_item.added", "output_index": 0, "item": {**item, "status": "in_progress"}})
            if not tool:
                self.event({"type": "response.output_text.delta", "output_index": 0, "item_id": item["id"], "content_index": 0, "delta": text})
            self.event({"type": "response.output_item.done", "output_index": 0, "item": item})
            self.event({"type": "response.completed", "response": response})
        else:
            delta = {"role": "assistant", "tool_calls": [{"index": 0, **tool}]} if tool else {"role": "assistant", "content": text}
            common = {"id": "chat_local", "object": "chat.completion.chunk", "created": 1, "model": request["model"]}
            self.event({**common, "choices": [{"index": 0, "delta": delta, "finish_reason": None}]})
            terminal = {**common, "choices": [{"index": 0, "delta": {}, "finish_reason": "tool_calls" if tool else "stop"}]}
            if not self.server.omit_usage:
                terminal["usage"] = {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}
            self.event(terminal)
            self.wfile.write(b"data: [DONE]\n\n")
            self.wfile.flush()

    def event(self, value):
        self.wfile.write(("data: " + json.dumps(value, ensure_ascii=False) + "\n\n").encode())
        self.wfile.flush()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cli-root")
    parser.add_argument("--java-runtime")
    parser.add_argument("--report", required=True)
    parser.add_argument("--catalog")
    parser.add_argument("--serve", action="store_true")
    parser.add_argument("--failures", action="store_true")
    parser.add_argument("--failures-only", action="store_true")
    parser.add_argument("--omit-usage", action="store_true")
    parser.add_argument("--custom-only", action="store_true")
    parser.add_argument("--wire", choices=["RESPONSES", "CHAT"])
    parser.add_argument("--business-only", action="store_true")
    parser.add_argument("--concurrent-only", action="store_true")
    parser.add_argument("--agent", choices=["CODEX", "DEEPSEEK_HARNESS", "OPENCODE"])
    args = parser.parse_args()
    report = Path(args.report)
    report.mkdir(parents=True, exist_ok=True)
    server = ThreadingHTTPServer(("0.0.0.0", 8089) if args.serve else ("127.0.0.1", 0), MockModel)
    server.requests = []
    server.omit_usage = args.omit_usage
    server.failure_marker = None
    if args.serve:
        server.serve_forever()
        return
    assert args.cli_root and args.java_runtime
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    env = {**os.environ, "DSH_HOME": str(report / "dsh-home"), "CODEX_HOME": str(report / "codex-home"),
           "DSH_TELEMETRY_MODE": "DISABLED", "OPENCODE_DISABLE_AUTOUPDATE": "true"}
    Path(env["CODEX_HOME"]).mkdir(exist_ok=True)
    scenarios = [("CODEX", "RESPONSES"), ("CODEX", "CHAT"), ("DEEPSEEK_HARNESS", "CHAT"), ("OPENCODE", "CHAT")]
    if args.agent:
        scenarios = [scenario for scenario in scenarios if scenario[0] == args.agent]
    if args.wire:
        scenarios = [scenario for scenario in scenarios if scenario[1] == args.wire]
    if args.custom_only:
        assert args.catalog
        scenarios = []
    if args.failures_only:
        scenarios = []
    if args.business_only or args.concurrent_only:
        scenarios = []
    outcomes = []
    try:
        if args.business_only or args.concurrent_only:
            scenario = 'BUSINESS' if args.business_only else 'CONCURRENT'
            command = ["java", "-cp", f"{args.java_runtime}/classes:{args.java_runtime}/test-classes:{args.java_runtime}/lib/*",
                       "cn.rvport.backend.agentruntime.AgentCliAcceptanceMain", args.cli_root, "CODEX",
                       f"http://127.0.0.1:{server.server_port}/v1", "CHAT", "--business" if args.business_only else "--concurrent"]
            result = subprocess.run(command, env=env, capture_output=True, text=True, timeout=130)
            (report / (scenario + '.log')).write_text(result.stdout + result.stderr)
            outcomes.append({'scenario':scenario,'exitCode':result.returncode,'requests':len(server.requests)})
            print(outcomes[-1], flush=True)
        for agent, wire in scenarios:
            server.agent = agent
            server.requests = []
            name = agent + "-" + wire
            command = ["java", "-cp", f"{args.java_runtime}/classes:{args.java_runtime}/test-classes:{args.java_runtime}/lib/*",
                       "cn.rvport.backend.agentruntime.AgentCliAcceptanceMain", args.cli_root, agent,
                       f"http://127.0.0.1:{server.server_port}/v1", wire]
            result = subprocess.run(command, env=env, capture_output=True, text=True, timeout=70)
            (report / (name + ".log")).write_text(result.stdout + result.stderr)
            (report / (name + "-requests.json")).write_text(json.dumps(server.requests, ensure_ascii=False, indent=2))
            outcomes.append({"scenario": name, "exitCode": result.returncode, "requests": len(server.requests)})
            print(outcomes[-1], flush=True)
        if args.failures or args.failures_only:
            for scenario in ("401", "403", "429", "500", "slow", "disconnected", "malformed", "unsupported",
                             "unknown-finish", "invalid-content", "truncated-json", "cancel"):
                server.requests = []
                server.failure_marker = report / ('started-' + scenario)
                server.failure_marker.unlink(missing_ok=True)
                command = ["java", "-cp", f"{args.java_runtime}/classes:{args.java_runtime}/test-classes:{args.java_runtime}/lib/*",
                           "cn.rvport.backend.agentruntime.AgentCliAcceptanceMain", args.cli_root, "CODEX",
                           f"http://127.0.0.1:{server.server_port}/failure/{scenario}", "CHAT",
                           "--failure-cancel" if scenario == "cancel" else "--failure", str(server.failure_marker)]
                result = subprocess.run(command, env=env, capture_output=True, text=True,
                                        timeout=70 if scenario == "unsupported" else 22)
                (report / ("FAILURE-" + scenario + ".log")).write_text(result.stdout + result.stderr)
                outcomes.append({"scenario": "FAILURE-" + scenario, "exitCode": result.returncode,
                                 "upstreamReached": bool(server.requests)})
                print(outcomes[-1], flush=True)
        if args.catalog:
            server.agent = "CODEX"
            server.requests = []
            command = ["java", "-cp", f"{args.java_runtime}/classes:{args.java_runtime}/test-classes:{args.java_runtime}/lib/*",
                       "cn.rvport.backend.agentruntime.AgentCliAcceptanceMain", args.cli_root, "CODEX",
                       f"http://127.0.0.1:{server.server_port}/v1", "CHAT", args.catalog]
            result = subprocess.run(command, env=env, capture_output=True, text=True, timeout=40)
            (report / "CODEX-CUSTOM.log").write_text(result.stdout + result.stderr)
            outcomes.append({"scenario": "CODEX-CUSTOM", "exitCode": result.returncode, "requests": len(server.requests)})
            print(outcomes[-1], flush=True)
    finally:
        server.shutdown()
        server.server_close()
        (report / "outcomes.json").write_text(json.dumps(outcomes, indent=2))
    if any(outcome["exitCode"] != 0 or not outcome.get('upstreamReached', True) for outcome in outcomes):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
