# 验证记录（2026-11-14）

## 本机
- 契约测试：生产/装载/端到端全绿（203 计数、SHA256 钉死、再生成字节一致、
  单源单族、DEFINITIONS_BY_FAMILY 键集 {x86,arm64}）。
- 端到端 test_riscv_macro_scan.py：`#elif __riscv_xlen` 判 riscv 角色、
  negated unknown、族隔离负测。

## 容器（kb-scanner-test:local）
- 18 道门禁 + 321 全量回归 exit=0（container-full7.log）。
- 目标族预检的单元覆盖补入 evidence-graph / architecture-enum 两个
  inventory 套件（test_target_family_role.py）。
