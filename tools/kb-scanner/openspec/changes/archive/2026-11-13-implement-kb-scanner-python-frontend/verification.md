# 验证记录（2026-11-14）

## 本机
- 单测（ctypes/cffi 五形态/内嵌 C/pyx 行正则/goreleaser）+ 端到端样本扫描全绿；默认 c,cpp 输出字节不变断言。

## 容器
- 18 门禁 + 321 回归 exit=0；frontend/graph/compatible-cutover 等门禁
  按套件补齐（workspace 语言域门控、cli/context 分支、vocabulary 工厂）。
