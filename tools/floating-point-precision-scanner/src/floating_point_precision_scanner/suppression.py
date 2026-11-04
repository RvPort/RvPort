"""稳定、带理由的 finding suppression。"""

from __future__ import annotations

import fnmatch
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from typing import Any

from .canonical import stable_id


@dataclass(frozen=True, slots=True)
class Suppression:
    code: str
    path: str
    reason: str
    start_line: int | None = None
    end_line: int | None = None
    source: str = "request"
    expires_on: str | None = None

    def matches(self, finding: Mapping[str, Any]) -> bool:
        if finding.get("finding_code") != self.code:
            return False
        location = finding.get("location")
        if not isinstance(location, Mapping):
            return False
        point = location.get("expansion") or location.get("spelling") or location
        if not isinstance(point, Mapping):
            return False
        path = str(point.get("path", ""))
        line = int(point.get("line", 0))
        if not fnmatch.fnmatchcase(path, self.path):
            return False
        return not (
            self.start_line is not None
            and (line < self.start_line or line > (self.end_line or self.start_line))
        )


def parse_suppressions(values: Sequence[Mapping[str, Any]]) -> tuple[Suppression, ...]:
    return tuple(
        Suppression(
            code=str(item["code"]),
            path=str(item["path"]),
            reason=str(item["reason"]),
            start_line=(int(item["start_line"]) if item.get("start_line") is not None else None),
            end_line=(int(item["end_line"]) if item.get("end_line") is not None else None),
            expires_on=(str(item["expires_on"]) if item.get("expires_on") else None),
        )
        for item in values
    )


def apply_suppressions(
    findings: Sequence[Mapping[str, Any]],
    suppressions: Sequence[Suppression],
    *,
    max_records: int = 1000,
    policy_as_of: str | None = None,
) -> tuple[tuple[Mapping[str, Any], ...], tuple[Mapping[str, Any], ...]]:
    visible: list[Mapping[str, Any]] = []
    records: list[Mapping[str, Any]] = []
    matched_suppressions: set[Suppression] = set()
    for finding in sorted(findings, key=lambda item: (str(item["finding_code"]),
                                                     str(item["finding_id"]))):
        matching = [item for item in suppressions if item.matches(finding)]
        if not matching or len(records) >= max_records:
            visible.append(finding)
            continue
        selected = sorted(
            matching,
            key=lambda item: (item.code, item.path, item.start_line or 0, item.reason),
        )[0]
        matched_suppressions.add(selected)
        expired = bool(
            selected.expires_on is not None
            and policy_as_of is not None
            and policy_as_of > selected.expires_on
        )
        identity = {
            "finding_id": finding["finding_id"],
            "code": selected.code,
            "path": selected.path,
            "start_line": selected.start_line,
            "end_line": selected.end_line,
            "source": selected.source,
            "expires_on": selected.expires_on,
        }
        records.append(
            {
                "suppression_id": stable_id("suppression", identity),
                "finding_id": finding["finding_id"],
                "finding_code": selected.code,
                "path": selected.path,
                "start_line": selected.start_line,
                "end_line": selected.end_line,
                "reason": selected.reason,
                "reason_en": selected.reason,
                "source": selected.source,
                "status": "expired" if expired else "applied",
                "expires_on": selected.expires_on,
            }
        )
        if expired:
            visible.append(finding)
    for selected in sorted(
        (item for item in suppressions if item not in matched_suppressions),
        key=lambda item: (item.code, item.path, item.start_line or 0, item.reason),
    ):
        if len(records) >= max_records:
            break
        identity = {
            "finding_id": None,
            "code": selected.code,
            "path": selected.path,
            "start_line": selected.start_line,
            "end_line": selected.end_line,
            "source": selected.source,
            "expires_on": selected.expires_on,
        }
        records.append(
            {
                "suppression_id": stable_id("suppression", identity),
                "finding_id": None,
                "finding_code": selected.code,
                "path": selected.path,
                "start_line": selected.start_line,
                "end_line": selected.end_line,
                "reason": selected.reason,
                "reason_en": selected.reason,
                "source": selected.source,
                "status": "unmatched",
                "expires_on": selected.expires_on,
            }
        )
    return (
        tuple(
            sorted(visible, key=lambda item: (str(item["finding_code"]), str(item["finding_id"])))
        ),
        tuple(sorted(records, key=lambda item: str(item["suppression_id"]))),
    )
