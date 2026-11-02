// 本地 Docker/Bun 单元测试，直接调用固定包；不发起网络请求。
import { test, expect } from "bun:test";
const root = process.env.OPENCODEX_PACKAGE_ROOT;
if (!root) throw new Error("OPENCODEX_PACKAGE_ROOT_REQUIRED");
const { createOpenAIChatAdapter } = await import(root + "/src/adapters/openai-chat.ts");
const { chatContentEvents } = await import(root + "/src/adapters/openai-chat/response-events.ts");
const { createTranslatorBudget } = await import(root + "/src/lib/translator-budget.ts");
const { responsesUsage } = await import(root + "/src/bridge/internal.ts");

const provider = { adapter: "openai-chat", baseUrl: "http://fixture/v1", apiKey: "dummy", openaiChatEofTolerance: false };
function chunk(content, finish = null) {
  return "data: " + JSON.stringify({ choices: [{ index: 0, delta: { content }, finish_reason: finish }] }) + "\n\n";
}
async function parse(source) {
  const budget = createTranslatorBudget();
  try {
    const events = [];
    for await (const event of createOpenAIChatAdapter(provider).parseStream(
      new Response(source, { headers: { "content-type": "text/event-stream" } }), budget)) events.push(event);
    return events;
  } finally { budget.dispose(); }
}

test.each(["partial", "", undefined])("EOF without terminal fails: %p", async (content) => {
  const events = await parse(chunk(content));
  expect(events.some(event => event.type === "error")).toBe(true);
  expect(events.some(event => event.type === "done")).toBe(false);
});
test.each(["stop", "length"])("explicit terminal retains semantics: %s", async (reason) => {
  for (const ending of ["", "data: [DONE]\n\n"]) {
    const events = await parse(chunk("中文", reason) + ending);
    expect(events.some(event => event.type === "error")).toBe(false);
    expect(events.at(-1).type).toBe("done");
    expect(events.at(-1).stopReason).toBe(reason === "length" ? "max_tokens" : undefined);
  }
});
test.each([42, false, { text: "invalid" }])("invalid content fails for streaming and buffered paths: %p", async (content) => {
  for (const usage of [undefined, { inputTokens: 7, outputTokens: 3 }]) {
    const result = chatContentEvents(content, provider.baseUrl, usage);
    expect(result).toEqual([{ type: "error", status: 502, errorType: "upstream_error",
      message: "invalid upstream content type", ...(usage ? { usage } : {}) }]);
  }
  const events = await parse(chunk(content, "stop") + "data: [DONE]\n\n");
  expect(events.some(event => event.type === "error")).toBe(true);
  expect(events.some(event => event.type === "done")).toBe(false);
});
test("null, absent content, text and valid provider blocks keep their original behavior", () => {
  for (const content of [undefined, null, ""]) expect(chatContentEvents(content, provider.baseUrl)).toEqual([]);
  expect(chatContentEvents("中文", provider.baseUrl)).toEqual([{ type: "text_delta", text: "中文" }]);
  expect(chatContentEvents([{ type: "text", text: "valid" }], "https://api.mistral.ai/v1"))
    .toEqual([{ type: "text_delta", text: "valid" }]);
});
test("unknown usage remains unknown while real zero usage is preserved", () => {
  expect(responsesUsage(undefined)).toBeNull();
  expect(responsesUsage(null)).toBeNull();
  expect(responsesUsage({ inputTokens: 0, outputTokens: 0 })).toMatchObject({ input_tokens: 0, output_tokens: 0, total_tokens: 0 });
  expect(responsesUsage({ inputTokens: 7, outputTokens: 3 })).toMatchObject({ input_tokens: 7, output_tokens: 3, total_tokens: 10 });
});
test.each(["unexpected_reason", "", 42])("unknown finish reason rejects both paths: %p", async (reason) => {
  const stream = await parse(chunk("partial", reason)+"data: [DONE]\n\n");
  expect(stream.at(-1).type).toBe("error");
  expect(stream.some(event => event.type === "done")).toBe(false);
  const budget = createTranslatorBudget();
  try {
    const events = await createOpenAIChatAdapter(provider).parseResponse(new Response(JSON.stringify({
      choices: [{ message: { role: "assistant", content: "partial" }, finish_reason: reason }],
      usage: { prompt_tokens: 7, completion_tokens: 3, total_tokens: 10 }
    })), budget);
    expect(events).toHaveLength(1);
    expect(events[0]).toMatchObject({ type: "error", status: 502, message: "unknown upstream finish_reason" });
  } finally { budget.dispose(); }
});
test.each(["stop", "length", "content_filter", "tool_calls", "function_call"])("buffered valid reasons retain output: %s", async (reason) => {
  const budget = createTranslatorBudget();
  try {
    const events = await createOpenAIChatAdapter(provider).parseResponse(new Response(JSON.stringify({
      choices: [{ message: { role: "assistant", content: "中文" }, finish_reason: reason }]
    })), budget);
    expect(events.some(event => event.type === "error")).toBe(false);
    expect(events.at(-1).type).toBe("done");
  } finally { budget.dispose(); }
});

test.each([undefined, null])("buffered missing terminal reason fails without invented usage: %p", async (reason) => {
  const budget = createTranslatorBudget();
  try {
    const events = await createOpenAIChatAdapter(provider).parseResponse(new Response(JSON.stringify({
      choices: [{ message: { role: "assistant", content: "partial" }, finish_reason: reason }]
    })), budget);
    expect(events).toHaveLength(1);
    expect(events[0]).toMatchObject({ type: "error", status: 502, message: "unknown upstream finish_reason" });
    expect(events[0].usage).toBeUndefined();
  } finally { budget.dispose(); }
});
