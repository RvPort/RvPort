"""Copy native syntax into bounded, scanner-owned byte-offset records.

Autoconf 语法层：用 tree-sitter-bash 解析 configure 类脚本，再降级
(lower) 为扫描器自有的不可变 Node/Source 结构（字节偏移 + 行表），
后续求值不再依赖 tree-sitter 对象。带节点数/深度预算，超限或语法
错误时整体标记为 unsupported 而非报错。
"""
from __future__ import annotations

from bisect import bisect_right
from dataclasses import dataclass

from tree_sitter import Language, Parser
import tree_sitter_bash


@dataclass(frozen=True, slots=True)
class Node:
    """语法树节点（frozen 不可变）：kind + 字节区间 + 带字段名的子节点。

    children 的每项是 (字段名|None, Node)，field/fields 按字段名取子节点。
    """

    kind: str
    start: int
    end: int
    children: tuple[tuple[str | None, "Node"], ...] = ()

    def field(self, name: str) -> "Node | None":
        """取第一个具有该字段名的子节点，无则 None。"""
        return next((child for key, child in self.children if key == name), None)

    def fields(self, name: str) -> tuple["Node", ...]:
        """取所有具有该字段名的子节点。"""
        return tuple(child for key, child in self.children if key == name)


@dataclass(frozen=True, slots=True)
class Source:
    """一个已解析的源文件（frozen 不可变）：字节内容 + 语法树 + 行首偏移表。

    lines 为每行行首字节偏移（0-based），position 据此换算 1-based 行列。
    issue 非空表示解析降级（预算或语法错误），求值器应视为 unknown。
    """

    path: str
    data: bytes
    root: Node
    lines: tuple[int, ...]
    issue: str = ""

    def text(self, node: Node) -> str:
        """取节点覆盖的源文本（字节切片后 UTF-8 解码，坏字节替换）。"""
        return self.data[node.start:node.end].decode("utf-8", errors="replace")

    def position(self, node: Node) -> tuple[int, int, int]:
        """节点位置换算为 (1-based 行号, 起始列, 行内结束列)。

        二分查行表定位行；结束列截断到节点首个换行处，保证跨行节点
        的列值仍有意义。
        """
        line = bisect_right(self.lines, node.start)
        offset = self.lines[line - 1]
        stop = self.data.find(b"\n", node.start, node.end)
        end = node.end if stop < 0 else stop
        return line, node.start - offset + 1, end - offset + 1


def parse(source: str, path: str, *, max_nodes: int = 100000, max_depth: int = 64) -> Source:
    """把 shell 文本解析为 Source（降级 tree-sitter 节点为自有 Node）。

    语法树含错误、节点/深度超预算时返回 kind="unsupported" 的根节点
    并把原因写入 issue，调用方据此把整个文件按 unknown 处理。
    """
    if min(max_nodes, max_depth) < 1:
        raise ValueError("Autoconf syntax budgets must be positive")
    data = source.encode("utf-8")
    lines = (0, *(i + 1 for i, byte in enumerate(data) if byte == 10))
    tree = Parser(Language(tree_sitter_bash.language())).parse(data)
    count = 0

    def lower(native, depth):
        nonlocal count
        count += 1
        if count > max_nodes or depth > max_depth:
            raise ValueError("autoconf-syntax-budget")
        if native.is_missing or native.type == "ERROR":
            raise ValueError("autoconf-partial-syntax")
        return Node(native.type, native.start_byte, native.end_byte,
                    tuple((native.field_name_for_child(i), lower(child, depth + 1))
                          for i, child in enumerate(native.children)))

    try:
        if tree.root_node.has_error:
            raise ValueError("autoconf-partial-syntax")
        root = lower(tree.root_node, 0)
        issue = ""
    except ValueError as exc:
        root, issue = Node("unsupported", 0, len(data)), str(exc)
    return Source(path, data, root, lines, issue)
