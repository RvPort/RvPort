# Knowledge data and maintenance

English | [简体中文](knowledge.zh-CN.md)

The runtime reads a scanner bundle. `scanner-manifest.json` pins the knowledge manifest digest and policy identity; manifests contain no commands or plugins. Generated data, accepted curated entries, and the reference registry can be loaded. The decision ledger is not a runtime input.

<a id="doc-knowledge-production-baseline"></a>

## Data composition

### Three artifact categories

- `generated/*.json` contains deterministic outputs from toolchain structure definitions.
- `curated/x86-migration-semantics.json` contains accepted entries only; `curated/source-references.json` holds their complete authoritative references.
- `collected/arm64/*` holds the frozen corpus files (ARM Architecture Reference Manual excerpts, collected manually). `generated/arm64-collected-entries.json` is their deterministic conversion output and is registered as a `collected-v1` manifest source: manual-manuscript corpora have no exact git revision, so provenance is the corpus files' SHA-256 digests plus the official manual URL and collection date.
- `collected/riscv/riscv.macro` holds the frozen RISC-V **target-side** platform macros (203 entries, collected via the rvport-skills/riscv-scan corpus from the RISC-V C-API specification). `generated/riscv-collected-entries.json` is their deterministic conversion output, registered as a `collected-v1` source with family `riscv` and scope `riscv-only`. Target-family entries mean the opposite of source families: a hit indicates an existing RISC-V branch (already ported), not a migration obstacle. They are consumed only by the explicit `family="riscv"` query in branch-role analysis and never enter `source_arch` values or the detector registry.
- `collected/rust/*` holds the frozen Rust arch function/struct corpus (from rvport-skills/riscv-scan, extracted from Rust std::arch modules). `tools/collect_rust_entries.py` converts it into three per-family `collected-v1` sources (single-family payloads): arm64 intrinsics/vectorTypes (a pure increment — the C corpus has no arm64 intrinsics), riscv target-family intrinsics (hits mean RV intrinsics are already in use), and x86 vectorTypes. x86 Rust function names are deliberately skipped: they overlap the existing C intrinsics wordlists by 99.4%.
- `audit/candidate-decisions.json` records generated/accepted/rejected/deferred decisions and reasons, but the Provider never loads it. `audit/candidate-inventory.json` is explicitly non-authoritative.

Knowledge JSON contains no regex, operator, matcher, severity, blocking, reason, suggestion, message, or readiness policy. Python `DetectorDefinition` objects still control matching, and downstream classification controls impact.

## Manifest and compatibility

The production manifest is explicitly v2 and supports `legacy-generated`, `generated-v2`, `curated-v1`, and `collected-v1` sources. The Provider also strictly accepts the original v1 generated-only manifest; v1 continues to reject `curatedSets`. For rollback, `tests/fixtures/knowledge-production/v1-manifest.json` can replace the production manifest to load only the two legacy fact sets.

Current frozen file digests are:

| File | SHA-256 |
|---|---|
| `generated/clang-x86-entries.json` | `adf187416c006b120e43f2d2ce0677ee2104a4c27de4dd9cabd1df12add352f7` |
| `generated/gcc-x86-entries.json` | `22d68c2ab3c8c40c8606451af2e5f493877661d031b6b2f379eff7886c23d191` |

The GCC source path was sanitized to `vendor/gcc/gcc/config/i386` for publication; the facts themselves are unchanged. Historical report digests identify their original versions and must not be rewritten with the current digest.

The historical legacy CLI, `schemaVersion: "1.0"` result contract, Finding semantics, 24 YAML/`generatedSet` entries, and three artifacts remain covered by full regressions. New knowledge does not introduce a second result structure. The current scanner defaults to V2; V1 is selected explicitly.

## Detection semantics

- Bounded instruction/register matches in inline assembly, CPU API calls in source, and feature or CPU/architecture names in target attributes produce `confirmed_local` with a Knowledge ID. CPU names are not searched globally across arbitrary macros or variables, avoiding false positives for ordinary words such as `generic` and `intel`.
- `vector_size(16/32/64)` produces only a knowledge-backed `candidate`.
- Comments remain hints. Unknown assembly words, ordinary numbers, and similar API names are not upgraded to confirmed matches.
- Graph analysis, build selection, packaging, and blocking/severity/readiness decisions remain outside the knowledge layer.

<a id="doc-knowledge-refresh-guide"></a>

## Refresh workflow

### ARM64 collected corpus

The arm64 corpus is converted by `tools/collect_aarch64_entries.py` (no network, no subprocess). It reads the frozen corpus files under `collected/arm64/` and writes `generated/arm64-collected-entries.json` deterministically. Mapping and cleaning rules are documented in the script header:- `aarch64.builtin` → `builtins` (complete `__builtin_aarch64_*` identifiers only)
- `aarch64.intrinsics` → `headers` (ARM header file names)
- `aarch64.macro` → `platformMacros` (complete identifier macros; truncated prefixes such as `__arm_` are dropped)
- `aarch64.opcode` → `assemblyInstructions` (`#` comments skipped; manual table field names — the underscored `EXC_*`/`DSNP_*`/`SPSR_*` families — and punctuation outliers such as `M.A.A` are dropped; canonical values are lowercased)

After regeneration, verify the output is byte-identical to the committed file (a contract test enforces this), then update the manifest digests as for other sources.

### RISC-V target-side corpus

The riscv macro corpus is converted by `tools/collect_riscv_entries.py` (same determinism guarantees). It reads `collected/riscv/riscv.macro` and writes `generated/riscv-collected-entries.json`:

- `riscv.macro` → `platformMacros` (complete `__riscv*` identifiers; truncated prefixes are dropped)

Regeneration and manifest digest updates follow the arm64 procedure. The same contract-test pattern pins counts (203), corpus digests, and byte-identical regeneration.

### Prerequisites

Refresh is separate from scanning. Maintainers first obtain and verify a specified release/commit of the official upstream repositories outside the collector container. The collector itself does not access the network, clone repositories, or run compilers, assemblers, or linkers. Mount checkouts read-only and write to a separate output directory. Review differences before replacing generated/curated files in the repository.

Fixed input paths:

- LLVM: `llvm/lib/Target/X86/X86RegisterInfo.td`, `llvm/lib/Target/X86/X86.td`
- GCC: `gcc/common/config/i386/i386-common.cc`, `gcc/common/config/i386/i386-isas.h`, `gcc/config/i386/cpuid.h`, `gcc/config/i386/ia32intrin.h`, `gcc/doc/extend.texi`
- Binutils: `opcodes/i386-opc.tbl`, `opcodes/i386-reg.tbl`

### Refresh

Build only the isolated test image:

```powershell
docker compose -f tools/kb-scanner/compose.test.yml build kb-scanner-test
```

Mount the absolute directories represented by `LLVM_SOURCE`, `GCC_SOURCE`, `BINUTILS_SOURCE`, and `KNOWLEDGE_OUTPUT` as read-only inputs and a separate writable output. Run `scripts/refresh_knowledge_sources.py` inside `rvport-kb-scanner-test:local`. It accepts only explicit `--llvm-root`, `--gcc-root`, `--binutils-root`, and `--output-root` arguments; it never discovers host paths automatically.

Run twice with identical inputs. Every output file must have the same SHA-256 in both output directories. Then review:

1. Upstream revisions, releases, licenses, and input paths/hashes.
2. Counts and differences for each fact kind; large opcode counts must not conceal API/vector gaps.
3. Reasons for rejected/deferred ledger entries. The ledger must not enter the manifest.
4. Accepted status for every curated entry, reproducible IDs, and complete references.
5. Updated digests for new data files in the manifest, without modifying the two frozen legacy compiler fact files.

### Validation and recovery

Run refresh in Docker. Preserve traceability for upstream revisions, licenses, and input digests, and require byte-identical repeated output. The frozen Clang/GCC facts do not change with other knowledge updates. Replace manifests only after reviewing differences. If validation fails, restore the entire verified data/digest set, not just one manifest. See [development](development.md) for the full test entry point.
