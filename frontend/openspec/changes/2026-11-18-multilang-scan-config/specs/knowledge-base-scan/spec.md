## ADDED Requirements

### Requirement: 扫描配置 SHALL 支持多语言与源架构选择

扫描配置表单 SHALL 以多选方式呈现语言选项（C/C++、Rust、Go、Java、Python/Cython、汇编，可组合），并以单选呈现源架构（x86、ARM64，缺省 x86）。提交时前端 SHALL 将语言标签数组映射为后端合同字符串（`'C/C++'` 映射为 `'c,cpp'`，其余原值，逗号连接）并透传 `sourceArch`；至少选择一个语言为必填。缺省选择（仅 C/C++ + x86）的提交载荷 MUST 与改动前单选表单的载荷一致。

#### Scenario: 混合语言提交

- **WHEN** 用户选择 C/C++ 与 Rust 并确认扫描
- **THEN** 请求体 SHALL 携带 `language: "c,cpp,rust"` 与所选 `sourceArch`

#### Scenario: 缺省提交保持不变

- **WHEN** 用户保持缺省（仅 C/C++、x86）提交
- **THEN** 请求载荷 SHALL 与旧版单选表单完全一致（`language: "c,cpp"`、`sourceArch: "x86"`）

#### Scenario: 空选择阻止提交

- **WHEN** 语言多选为空
- **THEN** 表单校验 SHALL 阻止提交并提示选择扫描语言

#### Scenario: 草稿持久化

- **WHEN** 用户修改语言或源架构后离开配置视图再返回
- **THEN** 阶段草稿 SHALL 以 `language: string[]` 与 `sourceArch` 恢复用户上次选择
