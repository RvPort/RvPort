.PHONY: install format lint typecheck unit integration validation benchmark coverage cpp-configure cpp-unit cpp-coverage verify verify-release

install:
	python -m pip install -r requirements-dev.lock -e .

format:
	python -m ruff format src tests

lint:
	python -m ruff check src tests

typecheck:
	python -m mypy

unit:
	python -m pytest tests/unit tests/contract

integration:
	python -m pytest -m integration tests/integration

validation:
	mkdir -p artifacts/validation
	python -m floating_point_precision_scanner.corpus_runner \
		--inputs tests/fixtures/validation/executable-inputs-v1.json \
		--output artifacts/validation/current-observed.json
	python -m floating_point_precision_scanner.validation \
		--corpus tests/fixtures/validation/executable-corpus-v1.json \
		--observed artifacts/validation/current-observed.json \
		--output artifacts/validation/validation-result.json

benchmark:
	mkdir -p artifacts/benchmark
	python -m floating_point_precision_scanner.benchmark_runner \
		--baseline tests/fixtures/benchmark/accepted-baseline-v1.json \
		--output artifacts/benchmark/benchmark-result.json

coverage:
	python -m coverage erase
	python -m coverage run -m pytest tests/unit tests/contract
	python -c "from pathlib import Path; Path('artifacts/coverage').mkdir(parents=True, exist_ok=True)"
	python -m coverage json -o artifacts/coverage/coverage.json
	python -m coverage xml -o artifacts/coverage/coverage.xml
	python -m coverage html -d artifacts/coverage/htmlcov
	python -m floating_point_precision_scanner.coverage_gate artifacts/coverage/coverage.json \
		--affected floating_point_precision_scanner.canonical_types \
		--affected floating_point_precision_scanner.ast_relations \
		--affected floating_point_precision_scanner.candidates \
		--affected floating_point_precision_scanner.frontend \
		--affected floating_point_precision_scanner.facts \
		--affected floating_point_precision_scanner.ir_worker \
		--affected floating_point_precision_scanner.evidence \
		--affected floating_point_precision_scanner.profiles \
		--affected floating_point_precision_scanner.build_contexts \
		--affected floating_point_precision_scanner.rules \
		--affected floating_point_precision_scanner.reduction_candidates \
		--affected floating_point_precision_scanner.application \
		--affected floating_point_precision_scanner.protocol \
		--affected floating_point_precision_scanner.result_v2 \
		--affected floating_point_precision_scanner.step_descriptions \
		--affected floating_point_precision_scanner.cli \
		--affected floating_point_precision_scanner.suppression \
		--affected floating_point_precision_scanner.budget \
		--affected floating_point_precision_scanner.cache \
		--affected floating_point_precision_scanner.validation \
		--affected floating_point_precision_scanner.corpus_runner \
		--affected floating_point_precision_scanner.benchmark \
		--affected floating_point_precision_scanner.benchmark_runner \
		--affected floating_point_precision_scanner.source_locations

cpp-configure:
	cmake -S . -B build -G Ninja -DCMAKE_C_COMPILER=clang-21 -DCMAKE_CXX_COMPILER=clang++-21 -DLLVM_DIR=/usr/lib/llvm-21/lib/cmake/llvm -DFP_SCANNER_BUILD_TESTS=ON -DFP_SCANNER_COVERAGE=ON
	cmake --build build

cpp-unit: cpp-configure
	mkdir -p artifacts/coverage
	rm -f artifacts/coverage/cpp-*.profraw
	LLVM_PROFILE_FILE=$(CURDIR)/artifacts/coverage/cpp-%p.profraw ctest --test-dir build --output-on-failure

cpp-coverage: cpp-unit
	llvm-profdata-21 merge -sparse artifacts/coverage/cpp-*.profraw -o artifacts/coverage/cpp.profdata
	llvm-cov-21 export build/fp-ir-worker-tests -object build/fp-ir-worker -instr-profile=artifacts/coverage/cpp.profdata -format=text src/cpp/ir_facts.cpp src/cpp/ir_worker.cpp > artifacts/coverage/cpp-coverage.json
	python -c "import json; d=json.load(open('artifacts/coverage/cpp-coverage.json'))['data'][0]; checks=[('total', d['totals'])]+[(f['filename'], f['summary']) for f in d['files']]; print([(n, s['lines']['percent'], s['branches']['percent']) for n,s in checks]); assert all(s['lines']['percent'] >= 90 and s['branches']['percent'] >= 90 for n,s in checks), checks"

verify: lint typecheck coverage validation cpp-coverage

verify-release: verify benchmark
