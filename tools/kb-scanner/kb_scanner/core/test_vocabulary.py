"""测试词汇表：识别测试代码/测试目标的共享判定规则。

提供 TestRole（测试角色）等枚举与两个纯函数判定器：
路径形态判定（candidate_test_path）与显式测试目标名判定
（explicit_test_target），供 detector/classifier 判断某段代码
是否属于测试面（影响迁移优先级评估）。
"""

from __future__ import annotations

import re
from collections.abc import Mapping
from dataclasses import dataclass
from enum import Enum

from .metadata import JsonValue, freeze_metadata


class TestRole(str, Enum):
    """测试角色：suite（套件）/ target（目标）/ source（源码）/ candidate（候选）。"""

    SUITE = "test_suite"
    TARGET = "test_target"
    SOURCE = "test_source"
    CANDIDATE = "test_candidate"


class TestRoleStrength(str, Enum):
    """角色强度：confirmed（已确认）/ candidate（仅候选，不得升级为确认角色）。"""

    CONFIRMED = "confirmed"
    CANDIDATE = "candidate"


class TestSelectionStatus(str, Enum):
    """构建系统是否选中该测试：selected/inactive/not_selected/unknown。"""

    SELECTED = "selected"
    INACTIVE = "inactive"
    NOT_SELECTED = "not_selected"
    UNKNOWN = "unknown"


class CoverageStatus(str, Enum):
    """覆盖状态：covered（已覆盖）/ gap（缺口）/ candidate（候选）/ unknown。"""

    COVERED = "covered"
    GAP = "gap"
    CANDIDATE = "candidate"
    UNKNOWN = "unknown"


_TEST_SEGMENT = re.compile(r"(?i)(?:^|/)(?:test|tests)(?:/|$)")
# 路径中含有独立的 test/tests 段，如 src/test/foo.c。
_TEST_STEM = re.compile(r"(?i)(?:^test[_-].+|.+[_-]test)$")
# 文件名以 test_/test- 开头或 _test/-test 结尾，如 test_foo.c、foo_test.c。
_NON_TEST_SEGMENT = re.compile(r"(?i)(?:^|/)(?:benchmark|benchmarks|example|examples|tool|tools)(?:/|$)")
# 含 benchmark/example/tool 段的路径不算测试代码（优先级更高，直接否决）。


@dataclass(frozen=True, slots=True)
class TestRoleEvidence:
    """测试角色证据：某实体扮演的测试角色及判定依据（frozen 不可变）。

    basis 是判定依据的描述（溯源）；configuration 表示该判定针对的
    构建配置（默认 "default"，多配置项目下同一目标可有多个角色证据）；
    candidate 强度的证据不得声称 confirmed 角色（防止误判固化）。
    """

    role: TestRole
    strength: TestRoleStrength
    basis: str
    selection: TestSelectionStatus = TestSelectionStatus.UNKNOWN
    configuration: str = "default"
    metadata: Mapping[str, JsonValue] | None = None

    def __post_init__(self) -> None:
        if not self.basis or not self.configuration:
            raise ValueError("test role basis and configuration must not be empty")
        if self.strength is TestRoleStrength.CANDIDATE and self.role is not TestRole.CANDIDATE:
            raise ValueError("candidate strength cannot be promoted to a confirmed test role")
        object.__setattr__(self, "metadata", freeze_metadata(self.metadata))


def candidate_test_path(path: str) -> bool:
    """按路径形态判断是否可能是测试代码（不含扩展名级确认）。

    规则：先否决 benchmark/example/tool 段；再看是否有独立 test/tests
    段或文件名以 test[_-]/[_-]test 形态命名。返回 True 仅表示"候选"。
    """
    normalized = path.replace("\\", "/").strip("/")
    if not normalized or _NON_TEST_SEGMENT.search(normalized):
        return False
    stem = normalized.rsplit("/", 1)[-1].rsplit(".", 1)[0]
    return bool(_TEST_SEGMENT.search(normalized) or _TEST_STEM.fullmatch(stem))


def explicit_test_target(name: str, input_kind: str = "") -> bool:
    """判断构建目标名是否显式为测试目标（如 test/check/test-all）。

    input_kind="ctest" 时任何非空名字都视为测试目标
    （ctest 的条目本身就是测试定义）。
    """
    normalized = name.strip().lower()
    if input_kind == "ctest":
        return bool(normalized)
    return normalized in {"test", "tests", "check", "check-local", "test-all"} or normalized.startswith(
        ("test-", "check-")
    )
