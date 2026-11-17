from __future__ import annotations

import hashlib
import json
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


Completion = Literal["complete", "partial", "ambiguous", "inactive", "unknown"]
Selection = Literal["selected", "inactive", "not_selected", "unknown"]
Severity = Literal["info", "low", "medium", "high", "critical"]
Reason = Literal[
    "context_budget_exceeded", "context_unavailable", "cycle_detected", "dynamic_expression",
    "inactive_branch", "missing_input", "missing_reference", "multiple_candidates",
    "reference_budget_exceeded", "type_conflict", "configuration_conflict",
]


class ClosedModel(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


class TypedRef(ClosedModel):
    kind: Literal["entity", "evidence", "knowledge", "observation", "relation", "proofPath", "classification", "assessment"]
    id: str
    producer: str


class Position(ClosedModel):
    line: int = Field(ge=1)
    column: int = Field(ge=1)


class Span(ClosedModel):
    start: Position
    end: Position


class ContextLine(ClosedModel):
    line: int = Field(ge=1)
    text: str


class Highlight(ClosedModel):
    line: int = Field(ge=1)
    startColumn: int = Field(ge=1)
    endColumn: int = Field(ge=2)


class ContextBudget(ClosedModel):
    beforeLines: int = Field(ge=0)
    afterLines: int = Field(ge=0)
    maxLines: int = Field(ge=1, le=256)
    maxCharacters: int = Field(ge=1, le=65536)
    truncated: bool


class SourceContext(ClosedModel):
    status: Literal["complete", "partial", "unavailable"]
    reasonCodes: tuple[Reason, ...] = ()
    lines: tuple[ContextLine, ...] = ()
    highlights: tuple[Highlight, ...] = ()
    budget: ContextBudget

    @model_validator(mode="after")
    def validate_state(self) -> "SourceContext":
        if self.status == "complete" and (not self.lines or not self.highlights or self.reasonCodes):
            raise ValueError("complete context requires lines/highlights and no reasons")
        if self.status == "unavailable" and (self.lines or self.highlights or not self.reasonCodes):
            raise ValueError("unavailable context requires only reasons")
        return self


class Source(ClosedModel):
    file: str
    span: Span
    context: SourceContext


class Fact(ClosedModel):
    kind: Literal["architecture_probe", "build_argument", "compiler_flag", "header_include", "intrinsic_call", "macro_invocation", "macro_replacement", "test_asset", "vector_type"]
    symbol: str | None = None
    matchedText: str
    primaryLocation: Source
    provenance: tuple[TypedRef, ...]
    extensions: dict[str, dict[str, Any]] | None = None


class Conclusion(ClosedModel):
    title: str
    severity: Severity
    confidence: float = Field(ge=0, le=1)
    findingType: str
    actionable: bool
    blocking: bool
    archReachability: Selection
    status: Completion
    reason: str
    suggestion: str
    suggestion_en: str | None = None
    extensions: dict[str, dict[str, Any]] | None = None

    @field_validator("suggestion_en", mode="before")
    @classmethod
    def validate_english_suggestion(cls, value: object) -> str:
        # Missing historical fields use the unvalidated default. Explicit null
        # is not a historical omission and must match the JSON Schema rejection.
        if not isinstance(value, str) or not value.strip():
            raise ValueError("suggestion_en must be a nonblank string when provided")
        return value

    def require_bilingual_suggestions(self) -> None:
        """Producer-only contract; historical readers must not call this."""
        for value in (self.suggestion, self.suggestion_en):
            if not isinstance(value, str) or not value.strip():
                raise ValueError("new V2 findings require both nonblank suggestions")


class Occurrence(ClosedModel):
    id: str
    kind: str
    location: Source
    configuration: str | None = None
    selectionStatus: Selection
    status: Completion
    reasonCodes: tuple[Reason, ...] = ()
    provenance: tuple[TypedRef, ...]
    extensions: dict[str, dict[str, Any]] | None = None


class KnowledgeProjection(ClosedModel):
    entryId: str
    category: str
    symbol: str
    producer: str
    sourceId: str
    sourceSha256: str
    provenance: tuple[TypedRef, ...]


class Step(ClosedModel):
    id: str
    kind: Literal["build_selector", "target_selection", "source_selection", "source_evidence", "macro_invocation", "macro_definition", "macro_replacement", "intrinsic_call", "vector_type", "compiler_flag", "test_asset", "knowledge_match", "classification", "assessment"]
    title: str
    status: Completion
    reason: str
    reasonCodes: tuple[Reason, ...] = ()
    symbol: str | None = None
    configuration: str | None = None
    selectionStatus: Selection | None = None
    source: Source | None = None
    knowledge: KnowledgeProjection | None = None
    provenance: tuple[TypedRef, ...]
    extensions: dict[str, dict[str, Any]] | None = None


class Edge(ClosedModel):
    id: str
    fromStepId: str
    toStepId: str
    kind: Literal["selects", "contains", "includes", "invokes_macro", "defines", "expands_to", "calls", "matches_knowledge", "classified_as", "impacts_assessment", "alternative_to", "guarded_by"]
    status: Completion
    reasonCodes: tuple[Reason, ...] = ()
    provenance: tuple[TypedRef, ...]


class Break(ClosedModel):
    id: str
    status: Literal["partial", "ambiguous", "unknown"]
    reasonCode: Reason
    stage: str
    fromStepId: str | None = None
    candidateStepIds: tuple[str, ...] = ()
    expectedKind: str | None = None
    missingId: str | None = None


class ChainBudget(ClosedModel):
    maxDepth: int = 16
    maxVisited: int = 256
    maxPaths: int = 32
    visited: int = Field(ge=0)
    paths: int = Field(ge=0)
    truncated: bool = False


class EvidenceChain(ClosedModel):
    status: Completion
    reasonCodes: tuple[Reason, ...] = ()
    rootStepId: str
    primaryPath: tuple[str, ...]
    steps: tuple[Step, ...]
    edges: tuple[Edge, ...] = ()
    breaks: tuple[Break, ...] = ()
    impactStepIds: tuple[str, ...]
    budget: ChainBudget


class Finding(ClosedModel):
    id: str
    fact: Fact
    conclusion: Conclusion
    occurrences: tuple[Occurrence, ...]
    primaryOccurrenceId: str
    evidenceChain: EvidenceChain
    extensions: dict[str, dict[str, Any]] | None = None


class Tool(ClosedModel):
    name: Literal["kb-scanner"] = "kb-scanner"
    version: str


class Scope(ClosedModel):
    domain: Literal["c-cpp-x86-to-riscv", "c-cpp-arm64-to-riscv", "rust-x86-to-riscv", "rust-arm64-to-riscv", "go-x86-to-riscv", "go-arm64-to-riscv", "java-x86-to-riscv", "java-arm64-to-riscv", "python-x86-to-riscv", "python-arm64-to-riscv"]
    targetArchitecture: Literal["riscv32", "riscv64"]
    staticAnalysisOnly: Literal[True] = True
    compiled: Literal[False] = False
    linked: Literal[False] = False
    testsExecuted: Literal[False] = False


class KnowledgeSourceView(ClosedModel):
    id: str
    kind: Literal["compiler-derived", "curated", "collected"]
    sha256: str


class Scan(ClosedModel):
    id: str
    tool: Tool
    scope: Scope
    artifactNames: tuple[str, ...] = ("scan-result.json", "scan-report.html", "scan.log")
    knowledgeSources: tuple[KnowledgeSourceView, ...] = ()
    extensions: dict[str, dict[str, Any]] | None = None


class Project(ClosedModel):
    id: str
    name: str
    filesAnalyzed: int = Field(ge=0)
    status: Completion
    reasonCodes: tuple[Reason, ...] = ()
    extensions: dict[str, dict[str, Any]] | None = None


class Summary(ClosedModel):
    files: int = Field(ge=0)
    findings: int = Field(ge=0)
    issues: int = Field(ge=0)
    hints: int = Field(ge=0)
    blocking: int = Field(ge=0)
    portabilityGaps: int | None = Field(ge=0)
    inactiveOrGuarded: int = Field(ge=0)
    referenceSources: int = Field(ge=0)
    references: int = Field(ge=0)
    countUnit: Literal["finding"] = "finding"
    readiness: Literal["x86_only", "arm64_only", "provisional", "unknown", "ready"]
    status: Completion
    reasonCodes: tuple[Reason, ...] = ()
    extensions: dict[str, dict[str, Any]] | None = None


class V2Diagnostic(ClosedModel):
    id: str
    code: str
    stage: str
    severity: Severity
    status: Completion
    reasonCodes: tuple[Reason, ...] = ()
    count: int = Field(ge=1)
    representativeLocation: Source | None = None
    findingIds: tuple[str, ...] = ()
    refs: tuple[TypedRef, ...] = ()
    extensions: dict[str, dict[str, Any]] | None = None


class SchemaV2Report(ClosedModel):
    schemaVersion: Literal["2.0"] = "2.0"
    scan: Scan
    project: Project
    summary: Summary
    findings: tuple[Finding, ...] = ()
    diagnostics: tuple[V2Diagnostic, ...] = ()
    extensions: dict[str, dict[str, Any]] | None = None


def stable_view_id(kind: str, *parts: Any) -> str:
    material = json.dumps((kind, parts), ensure_ascii=False, sort_keys=True, separators=(",", ":"), default=str)
    return f"{kind}:{hashlib.sha256(material.encode('utf-8')).hexdigest()}"


def confidence_value(value: str) -> float:
    return {"high": 1.0, "medium": 0.75, "low": 0.5}.get(value, 0.25)
