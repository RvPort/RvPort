from collections import defaultdict
from dataclasses import replace
from pathlib import Path
import unittest
from unittest.mock import patch

from kb_scanner.analyzers.evidence_graph import GraphEntityIndexer, RelationBuilder, _index_branches, _contains, _family_knowledge_ids
from .support import context, evidence, file, knowledge, snapshot
from kb_scanner.core import stable_id, KnowledgeEntry


class QueryIndexTests(unittest.TestCase):
    def test_enum_condition_reverse_index_avoids_whole_snapshot_rescans(self):
        source, other = file("enum.cc"), file("other.cc")
        member = evidence(source, "enum_member_declaration", "Backend::avx2", 1)
        references = tuple(evidence(source, "enum_member_reference", "Backend::avx2", line,
                                   metadata={"enumMemberDeclarationEvidenceIds": (member.id,), "bindingStatus": "complete"})
                           for line in range(20, 52))
        noise = tuple(evidence(other, "identifier", "unrelated", line) for line in range(1, 501))
        condition = evidence(source, "selection_condition", "B == Backend::avx2", 3, end_line=100,
                             metadata={"templateDependent": True, "guardEndLine": 120})
        facts = snapshot(source, other, evidence_items=(member, *noise, *references, condition))
        ctx = context(Path("/tmp"))
        index = GraphEntityIndexer().index(ctx, facts, knowledge())
        builder = RelationBuilder(ctx, facts, knowledge(), index)
        reads = []
        class CountedDict(dict):
            def get(self, key, default=None):
                reads.append(key)
                return super().get(key, default)
        index.evidence_entities = CountedDict(index.evidence_entities)
        builder._enum_relations()
        self.assertLess(len(reads), 4 * len(facts.evidence))
        selected = [r for r in builder._relations.values() if r.kind == "selects"]
        self.assertEqual(1, len(selected))
        self.assertEqual("unknown", selected[0].status)
        self.assertEqual({condition.id, member.id, *(r.id for r in references)}, set(selected[0].evidence_ids))
        reversed_facts = replace(facts, evidence=tuple(reversed(facts.evidence)))
        reverse = RelationBuilder(ctx, reversed_facts, knowledge(), GraphEntityIndexer().index(ctx, reversed_facts, knowledge()))
        reverse._enum_relations()
        self.assertEqual(builder._relations, reverse._relations)

    def test_knowledge_patterns_compile_once_and_keep_original_boundaries(self):
        source = file("a.c")
        entries = tuple(KnowledgeEntry(stable_id(token), "intrinsics", "x86", token, (stable_id("source"),))
                        for token in ("_mm_add", "special.op", "x"))
        facts = replace(knowledge(), entries=entries)
        cache = {}
        import re
        with patch("kb_scanner.analyzers.evidence_graph.re.compile", wraps=re.compile) as compile_pattern:
            for text in ("_mm_add", "call(_mm_add)", "prefix_mm_add_suffix", "special.op", "specialXop", "αxβ"):
                item = evidence(source, "call_expression", text, 1)
                actual = _family_knowledge_ids(item, facts, cache)
                expected = tuple(sorted(entry.id for entry in entries if
                    text.casefold() == entry.canonical_value.casefold() or
                    re.search(r"(?<![A-Za-z0-9_])" + re.escape(entry.canonical_value.casefold()) + r"(?![A-Za-z0-9_])", text.casefold())))
                self.assertEqual(expected, actual)
            self.assertEqual(3, compile_pattern.call_count)
        self.assertEqual((), _family_knowledge_ids(replace(item, value=None), facts))
        self.assertEqual((), _family_knowledge_ids(item, knowledge(), {}))

    def test_branch_containment_only_visits_matching_paths(self):
        sources, values = [], []
        for i in range(64):
            source = file(f"{i}.c")
            sources.append(source)
            values.extend((evidence(source, "preprocessor_condition", "__x86_64__", 1, end_line=10, metadata={"nodeType": "preproc_if"}),
                           evidence(source, "preprocessor_branch", "else", 5, end_line=9)))
        visits = []
        def contains(outer, inner):
            visits.append(1)
            return _contains(outer, inner)
        with patch("kb_scanner.analyzers.evidence_graph._contains", side_effect=contains):
            records = _index_branches(tuple(values), {s.id: s for s in sources}, defaultdict(list), knowledge())
        self.assertEqual(128, len(records))
        self.assertLessEqual(len(visits), 128)
        self.assertEqual(64, sum(x.role == "fallback" for x in records))
        self.assertTrue(all(x.branch.name.startswith("2:") for x in records if x.role == "fallback"))

    def test_relation_lookup_preserves_merges_status_and_metadata(self):
        source = file("a.c")
        first = evidence(source, "call_expression", "one", 1)
        second = evidence(source, "call_expression", "two", 2)
        facts = snapshot(source, evidence_items=(first, second))
        ctx = context(Path("/tmp"))
        index = GraphEntityIndexer().index(ctx, facts, knowledge())
        builder = RelationBuilder(ctx, facts, knowledge(), index)
        reads = []
        class CountedDict(dict):
            def values(self):
                reads.append(len(self))
                return super().values()
        builder._relations = CountedDict()
        for i in range(100):
            builder._add("calls", source.id, stable_id(str(i)), (first.id,), "complete")
        builder._add("calls", source.id, stable_id("0"), (second.id,), "complete")
        builder._add("calls", source.id, stable_id("0"), (first.id,), "complete", metadata={"configuration": "other"})
        builder._add("calls", source.id, stable_id("0"), (first.id,), "unknown")
        self.assertEqual(102, len(builder._relations))
        self.assertEqual([], reads)
        merged = [r for r in builder._relations.values() if r.target_id == stable_id("0") and r.status == "complete" and not r.metadata]
        self.assertEqual(1, len(merged))
        self.assertEqual({first.id, second.id}, set(merged[0].evidence_ids))
        builder._add("calls", source.id, stable_id("none"), (stable_id("missing"),), "complete")
        self.assertTrue(any(r.target_id == stable_id("none") for r in builder._relations.values()))
        # Frozen tuple metadata may hash like a list without comparing equal to it.
        # Replacing an identical ID must not duplicate the bucket entry.
        for metadata in ({"values": [1]}, {"values": [1]}, {"values": (1,)}):
            builder._add("calls", source.id, stable_id("sequence"), (first.id,), "complete", metadata=metadata)
        self.assertEqual(1, len([r for r in builder._relations.values() if r.target_id == stable_id("sequence")]))

    def test_partial_evidence_and_separate_invocation_indexes(self):
        source = file("a.c")
        item = evidence(source, "inline_asm", "unknown", 1)
        ctx = context(Path("/tmp"))
        for confidence in ("high", "medium"):
            facts = snapshot(source, evidence_items=(replace(item, confidence=confidence),))
            index = GraphEntityIndexer().index(ctx, facts, knowledge())
            builder = RelationBuilder(ctx, facts, knowledge(), index)
            builder._add("calls", source.id, source.id, (item.id,), "complete")
            relation = next(iter(builder._relations.values()))
            self.assertEqual("complete" if confidence == "high" else "partial", relation.status)
