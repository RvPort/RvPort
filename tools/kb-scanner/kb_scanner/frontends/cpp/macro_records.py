"""Copy bounded macro call/branch data while the parser tree is still alive.

frontend 阶段的宏记录辅助模块：在 tree-sitter / libclang 的原生对象
仍然存活期间，把宏调用参数 token、预处理分支区间等数据复制为纯标量
记录。token 数量有硬上限（预算耗尽返回 unknown 而非截断），且从不
推断"传入名字即执行"这类语义。
"""
import re

from .vocabulary import make_evidence


_TOKEN = re.compile(rb'//[^\n]*|/\*[\s\S]*?\*/|(?:u8|u|U|L)?"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|[A-Za-z_]\w*|0[xX][0-9a-fA-F]+|[0-9]+|[^\s]')
_NAME = re.compile(rb'[A-Za-z_]\w*\Z')
_BRANCHES = {'preproc_if', 'preproc_ifdef', 'preproc_ifndef', 'preproc_elif', 'preproc_else'}


def event_metadata(node, source, positions):
    """为预处理节点生成附加元数据：分支区间（#if/#elif/#else 归组）与 pragma 标记。

    preprocessorRegion 思路：#elif/#else 先沿 parent 链上溯找到条件组
    根（首个 #if/#ifdef/#ifndef），再记录整组区间、分支体结束位置
    （下一个 alternative 的起点）与行号，供后续按分支归并证据。
    """
    result = {'sourceOrder': int(node.start_byte)}
    if node.type in _BRANCHES:
        root = node
        while root.type in {'preproc_elif', 'preproc_else'} and root.parent is not None and root.parent.type in _BRANCHES:
            root = root.parent
        alternative = node.child_by_field_name('alternative')
        result['preprocessorRegion'] = {
            'groupStart': int(root.start_byte), 'groupEnd': int(root.end_byte),
            'start': int(node.start_byte),
            'bodyEnd': int(alternative.start_byte) if alternative is not None else int(node.end_byte),
            'groupEndLine': positions.location(int(root.end_byte)).line,
            'alternativeStart': int(alternative.start_byte) if alternative is not None else None,
            'branchKind': str(node.type),
        }
    if node.type in {'preproc_def', 'preproc_function_def'}:
        result['functionLike'] = node.type == 'preproc_function_def'
    if node.type == 'preproc_call' and re.match(rb'#\s*pragma\s+(?:push_macro|pop_macro)\b',source[node.start_byte:node.end_byte]):
        result['unsupportedMacroStateDirective'] = True
    return result


def call_records(node, source, positions, call, *, token_limit=4096):
    """Keep original argument tokens; never infer that passing a name executes it.

    用正则从调用参数原文切出 token（跳过注释/字符串外的空白），超过
    token_limit 即整体放弃（unknown + macro-token-budget），不截断。
    成功时交给 _copy_arguments 复制参数结构并附挂 macro_argument_token
    证据。
    """
    function = node.child_by_field_name('function')
    arguments = node.child_by_field_name('arguments')
    if function is None or arguments is None or function.type != 'identifier':
        return {'status': 'unknown', 'reason': 'unsupported-call-shape'}, ()
    start, end = int(arguments.start_byte), int(arguments.end_byte)
    raw = source[start:end]
    if not raw.startswith(b'(') or not raw.endswith(b')'):
        return {'status': 'unknown', 'reason': 'malformed-call-arguments'}, ()
    tokens = []
    for match in _TOKEN.finditer(raw[1:-1]):
        if match.group().startswith((b'//',b'/*')):
            continue
        if len(tokens) >= token_limit:
            return {'status': 'unknown', 'reason': 'macro-token-budget'}, ()
        tokens.append((match.group(), start + 1 + match.start(), start + 1 + match.end()))
    return _copy_arguments(tokens, call, positions.span)


def clang_call_records(cursor, call, token_span, *, token_limit=4096):
    """Copy native token fields inside the live translation unit, never retain Cursor.

    与 call_records 同构的 libclang 版本：直接取 TU 的原生 token，
    校验形状（名字 + 括号包裹）后复用 _copy_arguments；任何游标数据
    不可用都降级为 unknown，不影响基线事实。
    """
    tokens, spans = [], []
    try:
        for index, token in enumerate(cursor.get_tokens()):
            if index >= token_limit + 3:
                return {'status':'unknown', 'reason':'macro-token-budget'}, ()
            tokens.append((str(token.spelling).encode(), index, index))
            spans.append(token_span(token))
        if len(tokens) < 3 or tokens[0][0].decode() != call.value or tokens[1][0] != b'(' or tokens[-1][0] != b')':
            return {'status':'unknown','reason':'unsupported-call-shape'}, ()
        return _copy_arguments(tokens[2:-1], call, lambda first, last:spans[first])
    except (AttributeError, RuntimeError, ValueError):
        return {'status':'unknown','reason':'unavailable-native-call-tokens'}, ()


def _copy_arguments(tokens, call, span_for):
    """把 token 序列按顶层逗号切分为参数，并为标识符 token 挂 macro_argument_token 证据。

    括号深度计数决定逗号是否为参数分隔符；出现非 名字/数字/括号 的
    token、深度失衡或空参数即整体 unsupported（unknown），不部分输出。
    """
    args, current, depth, supported = [], [], 0, True
    for value, first, last in tokens:
        if value == b',' and depth == 0:
            args.append(tuple(current))
            current = []
            continue
        depth += 1 if value == b'(' else -1 if value == b')' else 0
        if depth < 0 or depth > 32 or not (_NAME.fullmatch(value) or value[:1].isdigit() or value in {b'(', b')'}):
            supported = False
        current.append((value.decode('ascii', errors='replace'), first, last))
    if current or args:
        args.append(tuple(current))
    if depth or any(not arg for arg in args):
        supported = False
    attachments, copied = [], []
    for ordinal, arg in enumerate(args):
        copied_arg = []
        for index, (value, first, last) in enumerate(arg):
            span = span_for(first, last)
            record = {'value': value, 'range': (span.start.line, span.start.column, span.end.line, span.end.column)}
            if _NAME.fullmatch(value.encode('ascii', errors='replace')):
                item = make_evidence(kind='macro_argument_token', subject_id=call.subject_id, value=value,
                    producer=call.producer, parse_mode=call.parse_mode, confidence=call.confidence,
                    span=span, context_id=call.metadata.get('compilationContextId'),
                    metadata={**{key: call.metadata[key] for key in ('language', 'languageSource') if key in call.metadata},
                              'callEvidenceId': call.id, 'argumentOrdinal': ordinal, 'ordinal': index,
                              'bindingStatus': 'unresolved', 'sourceOrder': first})
                attachments.append(item)
                record['evidenceId'] = item.id
            copied_arg.append(record)
        copied.append(tuple(copied_arg))
    return {'status': 'complete' if supported else 'unknown',
            'reason': None if supported else 'unsupported-call-arguments', 'arguments': tuple(copied),
            'tokenCount':len(tokens)}, tuple(attachments)
