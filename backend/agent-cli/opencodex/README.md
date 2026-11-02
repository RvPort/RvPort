# 受管 OpenCodex

仅供 worker 中 Codex CHAT 调用使用。固定 `@bitkyc08/opencodex@2.94.0` 与完整 npm lock，Bun 为 1.4.4。独立安装目录避免改变 DSH 的已验证依赖树；构建使用 npm ci，运行期禁用自动更新。不增加默认 Compose 服务。

来源：https://github.com/lidge-jun/opencodex 。MIT 许可证随 `LICENSE.opencodex` 保留。

## 已批准的受控修补

2026-10-30 用户批准 `patch_eof.py` 的单处条件修改：上游已输出文本但没有 finish_reason / [DONE] 时也必须失败，不能合成 completed。正常结束、明确长度截断及已测错误不变。补丁脚本先校验固定发布版本与 SHA-512，再修改并校验结果；内容偏差立即使构建失败。重复执行仅接受精确的修补后内容。

协议仍由 OpenCodex 转换；不维护另一份转换器。更新依赖时必须重新验证 EOF 行为、哈希和完整协议能力，并判断能否移除此补丁。补丁应用脚本作为自有可执行代码纳入单元测试和覆盖率，修改分支通过真实包协议对照回归验证。

2026-11-02 用户另行批准内容类型修补：保留 null/缺省值和正常字符串，将非法数值、布尔值或对象型 content 转为上游错误，避免丢弃内容后误报 completed。修补固定 `src/adapters/openai-chat/response-events.ts` 的完整文件 SHA-512。

同日用户明确授权补齐缺失 usage 和未知 finish_reason 的处理：`src/bridge/internal.ts` 在上游没有用量时返回 `null`，避免编造零用量；真实零值及真实计数保持不变。`src/adapters/openai-chat.ts` 的流式和非流式路径拒绝未知结束原因，保留正常 stop、工具调用、长度截断和内容过滤的既有语义。EOF 和结束原因修改作为同一文件的原子补丁验证。

所有目标文件预校验通过后才写入，任一内容漂移使构建失败；`applied-patch.json` 记录三个文件、四项行为修补的路径及前后 SHA-512。仅接受原始固定包或完整修补后的精确内容；升级已有中间版本时重新执行冻结安装，不把中间补丁当作新的原始基线。

`src/test/fixtures/opencodex_patch.test.js` 直接测试真实固定包的流式/非流式解析与 usage 构造，`opencodex_protocol_acceptance.py` 通过真实代理和本地模拟上游验证终态、工具关联、UTF-8 和用量。Bun 当前 LCOV 不提供分支计数，不能把函数覆盖率当作分支覆盖率；新增条件的两侧另有明确断言。自有 Python 安装脚本仍执行完整行/分支 90% 门禁。
