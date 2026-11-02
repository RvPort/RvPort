# RvPort development guide

English | [简体中文](开发指南.md)

## Architecture and boundaries

API and worker nodes share backend code and use `RVPORT_APP_MODE` to select their responsibilities. PostgreSQL stores application state and task records; RabbitMQ delivers task messages; Redis provides sessions, caching, and broadcasts; object storage holds artifacts. Tools integrate with the backend through controlled subprocesses and versioned JSON protocols.

Main source is under `backend/src/main/java/cn/rvport/backend/`, with corresponding tests in `src/test/`. Generated jOOQ source is in `src/generated/java/`. Liquibase manages database changes. Updating generated types without the corresponding schema change is not sufficient.

See [testing and coverage scope](testing-and-coverage.md) for language scopes, exclusion reasons, and outstanding gaps.

## Builds and unit tests in Docker

Run all commands from the repository root. The host only edits files, invokes Docker, and reads exported reports. Compilation, testing, code generation, and dependency installation run inside containers. Keep outputs in dedicated containers or volumes; do not mount host dependency-cache directories.

The backend unit-test image includes only the build stage and does not require all analysis tools to be built first:

```powershell
docker build --target unit-test -t rvport-backend-unit:local -f backend/Dockerfile .
docker run --name rvport-backend-unit --memory=6g --cpus=2 -e MAVEN_OPTS=-Xmx1536m rvport-backend-unit:local
docker cp rvport-backend-unit:/workspace/backend/target/surefire-reports ./unit-reports
docker cp rvport-backend-unit:/workspace/backend/target/site/jacoco ./coverage-reports
docker rm rvport-backend-unit
```

The container executes `mvn -B clean verify`. Save reports when a command fails and do not mark failed checks as passing. If test failure prevents coverage generation, record that reason rather than substituting an earlier revision's report. Before rerunning, export previous reports and remove the stopped container created for that run if its name will be reused.

Builds use Maven Central by default. To change the download endpoint, pass `--build-arg MAVEN_MIRROR_URL=https://trusted-mirror.example`; set the base JDK image with `--build-arg JAVA_IMAGE=image-reference`. Check the replacement's digest and version. Do not change global Docker configuration or disable TLS verification.

JaCoCo enforces 90% line and branch coverage overall and per backend package; generated jOOQ types are excluded. Retained Python scripts and other owned tools require separate measurement. Passing the Java gate does not establish compliance for the entire project.

Frontend coverage acceptance is currently deferred by project decision, but lint, type checks, ordinary unit tests, and production builds remain required. Finally, perform the complete Compose build in the installation guide, then start all default services with one `docker compose up -d --wait` command and check health, logs, and business flows.

Validate the backend's Python resource scripts separately in the same test image:

```powershell
docker run --name rvport-backend-python --memory=2g --cpus=2 -e PYTHONPATH=/workspace/tools/memory-order-analyzer/python rvport-backend-unit:local sh -c 'python3 -m coverage run --branch --source=src/main/resources/analysis,src/main/resources/bitcode,src/main/resources/agentruntime,agent-cli -m pytest src/test/python; test_status=$?; python3 -m coverage json -o /tmp/backend-python-coverage.json; python3 -m coverage report --fail-under=90; coverage_status=$?; if [ "$test_status" -ne 0 ]; then exit "$test_status"; fi; exit "$coverage_status"'
docker cp rvport-backend-python:/tmp/backend-python-coverage.json ./.migration-reports/backend-python-coverage.json
docker run --rm --memory=1g --cpus=1 --mount "type=bind,source=$((Get-Location).Path)/.migration-reports/backend-python-coverage.json,target=/tmp/coverage.json,readonly" rvport-backend-unit:local python3 -c 'import json; r=json.load(open("/tmp/coverage.json")); rows=[r["totals"],*[f["summary"] for f in r["files"].values()]]; assert all(s["covered_lines"]*100 >= s["num_statements"]*90 and s["covered_branches"]*100 >= s["num_branches"]*90 for s in rows), "Line or branch coverage is below 90%"; print("Overall and per-script line and branch coverage reach 90%")'
```

The first command preserves test failures while producing coverage. The final command independently checks at least 90% line and branch coverage overall and for each script; coverage's combined percentage alone is insufficient. Failure at any step means the gate has not passed. Reports include resource scripts that were not loaded by tests.

Floating-point scanner:

```powershell
docker build -t rvport-fp-dev:local tools/floating-point-precision-scanner -f tools/floating-point-precision-scanner/Dockerfile.dev
docker run --name rvport-fp-unit --memory=8g --cpus=2 -e CMAKE_BUILD_PARALLEL_LEVEL=2 rvport-fp-dev:local make coverage cpp-coverage
docker cp rvport-fp-unit:/workspace/artifacts/coverage ./fp-coverage
docker rm rvport-fp-unit
```

KB Scanner has an independent test container. Existing domain-specific checks are described in `tools/kb-scanner/README.md`. Run the complete ordinary unit suite and full coverage from the repository root:

```powershell
docker build -f tools/kb-scanner/Dockerfile.test -t rvport-kb-scanner-test:local .
docker run --name rvport-kb-unit --memory=4g --cpus=2 --network=none -e COVERAGE_FILE=/tmp/.coverage --entrypoint sh rvport-kb-scanner-test:local -c 'python -m coverage run --branch --source=kb_scanner,scripts,tools -m unittest discover -s tests/unit -t . && python -m coverage json -o /tmp/coverage.json'
docker cp rvport-kb-unit:/tmp/coverage.json ./kb-coverage.json
```

After the preceding command succeeds, check that no owned source was omitted and validate overall and affected-module line/branch gates inside the container. Maintain `affected` to reflect actual changes; do not narrow it to bypass affected code:

```powershell
$kbGate = @'
import json
from pathlib import Path
r = json.load(open('/report.json'))
owned = {p.as_posix() for root in ('kb_scanner', 'scripts', 'tools') for p in Path(root).rglob('*.py')}
assert owned <= set(r['files']), sorted(owned - set(r['files']))
affected = ['kb_scanner/core/metadata.py', 'kb_scanner/analyzers/candidate_usage/proofs.py']
affected += ['kb_scanner/projection/v2/' + name + '.py' for name in ('chain', 'families', 'macro_effects', 'mapper', 'multiversion', 'summary', 'test_content', 'validation')]
rows = [r['totals'], *[r['files'][name]['summary'] for name in affected]]
assert all(s['covered_lines'] * 100 >= s['num_statements'] * 90 and s['covered_branches'] * 100 >= s['num_branches'] * 90 for s in rows)
print('Full-source overall and affected-module line/branch gates passed')
'@
docker run --rm --memory=1g --cpus=1 --network=none --mount "type=bind,source=$((Get-Location).Path)/kb-coverage.json,target=/report.json,readonly" rvport-kb-scanner-test:local -c $kbGate
```

The memory-order analyzer's container coverage entry point is `python3 -m memory_order_analyzer.quality --output /output/unique-run-directory`; see [its development guide](../tools/memory-order-analyzer/doc/development.md). It independently checks all owned source, each language, and every module. Integration profiles do not replace unit-test profiles.

## Database and code generation

Add a Liquibase changeset for schema changes, validate complete initialization against a new database, and run the `jooq-codegen` profile in a container. Set `RVPORT_JOOQ_JDBC_URL`, `RVPORT_JOOQ_JDBC_USER`, and `RVPORT_JOOQ_JDBC_PASSWORD`. Generated code must retain the `cn.rvport.backend.jooq` package.

Code generation reuses the backend unit-test image above; dependencies and outputs remain inside containers. First create a local `.env.codegen` with `POSTGRES_PASSWORD`, `RVPORT_MIGRATION_TEST_PASSWORD`, and `RVPORT_JOOQ_JDBC_PASSWORD`, all set to the same temporary random password. This is local configuration and must not be committed. The code-generation database exposes no host ports and does not reuse deployment volumes.

```powershell
docker network create rvport-codegen
docker run -d --name rvport-codegen-db --network rvport-codegen --env-file .env.codegen -e POSTGRES_DB=rvport_codegen -e POSTGRES_USER=rvport_codegen postgres:18.4
docker exec rvport-codegen-db pg_isready -U rvport_codegen -d rvport_codegen
```

Wait for database readiness before initialization validation and generation. `LiquibaseMigrationTests` applies migrations twice to verify empty-database initialization and repeatability.

```powershell
docker run --name rvport-codegen --network rvport-codegen --memory=6g --cpus=2 --env-file .env.codegen -e MAVEN_OPTS=-Xmx1536m -e RVPORT_MIGRATION_TEST_ENABLED=true -e RVPORT_MIGRATION_TEST_JDBC_URL=jdbc:postgresql://rvport-codegen-db:5432/rvport_codegen -e RVPORT_MIGRATION_TEST_USERNAME=rvport_codegen -e RVPORT_JOOQ_JDBC_URL=jdbc:postgresql://rvport-codegen-db:5432/rvport_codegen -e RVPORT_JOOQ_JDBC_USER=rvport_codegen rvport-backend-unit:local sh -c 'mvn -B -s /opt/maven-cache/settings.xml -Dmaven.repo.local=/opt/maven-cache/repository -Dtest=LiquibaseMigrationTests test && mvn -B -s /opt/maven-cache/settings.xml -Dmaven.repo.local=/opt/maven-cache/repository -Pjooq-codegen generate-sources'
docker cp rvport-codegen:/workspace/backend/src/generated/java ./.migration-reports/jooq-generated
```

Only after the container exits successfully and the report confirms success should the complete exported `java` directory replace `backend/src/generated/java/`. Save the previous generated directory for review first. Overwriting only matching files can leave obsolete types for removed tables. Rebuild the test image and run the full unit and coverage suite; a database test alone does not replace the unit-test gate. After exporting reports, remove the two containers and dedicated network created for this run.

## Code and API changes

- Before removing functions or endpoints, inspect frontend callers, scripts, message handlers, scheduled tasks, reflection, and framework registration. Text-reference counts alone are insufficient.
- Automated testing, connection testing, and model testing exposed by the product are business features, distinct from test-only infrastructure.
- Preserve Chinese and English fields for user-facing business explanations as required by the protocol, such as `suggestion` and `suggestion_en`.
- Maintain unit tests for normal behavior, boundaries, and error paths alongside implementation changes. Overall and affected-module coverage must reach 90% lines and, where supported, branches.
- Update `.env.example` and installation documentation when adding configuration. Do not commit real credentials, runtime logs, or environment data.

## Contribution and release policy

### Commit messages

Use the `Type: Message` format. Write the subject as `<type>: <English> / <中文>`, followed by an English body and its Chinese counterpart describing the changes, rationale, and actual validation results.

| Type | Purpose |
|---|---|
| `feat` | New features or externally observable behavior |
| `test` | Changes primarily concerned with tests, fixtures, or coverage validation |
| `fix` | Defect fixes, including supporting tests |
| `refactor` | Internal restructuring without changing external behavior |
| `ci` | CI/CD pipeline or automation workflow configuration |
| `chconf` | Non-CI project, build, runtime, or tool configuration |
| `docs` | Documentation changes |

Use only these seven abbreviations, not long names such as `feature` or `config change`. Choose the primary purpose for mixed changes; independent purposes can be split into separate commits. Retaining the `ci` type does not restore retired CI facilities. The `docs` type does not exempt executable code in a change from testing requirements.

The following example assumes documentation checks have been completed. Adapt actual messages to the changes and validation performed:

```text
docs: Document contribution rules / 补充贡献规范

Document commit types, code review, branch roles, version naming,
and the Roadmap-to-release workflow. Checked content and relative links.

补充提交类型、代码审查、分支职责、版本命名及 Roadmap 到发布的流程。
已检查内容与相对链接。
```

### Code audit

Human-written code and code generated or modified by AI must follow the same code audit and PR review process. Reviewers check requirements, correctness, risks, coverage of normal behavior, boundaries and error paths, and applicable validation evidence. Address findings and repeat relevant validation before merging. Passing automated checks does not replace code review.

Code changes retain this guide's Docker validation requirements and at least 90% line and available branch coverage overall and for affected modules, with the documented temporary deferral of frontend coverage acceptance. Documentation-only changes require content, link, and formatting checks; unit tests, code coverage, and runtime builds are not applicable.

### Branches and contributions

`dev` is the development integration branch; `master` is the release branch. Routine development reaches upstream `dev` through PRs.

1. Fork the upstream RvPort repository on the hosting platform, then clone your fork locally.
2. Synchronize upstream `dev` and create a local topic branch from it.
3. Complete applicable validation, push the topic branch to your fork, and open a PR targeting upstream `dev`.
4. Link the corresponding Issue in the PR, describe the changes and validation, complete code review, and address feedback before merging into `dev`.
5. Once the entire Roadmap is complete, merge `dev` into `master` through a release PR and publish using the version convention.

These are collaboration rules, not a claim that remote branch protection or automated checks have been configured.

### Version naming

Use four numeric components: major.minor.bug fix.hotfix, written as `<major>.<minor>.<bugfix>.<hotfix>`. Prerelease versions append `-<type><number>`, such as the release candidate suffixes `rc1` and `rc2`.

| Example | Meaning |
|---|---|
| `1.2.0.0-rc1` | First release candidate |
| `1.2.0.0-rc2` | Second release candidate |
| `1.2.0.0` | Final release |

This is the project's four-component convention, not standard SemVer. The documentation specifies the format; it does not automatically update module versions.

### Roadmap, Issues, and PRs

Once the next Roadmap stage is agreed, break the plan into concrete Issues with tasks, acceptance criteria, and assignees. Owners develop on topic branches and deliver through PRs linked to their Issues. Confirm and close each Issue after its PR has merged into `dev` and acceptance is complete.

Do not assume closing keywords in PRs targeting `dev` will automatically close Issues. Check their status after merging and close them manually when necessary. Completing an individual Issue completes that task; only after the entire Roadmap is complete should a release PR merge `dev` into `master` for publication.

## OpenSpec

The repository root, backend, frontend, and three main scanners each have an independent OpenSpec root: six in total. Identify the current project root when executing commands; do not apply a backend change to a tool's store. Conclusions in specifications and archives describe their original revisions. Current implementations require current validation.

Archive an active change only after implementation and validation are complete.
