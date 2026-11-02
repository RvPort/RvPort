"""固定 2.94.0 的受控 EOF/内容类型补丁；先校验全部文件再写入。"""
import hashlib
import json
from pathlib import Path
import sys

BEFORE = '005aa9fbd04a5ccfd2ab868753a577be768cd72bc95782c90a8c268c0ce9863b5c5ae31587dd602f23213bcfa39b70204799a19185c2862f22aa8d42223bca45'
AFTER = '356cd559bf894d872cbcdc613935cfcf7b6c2bad60f66058b3f95256fe4e3cf2ea78b85789389d682f6920ce2c1b784dcff95d9c2db2ef0f1c3878cb1a13bd56'
OLD = b'if (!sawFinish && !sawUserFacingOutput) {'
NEW = b'if (!sawFinish) {'
CONTENT_BEFORE = '9512cbe2d60cc39b9b151136abc7c771c5d2ce4174c24af00d53c24855b9ef28a55c4d6420731352fb9443d78ff07fd1930a1b306ef260f84da876f2b5608d43'
CONTENT_AFTER = '1d4a12f53253adaa4bdf5109f2548801fd0b020ab86f8a38cb21f5bf1fff38d9e9983fc494b5cdc060e24f1345ed3a342dc7b9055344ee6cffcecb37a0b9b152'
CONTENT_OLD = b'  if (!Array.isArray(content)) return [];'
CONTENT_NEW = b'''  if (content === undefined || content === null) return [];
  if (!Array.isArray(content)) return [{
    type: "error", status: 502, errorType: "upstream_error",
    message: "invalid upstream content type", ...(usage ? { usage } : {}),
  }];'''
FINISH_STREAM_OLD = b'        if (typeof choice.finish_reason === "string" && choice.finish_reason) finishReason = choice.finish_reason;'
FINISH_STREAM_NEW = b'''        if (choice.finish_reason != null && !["stop", "length", "tool_calls", "content_filter", "function_call"].includes(choice.finish_reason)) {
          return yield* terminateWithError({ type: "error", status: 502, errorType: "upstream_error", message: "unknown upstream finish_reason" });
        }
        if (typeof choice.finish_reason === "string" && choice.finish_reason) finishReason = choice.finish_reason;'''
FINISH_JSON_OLD = b'        if (choice.finish_reason === "error") return [upstreamErrorEvent(choice.error, usage)];'
FINISH_JSON_NEW = b'''        if (choice.finish_reason === "error") return [upstreamErrorEvent(choice.error, usage)];
        if (!["stop", "length", "tool_calls", "content_filter", "function_call"].includes(choice.finish_reason as string)) {
          return [{ type: "error", status: 502, errorType: "upstream_error", message: "unknown upstream finish_reason", ...(usage ? { usage } : {}) }];
        }'''
FINISH_AFTER = '5d9c30cf9dcac96cc78c02589f7d1c5ffa67e01ac414c57661e64fa1dd7e56ea7dadddf4eae4d70cadc3491fcad6828652452e063c12431e72eab1bab771a60c'
USAGE_BEFORE = '69d3a4de549de3ffed434a91426d8ac0b5518b650665051a4883ca68dbc738369bb7ed829589f6da8577d140c5510741a9fcb63e43f31b8e256789816659a6d8'
USAGE_AFTER = 'dbd7e8754d3f817f799f28aae0c0e45ee93f737d9257a42d3057d49fe2b41b84b211cf3ef7bb72d76e0ea2e0b9cabd2485e17f1e1629a49ada369ae2c6955d69'
USAGE_OLD = b'''  if (!usage) {
    return {
      input_tokens: 0,
      output_tokens: 0,
      total_tokens: 0,
      input_tokens_details: { cached_tokens: 0 },
      output_tokens_details: { reasoning_tokens: 0 },
    };
  }'''
USAGE_NEW = b'  if (!usage) return null;'
PATCHES = (
    ('strict-text-eof-and-finish-reason', 'src/adapters/openai-chat.ts', BEFORE, FINISH_AFTER,
     (OLD, FINISH_STREAM_OLD, FINISH_JSON_OLD), (NEW, FINISH_STREAM_NEW, FINISH_JSON_NEW)),
    ('strict-content-type', 'src/adapters/openai-chat/response-events.ts',
     CONTENT_BEFORE, CONTENT_AFTER, CONTENT_OLD, CONTENT_NEW),
    ('preserve-missing-usage', 'src/bridge/internal.ts', USAGE_BEFORE, USAGE_AFTER,
     (b'export function responsesUsage(usage: OcxUsage | undefined): Record<string, unknown> {', USAGE_OLD,
      b'// input_tokens_details / output_tokens_details are ALWAYS emitted (zero defaults):'),
     (b'export function responsesUsage(usage: OcxUsage | undefined): Record<string, unknown> | null {', USAGE_NEW,
      b'// When upstream usage exists, token-detail objects retain the strict-client defaults:')),
)
BUN_SHA256 = '10702245766b225e70bbf6235fd6b47214fd94b4760d00ffaed8ab0df57ac235'


def verify_binary(root, expected=BUN_SHA256):
    path = Path(root) / 'node_modules/bun/bin/bun.exe'
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    if digest != expected:
        raise ValueError('Bun 安装脚本产物哈希错误')
    return digest


def patch_bytes(source, before=BEFORE, after=AFTER, old=OLD, new=NEW):
    digest = hashlib.sha512(source).hexdigest()
    if digest == after:
        return source
    originals = (old,) if isinstance(old, bytes) else old
    replacements = (new,) if isinstance(new, bytes) else new
    if digest != before or len(originals) != len(replacements) or any(source.count(item) != 1 for item in originals):
        raise ValueError('OpenCodex 补丁原文哈希或匹配数量错误')
    patched = source
    for original, replacement in zip(originals, replacements):
        patched = patched.replace(original, replacement)
    if hashlib.sha512(patched).hexdigest() != after:
        raise ValueError('OpenCodex 补丁结果哈希错误')
    return patched


def apply(root):
    root = Path(root) / 'node_modules/@bitkyc08/opencodex'
    if json.loads((root / 'package.json').read_text())['version'] != '2.94.0':
        raise ValueError('OpenCodex 版本不匹配')
    pending, evidence = [], []
    for name, relative, before, after, old, new in PATCHES:
        path = root / relative
        pending.append((path, patch_bytes(path.read_bytes(), before, after, old, new)))
        evidence.append({'patch': name, 'file': relative, 'before_sha512': before, 'after_sha512': after})
    for path, content in pending:
        path.write_bytes(content)
    return {'package': '@bitkyc08/opencodex', 'version': '2.94.0', 'patches': evidence}


if __name__ == '__main__':
    print(json.dumps({**apply(sys.argv[1]), 'bun_sha256': verify_binary(sys.argv[1])}, indent=2))
