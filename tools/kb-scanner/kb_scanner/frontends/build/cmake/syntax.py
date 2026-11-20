"""Lower CMake native nodes immediately; keep the Tree alive during traversal.

CMake 语法层：tree-sitter-cmake 解析后立刻把每个命令降级为自有的
Command(name, args, token)，token 携带 1-based 行列位置。带节点/
深度/字节预算；语法部分错误时抛 PartialSyntaxError 并附带已成功
降级的命令，供项目层降级续用。
"""

from bisect import bisect_right
from dataclasses import dataclass

from tree_sitter import Language, Parser
import tree_sitter_cmake


@dataclass(frozen=True)
class Token:
    """命令参数 token（frozen 不可变）：文本 + 1-based 起止行列 + 是否引号参数。"""

    text: str
    line: int = 1
    column: int = 1
    end_line: int = 1
    end_column: int = 2
    quoted: bool = False


@dataclass(frozen=True)
class Command:
    """一条 CMake 命令（frozen 不可变）：小写命令名、参数元组与命令本身的主 token。"""

    name: str
    args: tuple[Token, ...]
    token: Token


class PartialSyntaxError(ValueError):
    """语法部分错误：携带已成功降级的命令，调用方可降级续用而非全盘丢弃。"""

    def __init__(self, commands):
        super().__init__("cmake-partial-syntax")
        self.commands = tuple(commands)


def parse(source, *, max_nodes=100000, max_depth=64, max_bytes=4*1024*1024):
    """解析 CMake 文本为 Command 元组；语法部分错误抛 PartialSyntaxError。

    行列换算：bisect 行首偏移表得行号，行内字节切片解码得列号
    （均为 1-based）。括号 token 也作为参数保留，供条件解析使用。
    """
    if min(max_nodes, max_depth, max_bytes) < 1:
        raise ValueError("cmake-invalid-budget")
    data = source.encode("utf-8")
    if len(data) > max_bytes:
        raise ValueError("cmake-input-budget")
    lines = (0, *(i+1 for i, b in enumerate(data) if b == 10))
    parser = Parser(Language(tree_sitter_cmake.language()))
    tree = parser.parse(data)
    partial = tree.root_node.has_error
    commands = []
    count = 0

    def token(node, quoted=False):
        """把原生节点转为 Token：bisect 定位行，字节差换算列。"""
        start, end = node.start_byte, node.end_byte
        a, b = bisect_right(lines, start), bisect_right(lines, end)
        text = data[start:end].decode("utf-8")
        return Token(text, a, len(data[lines[a-1]:start].decode("utf-8"))+1,
                     b, len(data[lines[b-1]:end].decode("utf-8"))+1, quoted)

    def arguments(node, depth=0):
        """递归收集参数 token；quoted 标记来自引号/括号参数的 token。"""
        check(depth)
        if node.type == "argument":
            yield token(node, any(n.type in {"quoted_argument", "bracket_argument"} for n in node.children))
        elif node.type in {"(", ")"}:
            yield token(node)
        else:
            for child in node.children:
                yield from arguments(child, depth+1)

    def check(depth):
        """节点计数与深度预算检查，超限抛 ValueError。"""
        nonlocal count
        count += 1
        if count > max_nodes or depth > max_depth:
            raise ValueError("cmake-syntax-budget")

    def walk(node, depth=0):
        """遍历语法树收集 *_command 节点；含错误的命令整条跳过。"""
        check(depth)
        if node.type.endswith("_command"):
            if node.has_error:
                return
            children = node.named_children
            name = data[children[0].start_byte:children[0].end_byte].decode("utf-8").lower()
            args = tuple(t for child in children if child.type == "argument_list" for t in arguments(child, depth+1))
            commands.append(Command(name, args, token(node)))
        else:
            for child in node.named_children:
                walk(child, depth+1)
    walk(tree.root_node)
    if partial:
        raise PartialSyntaxError(commands)
    return tuple(commands)
