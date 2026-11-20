## Why

kb-scanner 已支持多语言（c/rust/go/java/python/asm，opt-in）与 x86/arm64 双源架构，前端扫描配置仍只有 C/C++ 单选语言，用户无法发起多语言或 ARM 源架构的扫描，新语言能力在产品界面不可达。

## What Changes

- 扫描语言选择从 C/C++ 单选改为**多选**：C/C++、Rust、Go、Java、Python/Cython、汇编（可组合，如 C/C++ + Rust 混合扫描）。
- 新增**源架构**下拉（x86 / ARM64），缺省 x86。
- 提交映射：语言标签数组按后端合同拼接（`'C/C++' → 'c,cpp'`，其余 label=value，逗号连接），`sourceArch` 透传；阶段草稿持久化结构同步为 `language: string[]` + `sourceArch`。

## Capabilities

### Modified Capabilities

- `knowledge-base-scan`：扫描配置表单的语言多选与源架构选择、提交映射与草稿持久化。

## Impact

- 代码：`ScanConfig.tsx`（多选 + 新下拉 + 帮助文案）、`api/project/knowledge-base.ts`（数组映射 + sourceArch）、`types/project/knowledge-base.ts` 与抽屉 `types.ts`（类型与初始值）、两处测试适配。
- 不改后端 API 形态（仍为逗号分隔字符串 + sourceArch 字符串）。
- 旧草稿中 string 形态 language 不再做兼容读取（阶段草稿随版本重置）。
