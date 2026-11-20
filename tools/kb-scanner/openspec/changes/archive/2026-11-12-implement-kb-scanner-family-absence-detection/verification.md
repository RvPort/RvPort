# 验证记录（2026-11-14）

## 本机
- 误报四件套回归（alarm/harmony/ppc64le/exclude-path）全绿；
- 升格链组件用例：CMake 选中 foo_x86.cpp 且无 riscv/generic →
  missing_riscv_family_member Finding + 证据链复现成员 proof；
  未选中仅进 extensions。

## 容器
- build-selection 门禁：detector 的 family_member 分支补测后 88.89%→91.67%；
- 18 门禁 + 321 回归 exit=0。
