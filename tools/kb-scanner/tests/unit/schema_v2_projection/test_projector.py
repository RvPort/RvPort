from __future__ import annotations

import os
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from kb_scanner.core import CompletionStatus
from kb_scanner.projection.publisher import AtomicArtifactPublisher
from kb_scanner.projection.v2.projector import SchemaV2ReportProjector
from .support import graph_with_source, report


class PublisherProjectorTests(unittest.TestCase):
    def test_chunked_artifacts_are_consumed_once_and_hash_exact_bytes(self):
        import hashlib
        consumed = []
        def chunks():
            for value in (b'{', '"中文":1'.encode(), b'}\n'):
                consumed.append(value)
                yield value
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp)
            refs = AtomicArtifactPublisher().publish(output, {'a': chunks(), 'b': b'log'})
            expected = '{"中文":1}\n'.encode()
            self.assertEqual(expected, (output/'a').read_bytes())
            self.assertEqual(3, len(consumed))
            self.assertEqual(hashlib.sha256(expected).hexdigest(), refs[0].sha256)

    def test_chunk_generation_failure_preserves_old_complete_artifact_set(self):
        def broken():
            yield b'partial'
            raise ValueError('stream failure')
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp)
            (output/'a').write_bytes(b'old')
            with self.assertRaisesRegex(ValueError, 'stream failure'):
                AtomicArtifactPublisher().publish(output, {'a': b'new', 'b': broken()})
            self.assertEqual(b'old', (output/'a').read_bytes())
            self.assertEqual({'a'}, {path.name for path in output.iterdir()})

    def test_large_report_uses_chunked_json_with_same_contract(self):
        from kb_scanner.projection.v2.models import stable_view_id
        original = report().findings[0]
        conclusion = original.conclusion.model_copy(update={'suggestion_en': 'Review target path.'})
        findings = tuple(sorted((original.model_copy(update={'id': stable_view_id('finding', i),
            'conclusion': conclusion}) for i in range(256)), key=lambda item: item.id))
        publisher = Mock()
        publisher.publish.return_value = ()
        with tempfile.TemporaryDirectory() as temp, patch('kb_scanner.projection.v2.projector.V2FindingMapper') as mapper:
            mapper.return_value.map.return_value = findings
            result = SchemaV2ReportProjector(publisher).project(*graph_with_source(Path(temp)))
        self.assertEqual(CompletionStatus.COMPLETE, result.status)
        encoded = publisher.publish.call_args.args[1]['scan-result.json']
        self.assertNotIsInstance(encoded, bytes)
        data = json.loads(b''.join(encoded))
        self.assertEqual(256, len(data['findings']))
        self.assertEqual([item.id for item in findings], [item['id'] for item in data['findings']])

    def test_function_and_family_catalogs_are_merged_at_publication_boundary(self):
        publisher=Mock()
        publisher.publish.return_value=()
        with tempfile.TemporaryDirectory() as temp, patch('kb_scanner.projection.v2.projector.FamilyProjection') as families, patch('kb_scanner.projection.v2.projector.MultiversionProjection') as functions, patch('kb_scanner.projection.v2.projector.render_html',return_value=b'<html>fake renderer boundary</html>'):
            families.return_value.report.return_value={'org.rvport.kb-scanner.architecture-family':{'families':[]}}
            functions.return_value.report.return_value={'org.rvport.kb-scanner.function-multiversion':{'groups':[], 'explanation':'静态组'}}
            result=SchemaV2ReportProjector(publisher).project(*graph_with_source(Path(temp)))
        self.assertEqual(CompletionStatus.COMPLETE,result.status)
        payload=json.loads(publisher.publish.call_args.args[1]['scan-result.json'])
        self.assertEqual({'org.rvport.kb-scanner.architecture-family','org.rvport.kb-scanner.function-multiversion'},set(payload['extensions']))

    def test_producer_missing_or_invalid_pair_never_reaches_publisher(self):
        original = report().findings[0]
        for update in ({}, {"suggestion_en": ""}, {"suggestion_en": "English", "suggestion": " \t"}):
            with self.subTest(update=update), tempfile.TemporaryDirectory() as temp:
                finding = original.model_copy(update={"conclusion": original.conclusion.model_copy(update=update)})
                publisher = Mock()
                with patch("kb_scanner.projection.v2.projector.V2FindingMapper") as mapper:
                    mapper.return_value.map.return_value = (finding,)
                    result = SchemaV2ReportProjector(publisher).project(*graph_with_source(Path(temp)))
                self.assertEqual(CompletionStatus.FAILED, result.status)
                self.assertIsNone(result.value)
                self.assertEqual("projection.v2-failed", result.diagnostics[0].code)
                self.assertIn("both nonblank", result.diagnostics[0].message)
                publisher.publish.assert_not_called()
                self.assertFalse((Path(temp) / "out").exists())

    def test_valid_bilingual_producer_publishes_complete_set_through_fake_boundary(self):
        finding = report().findings[0]
        finding = finding.model_copy(update={"conclusion": finding.conclusion.model_copy(update={
            "suggestion": "请核查目标路径。", "suggestion_en": "Review the target path.",
        })})
        publisher = Mock()
        publisher.publish.return_value = ()
        with tempfile.TemporaryDirectory() as temp, patch("kb_scanner.projection.v2.projector.V2FindingMapper") as mapper:
            mapper.return_value.map.return_value = (finding,)
            result = SchemaV2ReportProjector(publisher).project(*graph_with_source(Path(temp)))
        self.assertEqual(CompletionStatus.COMPLETE, result.status)
        artifacts = publisher.publish.call_args.args[1]
        self.assertEqual({"scan-result.json", "scan-report.html", "scan.log"}, set(artifacts))
        self.assertIn("请核查目标路径。".encode(), artifacts["scan-result.json"])
        self.assertIn(b'"suggestion_en":"Review the target path."', artifacts["scan-result.json"])

    def test_publisher_creates_digests_and_overwrites_complete_set(self):
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp)
            (output / "a").write_bytes(b"old")
            artifacts = AtomicArtifactPublisher().publish(output, {"a": b"new", "b": b"two"})
            self.assertEqual(b"new", (output / "a").read_bytes())
            self.assertEqual({"a", "b"}, {item.name for item in artifacts})
            self.assertTrue(all(len(item.sha256) == 64 for item in artifacts))

    def test_publisher_rolls_back_mid_replace_failure(self):
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp)
            (output / "a").write_bytes(b"old-a")
            (output / "b").write_bytes(b"old-b")
            calls = 0
            def replace(source, target):
                nonlocal calls
                calls += 1
                if calls == 3:
                    raise OSError("injected")
                os.replace(source, target)
            with self.assertRaises(OSError):
                AtomicArtifactPublisher(replace).publish(output, {"a": b"new-a", "b": b"new-b"})
            self.assertEqual(b"old-a", (output / "a").read_bytes())
            self.assertEqual(b"old-b", (output / "b").read_bytes())

    def test_publisher_reports_rollback_failure(self):
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp)
            (output / "a").write_bytes(b"old-a")
            (output / "b").write_bytes(b"old-b")
            calls = 0
            def replace(source, target):
                nonlocal calls
                calls += 1
                if calls >= 3:
                    raise OSError("injected")
                os.replace(source, target)
            with self.assertRaisesRegex(RuntimeError, "rollback failed"):
                AtomicArtifactPublisher(replace).publish(output, {"a": b"new-a", "b": b"new-b"})

    def test_projector_directly_publishes_three_v2_artifacts(self):
        with tempfile.TemporaryDirectory() as temp:
            values = graph_with_source(Path(temp))
            result = SchemaV2ReportProjector().project(*values)
            self.assertEqual(CompletionStatus.COMPLETE, result.status)
            self.assertEqual({"scan-result.json", "scan-report.html", "scan.log"}, {item.name for item in result.value})
            self.assertIn(b'"schemaVersion":"2.0"', (Path(temp) / "out/scan-result.json").read_bytes())

    def test_projector_returns_failed_diagnostic_without_partial_publish(self):
        class FailingPublisher:
            def publish(self, *_):
                raise OSError("no")
        with tempfile.TemporaryDirectory() as temp:
            result = SchemaV2ReportProjector(FailingPublisher()).project(*graph_with_source(Path(temp)))
            self.assertEqual(CompletionStatus.FAILED, result.status)
            self.assertEqual("projection.v2-failed", result.diagnostics[0].code)


class DomainDerivationTests(unittest.TestCase):
    """domain 语言参数化（Rust change 引入）：第一命中语言取前缀、无命中回退。"""

    def test_first_mapped_language_wins(self):
        from types import SimpleNamespace

        from kb_scanner.projection.v2.projector import _domain

        self.assertEqual("rust-x86-to-riscv", _domain(SimpleNamespace(languages=("rust",), source_arch="x86")))
        self.assertEqual("go-arm64-to-riscv", _domain(SimpleNamespace(languages=("go",), source_arch="arm64")))
        self.assertEqual("java-x86-to-riscv", _domain(SimpleNamespace(languages=("java",), source_arch="x86")))
        self.assertEqual("python-arm64-to-riscv", _domain(SimpleNamespace(languages=("python",), source_arch="arm64")))
        self.assertEqual("c-cpp-x86-to-riscv", _domain(SimpleNamespace(languages=("c", "rust"), source_arch="x86")))
        self.assertEqual("c-cpp-arm64-to-riscv", _domain(SimpleNamespace(languages=("asm",), source_arch="arm64")))

    def test_unmapped_and_missing_languages_fall_back(self):
        from types import SimpleNamespace

        from kb_scanner.projection.v2.projector import _domain

        self.assertEqual("c-cpp-x86-to-riscv", _domain(SimpleNamespace(languages=("cobol",), source_arch="x86")))
        self.assertEqual("c-cpp-x86-to-riscv", _domain(SimpleNamespace(languages=(), source_arch="x86")))
        self.assertEqual("c-cpp-arm64-to-riscv", _domain(SimpleNamespace(source_arch="arm64")))
