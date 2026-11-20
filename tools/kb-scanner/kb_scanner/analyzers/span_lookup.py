"""源码包含查询索引；保持原 Evidence 顺序及闭区间包含语义。

SpanLookup 解决"哪些区间的 Evidence 完整包含给定区间"：按 (subject, 文件) 分组，
组内按起点排序，并维护前缀最大终点。查询时二分定位后向前回溯，凡前缀最大
终点都盖不住查询终点的位置可以直接停止——保证 O(log n + k) 且不漏不重。
"""
from bisect import bisect_right
from collections import defaultdict


class SpanLookup:
    """区间包含索引（构建一次，多次查询；组内有序，无副作用）。

    groups[(subject_id, path)] = (rows, starts, ends)：rows 存
    (起点, 终点, 原序号, 条目)；starts 是起点数组；ends 是前缀最大终点数组，
    用于查询时提前终止回溯。
    """

    def __init__(self, values):
        """values：任意可迭代的 Evidence；无 location 的条目被跳过。"""
        groups = defaultdict(list)
        for ordinal, item in enumerate(values):
            if item.location is None:
                continue
            span = item.location
            groups[item.subject_id, span.start.path].append((
                (span.start.line, span.start.column or 1),
                (span.end.line, span.end.column or 1), ordinal, item))
        self.groups = {}
        for key, rows in groups.items():
            # 组内排序：先按区间起点，再按原始序号稳定排序。
            rows.sort(key=lambda row: (row[0], row[2]))
            # 前缀最大终点：只要某位置的前缀最大终点 < 查询终点，更前面的也不可能包含。
            maximum, ends = (0, 0), []
            for row in rows:
                maximum = max(maximum, row[1])
                ends.append(maximum)
            self.groups[key] = rows, [row[0] for row in rows], ends

    def containing(self, item):
        """返回完整包含 item 区间的全部条目（按原输入顺序）。

        算法：二分找最后一个起点 <= 查询起点的位置，向前回溯；ends[pos] < 查询
        终点时停止（前缀最大终点都盖不住，更前的更不可能）；行自身终点 >= 查询
        终点的才命中。闭区间语义：start <= inner.start 且 inner.end <= end。
        """
        if item.location is None:
            return ()
        span = item.location
        rows, starts, ends = self.groups.get((item.subject_id, span.start.path), ((), (), ()))
        end = span.end.line, span.end.column or 1
        position = bisect_right(starts, (span.start.line, span.start.column or 1)) - 1
        result = []
        while position >= 0 and ends[position] >= end:
            row = rows[position]
            if row[1] >= end:
                result.append((row[2], row[3]))
            position -= 1
        return tuple(value for _, value in sorted(result))
