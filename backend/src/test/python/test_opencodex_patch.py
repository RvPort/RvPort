"""受控补丁的纯单元测试，不下载第三方代码。"""
import hashlib
import importlib.util
import json
from pathlib import Path
import pytest

SPEC = importlib.util.spec_from_file_location('patch_eof', Path(__file__).parents[3] / 'agent-cli/opencodex/patch_eof.py')
patch = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(patch)


def digest(data):
    return hashlib.sha512(data).hexdigest()


def test_patch_and_idempotence():
    source = b'prefix\n' + patch.OLD + b'\nend'
    expected = source.replace(patch.OLD, patch.NEW)
    assert patch.patch_bytes(source, digest(source), digest(expected)) == expected
    assert patch.patch_bytes(expected, digest(source), digest(expected)) == expected


@pytest.mark.parametrize('source,before,after', [
    (b'other', 'unknown', 'unknown'),
    (patch.OLD + patch.OLD, digest(patch.OLD + patch.OLD), 'unknown'),
    (b'no marker', digest(b'no marker'), 'unknown'),
    (patch.OLD, digest(patch.OLD), 'wrong result'),
])
def test_reject_changed_sources(source, before, after):
    with pytest.raises(ValueError):
        patch.patch_bytes(source, before, after)


def test_apply_fixed_package(tmp_path, monkeypatch):
    package = tmp_path / 'node_modules/@bitkyc08/opencodex'
    (package / 'src/adapters').mkdir(parents=True)
    (package / 'package.json').write_text(json.dumps({'version': '2.94.0'}))
    path = package / 'src/adapters/openai-chat.ts'
    path.write_bytes(b'original')
    content_path = package / 'src/adapters/openai-chat/response-events.ts'
    content_path.parent.mkdir()
    content_path.write_bytes(b'original')
    usage_path = package / 'src/bridge/internal.ts'
    usage_path.parent.mkdir()
    usage_path.write_bytes(b'original')
    monkeypatch.setattr(patch, 'patch_bytes', lambda value, *_: b'patched' if value == b'original' else None)
    evidence = patch.apply(tmp_path)
    assert [item['patch'] for item in evidence['patches']] == [
        'strict-text-eof-and-finish-reason', 'strict-content-type', 'preserve-missing-usage']
    assert path.read_bytes() == b'patched'
    assert content_path.read_bytes() == b'patched'
    assert usage_path.read_bytes() == b'patched'
    (package / 'package.json').write_text(json.dumps({'version': 'changed'}))
    with pytest.raises(ValueError, match='版本'):
        patch.apply(tmp_path)


def test_content_patch_is_idempotent_and_validated():
    source = b'prefix\n' + patch.CONTENT_OLD + b'\nend'
    expected = source.replace(patch.CONTENT_OLD, patch.CONTENT_NEW)
    args = (digest(source), digest(expected), patch.CONTENT_OLD, patch.CONTENT_NEW)
    assert patch.patch_bytes(source, *args) == expected
    assert patch.patch_bytes(expected, *args) == expected
    with pytest.raises(ValueError):
        patch.patch_bytes(source+b'drift', *args)


def test_multiple_edits_have_one_final_hash():
    original, expected = b'first second', b'FIRST SECOND'
    args = (digest(original), digest(expected), (b'first', b'second'), (b'FIRST', b'SECOND'))
    assert patch.patch_bytes(original, *args) == expected
    assert patch.patch_bytes(expected, *args) == expected
    with pytest.raises(ValueError):
        patch.patch_bytes(original, digest(original), digest(expected), (b'first',), ())


def test_second_patch_failure_leaves_first_unchanged(tmp_path, monkeypatch):
    package = tmp_path / 'node_modules/@bitkyc08/opencodex'
    package.mkdir(parents=True)
    (package / 'package.json').write_text('{"version":"2.94.0"}')
    (package / 'first').write_bytes(patch.OLD)
    (package / 'second').write_bytes(b'unexpected')
    monkeypatch.setattr(patch, 'PATCHES', (
        ('first','first',digest(patch.OLD),digest(patch.NEW),patch.OLD,patch.NEW),
        ('second','second','wrong','wrong',patch.CONTENT_OLD,patch.CONTENT_NEW)))
    with pytest.raises(ValueError):
        patch.apply(tmp_path)
    assert (package / 'first').read_bytes() == patch.OLD


def test_binary_digest(tmp_path):
    binary = tmp_path / 'node_modules/bun/bin/bun.exe'
    binary.parent.mkdir(parents=True)
    binary.write_bytes(b'frozen binary')
    expected = hashlib.sha256(b'frozen binary').hexdigest()
    assert patch.verify_binary(tmp_path, expected) == expected
    with pytest.raises(ValueError, match='哈希'):
        patch.verify_binary(tmp_path)
