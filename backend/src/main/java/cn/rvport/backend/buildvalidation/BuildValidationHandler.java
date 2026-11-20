package cn.rvport.backend.buildvalidation;

import cn.rvport.backend.archive.ArchiveCandidateRegistrar;
import cn.rvport.backend.buildvalidation.bitcode.BitcodeArtifactService;
import cn.rvport.backend.buildvalidation.bitcode.BitcodeGenerationRequest;
import cn.rvport.backend.buildvalidation.bitcode.BitcodeGenerationResult;
import cn.rvport.backend.buildvalidation.bitcode.BitcodeReasonCode;
import cn.rvport.backend.buildvalidation.bitcode.BitcodeStatus;
import cn.rvport.backend.artifact.StoredArtifactCreateCommand;
import cn.rvport.backend.artifact.StoredArtifactOwnerType;
import cn.rvport.backend.artifact.StoredArtifactService;
import cn.rvport.backend.artifact.StoredArtifactView;
import cn.rvport.backend.credential.CredentialService;
import cn.rvport.backend.environment.EnvironmentRepository;
import cn.rvport.backend.environment.EnvironmentView;
import cn.rvport.backend.environment.runtime.SshClientAdapter;
import cn.rvport.backend.environment.runtime.SshCommandRequest;
import cn.rvport.backend.environment.runtime.SshCommandResult;
import cn.rvport.backend.environment.runtime.SshConnectionTarget;
import cn.rvport.backend.environment.runtime.SshRuntimeCredential;
import cn.rvport.backend.environment.runtime.SshRuntimeException;
import cn.rvport.backend.source.deploy.SourceDeployExecutionRequest;
import cn.rvport.backend.source.deploy.SourceDeployExecutor;
import cn.rvport.backend.source.deploy.SourceDeployRunView;
import cn.rvport.backend.task.RvPortTaskTypes;
import cn.rvport.backend.task.TaskErrorCode;
import cn.rvport.backend.task.TaskExecutionException;
import cn.rvport.backend.task.TaskLogRepository;
import cn.rvport.backend.task.TaskLogType;
import cn.rvport.backend.task.TaskScriptPayloadService;
import cn.rvport.backend.task.TaskStatus;
import cn.rvport.backend.task.ToolRunRepository;
import cn.rvport.backend.worker.TaskCancelledException;
import cn.rvport.backend.worker.TaskExecutionContext;
import cn.rvport.backend.worker.TaskHandler;
import cn.rvport.backend.worker.TaskHandlerResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class BuildValidationHandler implements TaskHandler {

    private static final String TOOL_NAME = "rvport-build-validation";
    private static final String TOOL_VERSION = "1";
    private static final long MAX_COMPILE_DATABASE_BYTES = 50L * 1024L * 1024L;

    private final BuildValidationRepository buildValidationRepository;
    private final SourceDeployExecutor sourceDeployExecutor;
    private final EnvironmentRepository environmentRepository;
    private final CredentialService credentialService;
    private final TaskScriptPayloadService taskScriptPayloadService;
    private final SshClientAdapter sshClientAdapter;
    private final TaskLogRepository taskLogRepository;
    private final ToolRunRepository toolRunRepository;
    private final ArchiveCandidateRegistrar archiveCandidateRegistrar;
    private final StoredArtifactService storedArtifactService;
    private final BitcodeArtifactService bitcodeArtifactService;
    private final ObjectMapper objectMapper;
    @org.springframework.beans.factory.annotation.Autowired
    private cn.rvport.backend.source.SourceRepository analysisSources;
    @org.springframework.beans.factory.annotation.Autowired
    private cn.rvport.backend.report.review.ReportSourceSnapshotService analysisSnapshots;

    public BuildValidationHandler(
            BuildValidationRepository buildValidationRepository,
            SourceDeployExecutor sourceDeployExecutor,
            EnvironmentRepository environmentRepository,
            CredentialService credentialService,
            TaskScriptPayloadService taskScriptPayloadService,
            SshClientAdapter sshClientAdapter,
            TaskLogRepository taskLogRepository,
            ToolRunRepository toolRunRepository,
            ArchiveCandidateRegistrar archiveCandidateRegistrar,
            StoredArtifactService storedArtifactService,
            BitcodeArtifactService bitcodeArtifactService,
            ObjectMapper objectMapper
    ) {
        this.buildValidationRepository = buildValidationRepository;
        this.sourceDeployExecutor = sourceDeployExecutor;
        this.environmentRepository = environmentRepository;
        this.credentialService = credentialService;
        this.taskScriptPayloadService = taskScriptPayloadService;
        this.sshClientAdapter = sshClientAdapter;
        this.taskLogRepository = taskLogRepository;
        this.toolRunRepository = toolRunRepository;
        this.archiveCandidateRegistrar = archiveCandidateRegistrar;
        this.storedArtifactService = storedArtifactService;
        this.bitcodeArtifactService = bitcodeArtifactService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String taskType() {
        return RvPortTaskTypes.BUILD_VALIDATION;
    }

    @Override
    public TaskHandlerResult handle(TaskExecutionContext context) {
        BuildValidationTaskInput input = context.input(BuildValidationTaskInput.class);
        BuildValidationRunView run = buildValidationRepository.findById(input.buildRunId())
                .orElseThrow(() -> new TaskExecutionException(TaskErrorCode.RESOURCE_NOT_FOUND, "构建运行不存在", null));
        UUID toolRunId = context.publish(() -> {
        buildValidationRepository.markRunning(run.id());
        return toolRunRepository.create(
                context.task().projectId(),
                context.task().id(),
                TOOL_NAME,
                TOOL_VERSION,
                "构建验证",
                TaskStatus.RUNNING,
                OffsetDateTime.now()
        );
        });
        try {
            context.progress(10, "开始构建验证");
            context.throwIfCancellationRequested();
            SourceDeployRunView deployment = ensureDeployment(input, context);
            EnvironmentView environment = requireEnvironment(input);
            EnvironmentView jumpHost = requireJumpHost(environment);
            String script = taskScriptPayloadService.requireScript(
                    input.scriptPayloadId(),
                    input.projectId(),
                    context.task().id(),
                    input.scriptSha256()
            );

            context.progress(70, "正在远端环境执行构建脚本");
            SshCommandResult result = sshClientAdapter.execute(new SshCommandRequest(
                    target(environment),
                    jumpHost == null ? null : target(jumpHost),
                    buildCommand(deployment.remotePath(), input.environmentVariables()),
                    script,
                    true,
                    Duration.ofSeconds(30),
                    Duration.ofSeconds(effectiveTimeout(input.timeoutSeconds())),
                    context
            ));
            UUID mergedLogId = latestLogId(context, TaskLogType.COMBINED);
            UUID stdoutLogId = latestLogId(context, TaskLogType.STDOUT);
            UUID stderrLogId = latestLogId(context, TaskLogType.STDERR);
            Map<String, Object> summary = summary(input, deployment, result);
            TaskStatus status = result.exitCode() == 0 ? TaskStatus.SUCCEEDED : TaskStatus.FAILED;
            String failureCategory = result.exitCode() == 0 ? null : "BUILD_COMMAND_FAILED";
            Map<String, Object> compileDatabase = status == TaskStatus.SUCCEEDED
                    ? collectCompileDatabase(input, deployment, environment, jumpHost, context)
                    : compileDatabaseUnavailable("BUILD_FAILED");
            BitcodeGenerationResult bitcode = collectBitcode(
                    input, deployment, environment, jumpHost, context, status, compileDatabase);
            Map<String, Object> buildFacts = buildFacts(input, deployment, result, mergedLogId, stdoutLogId,
                    stderrLogId, compileDatabase, bitcode.summary().toMap());
            context.publish(() -> {
            toolRunRepository.finish(toolRunId, status, result.exitCode(), summary);
            buildValidationRepository.updateBuildFacts(run.id(), buildFacts);
            buildValidationRepository.markFinished(run.id(), status, result.exitCode(), failureCategory, mergedLogId, stdoutLogId, stderrLogId);
            BuildValidationRunView finished = buildValidationRepository.findById(run.id()).orElse(run);
            archiveCandidateRegistrar.registerBuildValidation(finished,
                    archiveSummary(input, deployment, result, status, failureCategory, bitcode.summary().toMap()),
                    bitcode.artifacts());
            return null;
            });
            if (result.exitCode() != 0) {
                throw new TaskExecutionException(TaskErrorCode.REMOTE_COMMAND_FAILED, "构建验证失败", null);
            }
            context.progress(90, "构建验证完成");
            return TaskHandlerResult.success("构建验证成功");
        } catch (TaskCancelledException ex) {
            markFinishedWithLatestLogs(context, run.id(), TaskStatus.CANCELLED, null, "BUILD_CANCELLED");
            context.publish(() -> { toolRunRepository.finish(toolRunId, TaskStatus.CANCELLED, null, Map.of("error", "任务已取消")); return null; });
            throw ex;
        } catch (TaskExecutionException ex) {
            if (!isAlreadyFinished(run.id())) {
                markFinishedWithLatestLogs(context, run.id(), TaskStatus.FAILED, null, category(ex));
            }
            context.publish(() -> { toolRunRepository.finish(toolRunId, TaskStatus.FAILED, null, Map.of("error", ex.safeMessage(), "errorCode", ex.errorCode())); return null; });
            throw ex;
        } catch (SshRuntimeException ex) {
            TaskStatus status = isTimeout(ex) ? TaskStatus.TIMEOUT : TaskStatus.FAILED;
            String category = isTimeout(ex) ? "BUILD_TIMEOUT" : "SSH_CONNECTION_FAILED";
            String errorCode = ex.errorCode() == null ? TaskErrorCode.REMOTE_COMMAND_FAILED : ex.errorCode();
            markFinishedWithLatestLogs(context, run.id(), status, null, category);
            context.publish(() -> { toolRunRepository.finish(toolRunId, status, null, Map.of("error", "构建验证 SSH 执行失败", "errorCode", errorCode)); return null; });
            throw new TaskExecutionException(errorCode, "构建验证 SSH 执行失败", ex);
        }
    }

    private SourceDeployRunView ensureDeployment(BuildValidationTaskInput input, TaskExecutionContext context) {
        SourceDeployRunView deployment = sourceDeployExecutor.ensureDeployed(new SourceDeployExecutionRequest(
                input.projectId(),
                context.task().id(),
                input.sourceDeploymentId(),
                input.sourceRevisionId(),
                input.environmentId(),
                input.environmentConfigVersion(),
                null,
                false,
                input.timeoutSeconds()
        ), context);
        context.publish(() -> { buildValidationRepository.updateSourceDeployment(input.buildRunId(), deployment.id(), deployment.remotePath()); return null; });
        return deployment;
    }

    private EnvironmentView requireEnvironment(BuildValidationTaskInput input) {
        EnvironmentView environment = environmentRepository.findById(input.environmentId())
                .orElseThrow(() -> new TaskExecutionException(TaskErrorCode.RESOURCE_NOT_FOUND, "环境不存在", null));
        if (!environment.projectId().equals(input.projectId())) {
            throw new TaskExecutionException(TaskErrorCode.PROJECT_MISMATCH, "环境不属于当前项目", null);
        }
        if (!environment.enabled()) {
            throw new TaskExecutionException(TaskErrorCode.RESOURCE_DISABLED, "环境已禁用", null);
        }
        return environment;
    }

    private EnvironmentView requireJumpHost(EnvironmentView environment) {
        if (environment.jumpHostEnvironmentId() == null) {
            return null;
        }
        EnvironmentView jumpHost = environmentRepository.findById(environment.jumpHostEnvironmentId())
                .orElseThrow(() -> new TaskExecutionException(TaskErrorCode.RESOURCE_NOT_FOUND, "跳板机环境不存在", null));
        if (!jumpHost.projectId().equals(environment.projectId())) {
            throw new TaskExecutionException(TaskErrorCode.PROJECT_MISMATCH, "跳板机不属于当前项目", null);
        }
        if (!jumpHost.enabled()) {
            throw new TaskExecutionException(TaskErrorCode.RESOURCE_DISABLED, "跳板机已禁用", null);
        }
        if (jumpHost.jumpHostEnvironmentId() != null) {
            throw new TaskExecutionException(TaskErrorCode.UNSUPPORTED_OPERATION, "不支持多级跳板机", null);
        }
        return jumpHost;
    }

    private SshConnectionTarget target(EnvironmentView environment) {
        if (environment.credentialId() == null) {
            throw new TaskExecutionException(TaskErrorCode.INPUT_INVALID, "SSH 凭据引用不存在", null);
        }
        CredentialService.SshRuntimeCredential credential = credentialService.sshRuntimeCredential(environment.credentialId(), environment.projectId());
        return new SshConnectionTarget(
                environment.id(),
                environment.host(),
                environment.sshPort(),
                environment.username(),
                environment.workingDirectory(),
                new SshRuntimeCredential(
                        credential.kind(),
                        credential.username(),
                        credential.password(),
                        credential.privateKey(),
                        credential.passphrase()
                )
        );
    }

    private String buildCommand(String remotePath, Map<String, String> environmentVariables) {
        String scriptPath = ".rvport-build-" + UUID.randomUUID() + ".sh";
        String environmentExports = environmentExports(environmentVariables);
        return """
                set -eu
                WORK_DIR=__WORK_DIR__
                SCRIPT_PATH="$WORK_DIR/__SCRIPT_NAME__"
                ELF_BEFORE="$WORK_DIR/.rvport-elf-before.txt"
                ELF_AFTER="$WORK_DIR/.rvport-elf-after.txt"
                ELF_BEFORE_SORTED="$WORK_DIR/.rvport-elf-before.sorted"
                ELF_AFTER_SORTED="$WORK_DIR/.rvport-elf-after.sorted"
                cleanup() {
                  rm -f "$SCRIPT_PATH" "$ELF_BEFORE" "$ELF_AFTER" "$ELF_BEFORE_SORTED" "$ELF_AFTER_SORTED"
                }
                trap cleanup EXIT INT TERM
                if [ ! -d "$WORK_DIR" ]; then
                  echo "BUILD_WORKING_DIRECTORY_NOT_FOUND" >&2
                  exit 127
                fi
                cat > "$SCRIPT_PATH"
                chmod 700 "$SCRIPT_PATH"
                cd "$WORK_DIR"
                __ENVIRONMENT_EXPORTS__
                collect_build_hints() {
                  find . -maxdepth 5 -path './.git' -prune -o -type f \\( -name 'CMakeLists.txt' -o -name 'Makefile' -o -name '*.spec' -o -name 'configure' -o -name 'meson.build' -o -name 'build.ninja' \\) -print 2>/dev/null | while IFS= read -r HINT_PATH; do
                    HINT_NAME="${HINT_PATH##*/}"
                    case "$HINT_NAME" in
                      CMakeLists.txt) HINT_TYPE="CMAKE" ;;
                      Makefile) HINT_TYPE="MAKE" ;;
                      *.spec) HINT_TYPE="RPM_SPEC" ;;
                      configure) HINT_TYPE="AUTOTOOLS" ;;
                      meson.build) HINT_TYPE="MESON" ;;
                      build.ninja) HINT_TYPE="NINJA" ;;
                      *) HINT_TYPE="UNKNOWN" ;;
                    esac
                    printf 'RVPORT_OBSERVATION buildSystemHint %s\t%s\n' "$HINT_TYPE" "${HINT_PATH#./}"
                  done
                }
                collect_elf_manifest() {
                  OUTPUT="$1"
                  : > "$OUTPUT"
                  if command -v file >/dev/null 2>&1; then
                    find . -path './.git' -prune -o -type f -print | while IFS= read -r FILE_PATH; do
                      FILE_TYPE="$(file -b "$FILE_PATH" 2>/dev/null || true)"
                      case "$FILE_TYPE" in
                        *ELF*) printf '%s\t%s\n' "${FILE_PATH#./}" "$FILE_TYPE" >> "$OUTPUT" ;;
                      esac
                    done
                  else
                    echo "RVPORT_OBSERVATION_WARNING fileUnavailable" >&2
                    find . -path './.git' -prune -o -type f \\( -name '*.so' -o -name '*.so.*' \\) -print | while IFS= read -r FILE_PATH; do
                      printf '%s\t%s\n' "${FILE_PATH#./}" "extension-match" >> "$OUTPUT"
                    done
                  fi
                }
                owner_package_for_path() {
                  OWNER_PATH="$1"
                  OWNER=""
                  if command -v rpm >/dev/null 2>&1; then
                    OWNER="$(rpm -qf "$OWNER_PATH" 2>/dev/null || true)"
                  elif command -v dpkg-query >/dev/null 2>&1; then
                    OWNER_NAME="$(dpkg-query -S "$OWNER_PATH" 2>/dev/null | sed 's/:.*//' | head -n 1 || true)"
                    if [ -n "$OWNER_NAME" ]; then
                      OWNER_VERSION="$(dpkg-query -W -f='${Version}' "$OWNER_NAME" 2>/dev/null || true)"
                      if [ -n "$OWNER_VERSION" ]; then
                        OWNER="$OWNER_NAME-$OWNER_VERSION"
                      else
                        OWNER="$OWNER_NAME"
                      fi
                    fi
                  fi
                  if [ -n "$OWNER" ] && ! printf '%s' "$OWNER" | grep -qi 'not owned'; then
                    printf '%s' "$OWNER"
                  fi
                }
                resolve_needed_library() {
                  REL_PATH="$1"
                  FILE_PATH="$2"
                  NEEDED="$3"
                  RESOLVED_PATH=""
                  RESOLVER=""
                  CANDIDATES=""
                  if command -v ldconfig >/dev/null 2>&1; then
                    CANDIDATES="$(ldconfig -p 2>/dev/null | awk -v n="$NEEDED" '$1 == n { print $NF }' | head -n 5 | paste -sd ',' - || true)"
                    RESOLVED_PATH="$(printf '%s' "$CANDIDATES" | awk -F',' '{ print $1 }')"
                    if [ -n "$RESOLVED_PATH" ]; then
                      RESOLVER="ldconfig"
                    fi
                  fi
                  if [ -z "$RESOLVED_PATH" ]; then
                    CANDIDATES=""
                    for DIR in /lib /lib64 /usr/lib /usr/lib64 /usr/local/lib /usr/local/lib64; do
                      if [ -e "$DIR/$NEEDED" ]; then
                        if [ -z "$CANDIDATES" ]; then
                          CANDIDATES="$DIR/$NEEDED"
                        else
                          CANDIDATES="$CANDIDATES,$DIR/$NEEDED"
                        fi
                        if [ -z "$RESOLVED_PATH" ]; then
                          RESOLVED_PATH="$DIR/$NEEDED"
                        fi
                        RESOLVER="library-path-scan"
                      fi
                    done
                  fi
                  if [ -n "$RESOLVED_PATH" ]; then
                    OWNER="$(owner_package_for_path "$RESOLVED_PATH")"
                    WARNING=""
                    if printf '%s' "$CANDIDATES" | grep -q ','; then
                      WARNING="multiple_candidates"
                    fi
                    printf 'RVPORT_OBSERVATION resolvedNeededLibrary %s\tsoname=%s\tpath=%s\townerPackage=%s\tresolver=%s\tcandidates=%s\twarning=%s\n' "$REL_PATH" "$NEEDED" "$RESOLVED_PATH" "$OWNER" "$RESOLVER" "$CANDIDATES" "$WARNING"
                  else
                    printf 'RVPORT_OBSERVATION resolvedNeededLibrary %s\tsoname=%s\tresolver=unresolved\twarning=library_not_found\n' "$REL_PATH" "$NEEDED"
                  fi
                }
                emit_elf_metadata() {
                  REL_PATH="$1"
                  FILE_PATH="./$REL_PATH"
                  if command -v readelf >/dev/null 2>&1 && [ -f "$FILE_PATH" ]; then
                    readelf -d "$FILE_PATH" 2>/dev/null | sed -n 's/.*Shared library: \\[\\(.*\\)\\].*/\\1/p' | while IFS= read -r NEEDED; do
                      printf 'RVPORT_OBSERVATION elfNeeded %s\t%s\n' "$REL_PATH" "$NEEDED"
                      resolve_needed_library "$REL_PATH" "$FILE_PATH" "$NEEDED"
                    done
                    readelf -d "$FILE_PATH" 2>/dev/null | sed -n 's/.*Library soname: \\[\\(.*\\)\\].*/\\1/p' | while IFS= read -r SONAME; do
                      printf 'RVPORT_OBSERVATION elfSoname %s\t%s\n' "$REL_PATH" "$SONAME"
                    done
                  fi
                  OWNER=""
                  if command -v rpm >/dev/null 2>&1; then
                    OWNER="$(rpm -qf "$FILE_PATH" 2>/dev/null || true)"
                  elif command -v dpkg-query >/dev/null 2>&1; then
                    OWNER="$(dpkg-query -S "$FILE_PATH" 2>/dev/null | sed 's/:.*//' | head -n 1 || true)"
                  fi
                  if [ -n "$OWNER" ] && ! printf '%s' "$OWNER" | grep -qi 'not owned'; then
                    printf 'RVPORT_OBSERVATION ownerPackage %s\t%s\n' "$REL_PATH" "$OWNER"
                  fi
                }
                summarize_cmake_compiler_flag_probes() {
                  if command -v python3 >/dev/null 2>&1; then
                    python3 - <<'PY'
from pathlib import Path

SUPPORTED = {
    "check_c_compiler_flag",
    "check_cxx_compiler_flag",
    "check_compiler_flag",
}
EXCLUDED_DIRS = {
    ".git",
    ".hg",
    ".svn",
    "CMakeFiles",
    "_deps",
    "node_modules",
    "target",
    ".rvport-rpmbuild",
}


def clean(value):
    return " ".join(str(value or "").replace("\\t", " ").replace("\\r", " ").replace("\\n", " ").split())[:300]


def skip_ws_and_comments(text, index):
    cursor = index
    while cursor < len(text):
        if text[cursor].isspace():
            cursor += 1
            continue
        if text[cursor] == "#":
            while cursor < len(text) and text[cursor] != "\\n":
                cursor += 1
            continue
        break
    return cursor


def read_block(text, open_index):
    depth = 0
    quote = None
    out = []
    i = open_index
    while i < len(text):
        c = text[i]
        if quote:
            if c == "\\\\" and i + 1 < len(text):
                out.append(c)
                i += 1
                out.append(text[i])
            elif c == quote:
                quote = None
                out.append(c)
            else:
                out.append(c)
            i += 1
            continue
        if c in ("'", '"'):
            quote = c
            out.append(c)
            i += 1
            continue
        if c == "#":
            while i < len(text) and text[i] != "\\n":
                i += 1
            out.append(" ")
            continue
        if c == "(":
            depth += 1
            if depth > 1:
                out.append(c)
            i += 1
            continue
        if c == ")":
            depth -= 1
            if depth == 0:
                return "".join(out), i + 1
            out.append(c)
            i += 1
            continue
        out.append(c)
        i += 1
    return None, len(text)


def split_args(text):
    args = []
    current = []
    quote = None
    started = False
    i = 0
    while i < len(text):
        c = text[i]
        if quote:
            if c == "\\\\" and i + 1 < len(text):
                i += 1
                current.append(text[i])
                started = True
            elif c == quote:
                quote = None
                started = True
            else:
                current.append(c)
                started = True
            i += 1
            continue
        if c in ("'", '"'):
            quote = c
            started = True
            i += 1
            continue
        if c.isspace():
            if started:
                args.append("".join(current))
                current = []
                started = False
            i += 1
            continue
        current.append(c)
        started = True
        i += 1
    if started:
        args.append("".join(current))
    return args


def probes_from_file(relative_path, text):
    probes = []
    i = 0
    while i < len(text):
        c = text[i]
        if c == "#":
            while i < len(text) and text[i] != "\\n":
                i += 1
            continue
        if not (c.isalpha() or c == "_"):
            i += 1
            continue
        start = i
        i += 1
        while i < len(text) and (text[i].isalnum() or text[i] == "_"):
            i += 1
        function = text[start:i].lower()
        if function not in SUPPORTED:
            continue
        cursor = skip_ws_and_comments(text, i)
        if cursor >= len(text) or text[cursor] != "(":
            continue
        block, end = read_block(text, cursor)
        if block is None:
            probes.append(("", "", "", function, "unbalanced_parentheses"))
            break
        args = split_args(block)
        language, flag, variable, unresolved = "", "", "", ""
        if function == "check_c_compiler_flag" and len(args) >= 2:
            language, flag, variable = "C", args[0], args[1]
        elif function == "check_cxx_compiler_flag" and len(args) >= 2:
            language, flag, variable = "CXX", args[0], args[1]
        elif function == "check_compiler_flag" and len(args) >= 3:
            language, flag, variable = args[0], args[1], args[2]
        else:
            unresolved = "insufficient_arguments"
        if not unresolved and ("${" in flag or "${" in variable):
            unresolved = "variable_expansion"
        probes.append((variable, language, flag, function, unresolved))
        i = end
    probes.extend(expand_foreach_probes(text))
    return probes


def all_calls(text):
    calls = []
    i = 0
    while i < len(text):
        c = text[i]
        if c == "#":
            while i < len(text) and text[i] != "\\n":
                i += 1
            continue
        if not (c.isalpha() or c == "_"):
            i += 1
            continue
        start = i
        i += 1
        while i < len(text) and (text[i].isalnum() or text[i] == "_"):
            i += 1
        function = text[start:i].lower()
        cursor = skip_ws_and_comments(text, i)
        if cursor >= len(text) or text[cursor] != "(":
            continue
        block, end = read_block(text, cursor)
        if block is None:
            break
        calls.append((function, block))
        i = end
    return calls


def variable_ref(value):
    return value.startswith("${") and value.endswith("}")


def variable_name(value):
    return value[2:-1]


def cmake_lists(text):
    values = {}
    for function, block in all_calls(text):
        args = split_args(block)
        if function == "list" and len(args) >= 3 and args[0].lower() == "append":
            values.setdefault(args[1], []).extend(args[2:])
        elif function == "set" and len(args) >= 2:
            values.setdefault(args[0], []).extend(args[1:])
    return values


def apply_regex(value, pattern, replacement):
    import re
    try:
        return re.sub(pattern, replacement, value)
    except Exception:
        return value


def substitute_args(args, variables):
    output = []
    for arg in args:
        value = arg
        for name, replacement in variables.items():
            value = value.replace("${%s}" % name, replacement)
        output.append(value)
    return output


def expand_foreach_probes(text):
    values = cmake_lists(text)
    probes = []
    lower = text.lower()
    i = 0
    while True:
        foreach_index = lower.find("foreach", i)
        if foreach_index < 0:
            break
        open_index = skip_ws_and_comments(text, foreach_index + len("foreach"))
        if open_index >= len(text) or text[open_index] != "(":
            i = foreach_index + len("foreach")
            continue
        header, header_end = read_block(text, open_index)
        if header is None:
            break
        end_index = lower.find("endforeach", header_end)
        if end_index < 0:
            break
        args = split_args(header)
        if len(args) < 2:
            i = end_index + len("endforeach")
            continue
        loop_var = args[0]
        loop_values = []
        for arg in args[1:]:
            if variable_ref(arg):
                loop_values.extend(values.get(variable_name(arg), []))
            else:
                loop_values.append(arg)
        body = text[header_end:end_index]
        derived = {}
        for function, block in all_calls(body):
            parts = split_args(block)
            if function == "string" and len(parts) >= 6 and parts[0].lower() == "regex" and parts[1].lower() == "replace":
                if variable_ref(parts[5]) and variable_name(parts[5]) == loop_var:
                    derived[parts[4]] = (parts[2], parts[3])
        for function, block in all_calls(body):
            if function not in SUPPORTED:
                continue
            parts = split_args(block)
            for loop_value in loop_values:
                variables = {loop_var: loop_value}
                for name, (pattern, replacement) in derived.items():
                    variables[name] = apply_regex(loop_value, pattern, replacement)
                expanded = substitute_args(parts, variables)
                language, flag, variable, unresolved = "", "", "", ""
                if function == "check_c_compiler_flag" and len(expanded) >= 2:
                    language, flag, variable = "C", expanded[0], expanded[1]
                elif function == "check_cxx_compiler_flag" and len(expanded) >= 2:
                    language, flag, variable = "CXX", expanded[0], expanded[1]
                elif function == "check_compiler_flag" and len(expanded) >= 3:
                    language, flag, variable = expanded[0], expanded[1], expanded[2]
                else:
                    unresolved = "insufficient_arguments"
                if not unresolved and ("${" in flag or "${" in variable):
                    unresolved = "variable_expansion"
                probes.append((variable, language, flag, function, unresolved))
        i = end_index + len("endforeach")
    return probes


files = []
for path in Path(".").rglob("*"):
    if len(files) >= 1000:
        print("RVPORT_OBSERVATION_WARNING cmakeCompilerFlagProbeScanLimit limit=1000")
        break
    if not path.is_file():
        continue
    parts = set(path.parts)
    if parts & EXCLUDED_DIRS:
        continue
    if path.name != "CMakeLists.txt" and path.suffix.lower() != ".cmake":
        continue
    try:
        if path.stat().st_size > 1024 * 1024:
            print("RVPORT_OBSERVATION_WARNING cmakeCompilerFlagProbeFileSkipped file=%s reason=size_limit" % clean(path.as_posix()))
            continue
    except OSError:
        continue
    files.append(path)

seen = {}
for path in sorted(files):
    relative = path.as_posix()
    if relative.startswith("./"):
        relative = relative[2:]
    try:
        text = path.read_text(errors="ignore")
    except Exception as exc:
        print("RVPORT_OBSERVATION_WARNING cmakeCompilerFlagProbeFileSkipped file=%s reason=%s" % (clean(relative), clean(type(exc).__name__)))
        continue
    for variable, language, flag, function, unresolved in probes_from_file(relative, text):
        if unresolved:
            print("RVPORT_OBSERVATION_WARNING cmakeCompilerFlagProbeUnresolved file=%s function=%s variable=%s flag=%s reason=%s" % (
                clean(relative), clean(function), clean(variable), clean(flag), clean(unresolved)
            ))
            continue
        key = clean(variable)
        value = (clean(flag), clean(language), clean(relative), clean(function))
        previous = seen.setdefault(key, set())
        previous.add(value)
        print("RVPORT_OBSERVATION cmakeCompilerFlagProbe %s\tlanguage=%s\tflag=%s\tfile=%s\tfunction=%s" % (
            key, value[1], value[0], value[2], value[3]
        ))

for variable, values in sorted(seen.items()):
    flags = sorted({value[0] for value in values})
    if len(flags) > 1:
        print("RVPORT_OBSERVATION_WARNING cmakeCompilerFlagProbeConflict variable=%s\tflags=%s" % (
            clean(variable), ",".join(flags)
        ))
PY
                  else
                    echo "RVPORT_OBSERVATION_WARNING cmakeCompilerFlagProbePythonUnavailable" >&2
                  fi
                }
                summarize_compile_databases() {
                  if [ -f compile_commands.json ]; then
                    if command -v python3 >/dev/null 2>&1; then
                      python3 - <<'PY'
import json, shlex
from pathlib import Path
try:
    data = json.loads(Path("compile_commands.json").read_text(errors="ignore"))
except Exception:
    data = []
commands = data if isinstance(data, list) else []
compilers, includes, defines, lib_paths, link_libs, standards = set(), set(), set(), set(), set(), set()
for item in commands[:1000]:
    command = item.get("command") or " ".join(item.get("arguments") or [])
    try:
        parts = shlex.split(command)
    except Exception:
        parts = command.split()
    if parts:
        compilers.add(Path(parts[0]).name)
    for idx, part in enumerate(parts):
        if part.startswith("-I") and len(part) > 2:
            includes.add(part[2:])
        elif part == "-I" and idx + 1 < len(parts):
            includes.add(parts[idx + 1])
        elif part.startswith("-D") and len(part) > 2:
            defines.add(part[2:].split("=", 1)[0])
        elif part.startswith("-L") and len(part) > 2:
            lib_paths.add(part[2:])
        elif part == "-L" and idx + 1 < len(parts):
            lib_paths.add(parts[idx + 1])
        elif part.startswith("-l") and len(part) > 2:
            link_libs.add(part[2:])
        elif part.startswith("-std="):
            standards.add(part[5:])
print("RVPORT_OBSERVATION compileDatabase compile_commands.json\tcommands=%d\tcompilers=%s\tincludes=%s\tdefines=%s\tlibPaths=%s\tlinkLibs=%s\tstandards=%s" % (
    len(commands), ",".join(sorted(compilers)[:30]), ",".join(sorted(includes)[:80]), ",".join(sorted(defines)[:80]),
    ",".join(sorted(lib_paths)[:80]), ",".join(sorted(link_libs)[:80]), ",".join(sorted(standards)[:20])
))
PY
                    else
                      echo "RVPORT_OBSERVATION compileDatabase compile_commands.json present"
                    fi
                  fi
                  if [ -f CMakeCache.txt ]; then
                    GENERATOR="$(sed -n 's/^CMAKE_GENERATOR:INTERNAL=//p' CMakeCache.txt | head -n 1)"
                    BUILD_TYPE="$(sed -n 's/^CMAKE_BUILD_TYPE:STRING=//p' CMakeCache.txt | head -n 1)"
                    C_COMPILER="$(sed -n 's/^CMAKE_C_COMPILER:FILEPATH=//p' CMakeCache.txt | head -n 1)"
                    CXX_COMPILER="$(sed -n 's/^CMAKE_CXX_COMPILER:FILEPATH=//p' CMakeCache.txt | head -n 1)"
                    printf 'RVPORT_OBSERVATION cmakeCache CMakeCache.txt\tgenerator=%s\tbuildType=%s\tcCompiler=%s\tcxxCompiler=%s\n' "$GENERATOR" "$BUILD_TYPE" "$C_COMPILER" "$CXX_COMPILER"
                  fi
                  if [ -f build.ninja ]; then
                    RULES="$(grep -c '^rule ' build.ninja 2>/dev/null || true)"
                    BUILDS="$(grep -c '^build ' build.ninja 2>/dev/null || true)"
                    COMMANDS="$(sed -n 's/^  command = //p' build.ninja | head -n 20 | tr '\n' '|' | sed 's/|$//')"
                    printf 'RVPORT_OBSERVATION buildNinja build.ninja\trules=%s\tbuilds=%s\tcommands=%s\n' "$RULES" "$BUILDS" "$COMMANDS"
                  fi
                }
                summarize_execve_trace() {
                  if [ -f .rvport-execve.log ]; then
                    if command -v python3 >/dev/null 2>&1; then
                      python3 - <<'PY'
import ast, re
from collections import Counter, defaultdict
from pathlib import Path

text = Path(".rvport-execve.log").read_text(errors="ignore")
pattern = re.compile(r'(?:^|\\n)(?:\\d+\\s+)?execve\\("([^"]+)",\\s*(\\[[^\\n]*?\\]),\\s*(?:0x[0-9a-fA-F]+|NULL)?\\s*/\\*.*?\\*/\\)\\s+=\\s+(-?\\d+)')
tools = Counter()
tool_paths = {}
args_by_tool = defaultdict(lambda: {"includes": set(), "defines": set(), "libPaths": set(), "linkLibs": set(), "standards": set()})
interesting = {"cc","c++","gcc","g++","clang","clang++","ld","ar","as","make","cmake","ninja","pkg-config","rpmbuild","configure","meson"}
total = 0
for match in pattern.finditer(text):
    path, argv_text, result = match.groups()
    if result != "0":
        continue
    total += 1
    try:
        argv = ast.literal_eval(argv_text)
    except Exception:
        argv = [bytes(value, "utf-8").decode("unicode_escape", errors="ignore") for value in re.findall(r'"((?:\\\\.|[^"\\\\])*)"', argv_text)]
    if Path(path).name == "wrapper" and "--execute" in argv:
        idx = argv.index("--execute")
        if idx + 1 < len(argv):
            path = argv[idx + 1]
        if "--" in argv:
            argv = argv[argv.index("--") + 1:]
    tool = Path(path).name
    if tool not in interesting and (not argv or Path(str(argv[0])).name not in interesting):
        continue
    if tool not in interesting and argv:
        tool = Path(str(argv[0])).name
    tools[tool] += 1
    tool_paths.setdefault(tool, path)
    bucket = args_by_tool[tool]
    for idx, arg in enumerate(str(v) for v in argv[1:]):
        if arg.startswith("-I") and len(arg) > 2:
            bucket["includes"].add(arg[2:])
        elif arg == "-I" and idx + 2 < len(argv):
            bucket["includes"].add(str(argv[idx + 2]))
        elif arg.startswith("-D") and len(arg) > 2:
            bucket["defines"].add(arg[2:].split("=", 1)[0])
        elif arg.startswith("-L") and len(arg) > 2:
            bucket["libPaths"].add(arg[2:])
        elif arg == "-L" and idx + 2 < len(argv):
            bucket["libPaths"].add(str(argv[idx + 2]))
        elif arg.startswith("-l") and len(arg) > 2:
            bucket["linkLibs"].add(arg[2:])
        elif arg.startswith("-std="):
            bucket["standards"].add(arg[5:])
print("RVPORT_OBSERVATION execveSummary .rvport-execve.log\texecve=%d\ttools=%s" % (
    total, ",".join("%s:%d" % item for item in tools.most_common(40))
))
for tool, count in tools.most_common(40):
    values = args_by_tool[tool]
    print("RVPORT_OBSERVATION execveTool %s\tcount=%d\tpath=%s\tincludes=%s\tdefines=%s\tlibPaths=%s\tlinkLibs=%s\tstandards=%s" % (
        tool, count, tool_paths.get(tool, ""),
        ",".join(sorted(values["includes"])[:80]),
        ",".join(sorted(values["defines"])[:80]),
        ",".join(sorted(values["libPaths"])[:80]),
        ",".join(sorted(values["linkLibs"])[:80]),
        ",".join(sorted(values["standards"])[:20])
    ))
PY
                    else
                      echo "RVPORT_OBSERVATION execveSummary .rvport-execve.log present"
                    fi
                  fi
                }
                summarize_compiler_builtin_macros() {
                  if command -v python3 >/dev/null 2>&1; then
                    python3 - <<'PY'
import shutil
import subprocess

compilers = [
    ("cc", "c"),
    ("gcc", "c"),
    ("clang", "c"),
    ("c++", "c++"),
    ("g++", "c++"),
    ("clang++", "c++"),
]
emitted = set()
for compiler, language in compilers:
    path = shutil.which(compiler)
    if not path:
        continue
    identity = (path, language)
    if identity in emitted:
        continue
    emitted.add(identity)
    command = [path, "-dM", "-E", "-"]
    if language == "c++":
        command = [path, "-dM", "-E", "-x", "c++", "-"]
    try:
        result = subprocess.run(
            command,
            input=b"",
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            timeout=10,
            check=False,
        )
    except Exception:
        continue
    if result.returncode != 0:
        continue
    macros = set()
    for line in result.stdout.decode(errors="ignore").splitlines():
        if not line.startswith("#define "):
            continue
        value = line[len("#define "):].strip()
        if not value:
            continue
        name = value.split(None, 1)[0].split("(", 1)[0]
        if name:
            macros.add(name)
    ordered = sorted(macros)
    chunk_size = 80
    for index in range(0, len(ordered), chunk_size):
        chunk = ordered[index:index + chunk_size]
        print(
            "RVPORT_OBSERVATION compilerBuiltinMacros %s\tlanguage=%s\tpath=%s\tcount=%d\tchunk=%d\tmacros=%s"
            % (compiler, language, path, len(ordered), index // chunk_size + 1, ",".join(chunk))
        )
PY
                  fi
                }
                collect_build_hints
                collect_elf_manifest "$ELF_BEFORE"
                export PS4='+RVPORT_TRACE ts=$(date +%s) pwd=$PWD line=$LINENO cmd='
                if command -v bear >/dev/null 2>&1; then
                  BEAR_PREFIX="bear --"
                else
                  BEAR_PREFIX=""
                  echo "RVPORT_OBSERVATION_WARNING bearUnavailable" >&2
                fi
                if command -v strace >/dev/null 2>&1; then
                  if strace -f -e execve -o .rvport-strace-probe.log true >/dev/null 2>&1; then
                    STRACE_PREFIX="strace -f -e execve -o .rvport-execve.log"
                  else
                    STRACE_PREFIX=""
                    echo "RVPORT_OBSERVATION_WARNING straceUnavailable reason=probe_failed" >&2
                  fi
                  rm -f .rvport-strace-probe.log
                else
                  STRACE_PREFIX=""
                  echo "RVPORT_OBSERVATION_WARNING straceUnavailable" >&2
                fi
                set +e
                if [ -n "$STRACE_PREFIX" ]; then
                  $STRACE_PREFIX $BEAR_PREFIX bash -x "$SCRIPT_PATH"
                else
                  $BEAR_PREFIX bash -x "$SCRIPT_PATH"
                fi
                STATUS=$?
                set -e
                collect_elf_manifest "$ELF_AFTER"
                sort "$ELF_BEFORE" > "$ELF_BEFORE_SORTED"
                sort "$ELF_AFTER" > "$ELF_AFTER_SORTED"
                cat "$ELF_BEFORE_SORTED" | while IFS= read -r LINE; do
                  echo "RVPORT_OBSERVATION repositoryBundledElf $LINE"
                  REL_PATH="${LINE%%	*}"
                  emit_elf_metadata "$REL_PATH"
                done
                comm -13 "$ELF_BEFORE_SORTED" "$ELF_AFTER_SORTED" | while IFS= read -r LINE; do
                  echo "RVPORT_OBSERVATION buildProducedElf $LINE"
                  REL_PATH="${LINE%%	*}"
                  emit_elf_metadata "$REL_PATH"
                done
                summarize_cmake_compiler_flag_probes
                summarize_execve_trace
                summarize_compile_databases
                summarize_compiler_builtin_macros
                cleanup
                trap - EXIT INT TERM
                exit "$STATUS"
                """
                .replace("__WORK_DIR__", shellQuote(remotePath))
                .replace("__SCRIPT_NAME__", scriptPath)
                .replace("__ENVIRONMENT_EXPORTS__", environmentExports);
    }

    private String environmentExports(Map<String, String> environmentVariables) {
        if (environmentVariables == null || environmentVariables.isEmpty()) {
            return "";
        }
        StringBuilder exports = new StringBuilder();
        environmentVariables.forEach((name, value) ->
                exports.append("export ")
                        .append(name)
                        .append("=")
                        .append(shellQuote(value == null ? "" : value))
                        .append("\n"));
        return exports.toString().stripTrailing();
    }

    private Map<String, Object> summary(BuildValidationTaskInput input, SourceDeployRunView deployment, SshCommandResult result) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("sourceDeploymentId", deployment.id());
        summary.put("sourceRevisionId", input.sourceRevisionId());
        summary.put("environmentId", input.environmentId());
        summary.put("buildScope", input.buildScope() == null ? BuildValidationScope.BOTH.name() : input.buildScope().name());
        summary.put("workingDirectory", deployment.remotePath());
        summary.put("scriptSha256", input.scriptSha256());
        summary.put("scriptLength", input.scriptLength());
        summary.put("durationMs", result.duration().toMillis());
        summary.put("exitCode", result.exitCode());
        return summary;
    }

    private Map<String, Object> buildFacts(
            BuildValidationTaskInput input,
            SourceDeployRunView deployment,
            SshCommandResult result,
            UUID mergedLogId,
            UUID stdoutLogId,
            UUID stderrLogId,
            Map<String, Object> compileDatabase
    ) {
        return buildFacts(input, deployment, result, mergedLogId, stdoutLogId, stderrLogId, compileDatabase,
                cn.rvport.backend.buildvalidation.bitcode.BitcodeSummary
                        .unavailable(BitcodeStatus.NOT_ATTEMPTED, BitcodeReasonCode.LEGACY_RECORD).toMap());
    }

    private Map<String, Object> buildFacts(
            BuildValidationTaskInput input,
            SourceDeployRunView deployment,
            SshCommandResult result,
            UUID mergedLogId,
            UUID stdoutLogId,
            UUID stderrLogId,
            Map<String, Object> compileDatabase,
            Map<String, Object> bitcode
    ) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("schemaVersion", "build-facts.v1");
        facts.put("sourceRevisionId", input.sourceRevisionId());
        facts.put("environmentId", input.environmentId());
        facts.put("sourceDeploymentId", deployment.id());
        facts.put("environmentConfigVersion", input.environmentConfigVersion());
        facts.put("buildScope", input.buildScope() == null ? BuildValidationScope.BOTH.name() : input.buildScope().name());
        facts.put("scriptSha256", input.scriptSha256());
        facts.put("scriptLength", input.scriptLength());
        facts.put("environmentVariableNames", input.environmentVariables() == null
                ? List.of()
                : input.environmentVariables().keySet().stream().sorted().toList());
        facts.put("exitCode", result.exitCode());
        facts.put("durationMs", result.duration().toMillis());
        Map<String, Object> logRefs = new LinkedHashMap<>();
        logRefs.put("mergedLogId", mergedLogId);
        logRefs.put("stdoutLogId", stdoutLogId);
        logRefs.put("stderrLogId", stderrLogId);
        facts.put("logRefs", logRefs);
        facts.put("observedSignals", Map.of(
                "ps4Trace", true,
                "straceExecve", "OPTIONAL",
                "bearCompileCommands", "OPTIONAL",
                "naturalCompileDatabase", List.of("compile_commands.json", "CMakeCache.txt", "build.ninja"),
                "artifactScan", "PENDING_DEPENDENCY_ANALYSIS"
        ));
        facts.put("warnings", List.of("bearUnavailable 和 straceUnavailable 会在构建日志中以 RVPORT_OBSERVATION_WARNING 记录，不阻断构建"));
        facts.put("compileDatabase", compileDatabase == null ? compileDatabaseUnavailable("NOT_COLLECTED") : compileDatabase);
        facts.put("bitcode", bitcode);
        facts.put("workingDirectory", deployment.remotePath());
        return facts;
    }

    private BitcodeGenerationResult collectBitcode(
            BuildValidationTaskInput input,
            SourceDeployRunView deployment,
            EnvironmentView environment,
            EnvironmentView jumpHost,
            TaskExecutionContext context,
            TaskStatus buildStatus,
            Map<String, Object> compileDatabase
    ) {
        if (buildStatus != TaskStatus.SUCCEEDED) {
            return BitcodeGenerationResult.unavailable(BitcodeStatus.NOT_ATTEMPTED, BitcodeReasonCode.BUILD_FAILED);
        }
        if (environment.role() != cn.rvport.backend.environment.EnvironmentRole.SOURCE_X86) {
            return BitcodeGenerationResult.unavailable(BitcodeStatus.UNSUPPORTED, BitcodeReasonCode.NOT_SOURCE_X86);
        }
        if (!Boolean.TRUE.equals(compileDatabase.get("available"))) {
            return BitcodeGenerationResult.unavailable(
                    BitcodeStatus.UNSUPPORTED, BitcodeReasonCode.COMPILE_DATABASE_UNAVAILABLE);
        }
        try {
            return bitcodeArtifactService.generate(new BitcodeGenerationRequest(
                    input.projectId(), context.task().id(), input.buildRunId(), input.sourceRevisionId(),
                    deployment.remotePath(), target(environment), jumpHost == null ? null : target(jumpHost), context
            ));
        } catch (cn.rvport.backend.worker.TaskAttemptLostException ex) {
            throw ex;
        } catch (TaskCancelledException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            context.log(TaskLogType.STDERR,
                    "RVPORT_OBSERVATION_WARNING llvmBitcodeUnavailable reason=INTERNAL_ERROR"
                            + System.lineSeparator());
            return BitcodeGenerationResult.unavailable(BitcodeStatus.FAILED, BitcodeReasonCode.INTERNAL_ERROR);
        }
    }

    private Map<String, Object> collectCompileDatabase(
            BuildValidationTaskInput input,
            SourceDeployRunView deployment,
            EnvironmentView environment,
            EnvironmentView jumpHost,
            TaskExecutionContext context
    ) {
        try {
            SshCommandResult result = sshClientAdapter.execute(new SshCommandRequest(
                    target(environment),
                    jumpHost == null ? null : target(jumpHost),
                    compileDatabaseFetchCommand(deployment.remotePath()),
                    Duration.ofSeconds(30),
                    Duration.ofSeconds(60)
            ));
            if (result.exitCode() != 0) {
                return compileDatabaseWarning(context, "FETCH_FAILED");
            }
            RemoteCompileDatabase remote = parseRemoteCompileDatabase(result.stdout());
            if (!remote.available()) {
                return compileDatabaseUnavailable(remote.reason());
            }
            byte[] content = Base64.getDecoder().decode(remote.base64Content());
            if (content.length > MAX_COMPILE_DATABASE_BYTES) {
                return compileDatabaseWarning(context, "SIZE_LIMIT_EXCEEDED");
            }
            JsonNode json = objectMapper.readTree(content);
            if (!json.isArray()) {
                return compileDatabaseWarning(context, "JSON_TOP_LEVEL_NOT_ARRAY");
            }
            int commandCount = json.size();
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("sourceRevisionId", input.sourceRevisionId());
            summary.put("environmentId", input.environmentId());
            summary.put("buildRunId", input.buildRunId());
            summary.put("buildScope", input.buildScope() == null ? BuildValidationScope.BOTH.name() : input.buildScope().name());
            summary.put("source", "REMOTE_BUILD_WORKDIR");
            summary.put("commandCount", commandCount);
            summary.put("remoteSourceRoot", deployment.remotePath());
            if (analysisSources != null && analysisSnapshots != null) {
                var revision = analysisSources.findById(input.sourceRevisionId())
                        .filter(value -> input.projectId().equals(value.projectId())).orElseThrow();
                var manifest = analysisSnapshots.prepare(revision);
                if (manifest.sourceDigest() == null || manifest.reasonCode() != null)
                    return compileDatabaseWarning(context, "SOURCE_IDENTITY_UNAVAILABLE");
                var identity = cn.rvport.backend.analysis.AnalysisInputPlan.Identity.of("c,cpp", environment.arch(),
                        "riscv64", Map.of(), List.of(), "build-source@1");
                summary.put("sourceDigest", manifest.sourceDigest());
                if (!identity.architectureAssumed()) summary.put("sourceArch", identity.effectiveSourceArch());
            }
            StoredArtifactView artifact = storedArtifactService.store(new StoredArtifactCreateCommand(
                    input.projectId(),
                    context.task().id(),
                    StoredArtifactOwnerType.BUILD_VALIDATION,
                    input.buildRunId(),
                    BuildValidationArtifacts.COMPILE_COMMANDS_JSON,
                    "application/json",
                    content,
                    summary,
                    "json"
            ), context);
            Map<String, Object> value = new LinkedHashMap<>(summary);
            value.put("available", true);
            value.put("storedArtifactId", artifact.id());
            value.put("artifactType", artifact.artifactType());
            value.put("sha256", artifact.sha256());
            value.put("sizeBytes", artifact.sizeBytes());
            return value;
        } catch (cn.rvport.backend.worker.TaskAttemptLostException ex) {
            throw ex;
        } catch (Exception ex) {
            return compileDatabaseWarning(context, "COLLECTION_FAILED", ex);
        }
    }

    private String compileDatabaseFetchCommand(String remotePath) {
        return """
                set -eu
                cd __WORK_DIR__
                if [ ! -f compile_commands.json ]; then
                  echo "RVPORT_COMPILE_DATABASE_STATUS=missing"
                  exit 0
                fi
                SIZE="$(wc -c < compile_commands.json | tr -d ' ')"
                if [ "$SIZE" -gt __MAX_SIZE__ ]; then
                  echo "RVPORT_COMPILE_DATABASE_STATUS=too_large"
                  echo "RVPORT_COMPILE_DATABASE_SIZE=$SIZE"
                  exit 0
                fi
                echo "RVPORT_COMPILE_DATABASE_STATUS=present"
                echo "RVPORT_COMPILE_DATABASE_SIZE=$SIZE"
                echo "RVPORT_COMPILE_DATABASE_BASE64_BEGIN"
                base64 compile_commands.json | tr -d '\\n'
                echo
                echo "RVPORT_COMPILE_DATABASE_BASE64_END"
                """
                .replace("__WORK_DIR__", shellQuote(remotePath))
                .replace("__MAX_SIZE__", Long.toString(MAX_COMPILE_DATABASE_BYTES));
    }

    private RemoteCompileDatabase parseRemoteCompileDatabase(String stdout) {
        String text = stdout == null ? "" : stdout;
        if (text.contains("RVPORT_COMPILE_DATABASE_STATUS=missing")) {
            return RemoteCompileDatabase.unavailable("NOT_AVAILABLE");
        }
        if (text.contains("RVPORT_COMPILE_DATABASE_STATUS=too_large")) {
            return RemoteCompileDatabase.unavailable("SIZE_LIMIT_EXCEEDED");
        }
        if (!text.contains("RVPORT_COMPILE_DATABASE_STATUS=present")) {
            return RemoteCompileDatabase.unavailable("NOT_AVAILABLE");
        }
        String begin = "RVPORT_COMPILE_DATABASE_BASE64_BEGIN";
        String end = "RVPORT_COMPILE_DATABASE_BASE64_END";
        int beginIndex = text.indexOf(begin);
        int endIndex = text.indexOf(end);
        if (beginIndex < 0 || endIndex <= beginIndex) {
            return RemoteCompileDatabase.unavailable("BASE64_MISSING");
        }
        String encoded = text.substring(beginIndex + begin.length(), endIndex)
                .replaceAll("\\s+", "");
        if (encoded.isBlank()) {
            return RemoteCompileDatabase.unavailable("BASE64_EMPTY");
        }
        return new RemoteCompileDatabase(true, null, encoded);
    }

    private Map<String, Object> compileDatabaseUnavailable(String reason) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("available", false);
        value.put("mode", "NOT_AVAILABLE");
        value.put("reason", reason);
        return value;
    }

    private Map<String, Object> compileDatabaseWarning(TaskExecutionContext context, String reason) {
        context.log(TaskLogType.STDERR, "RVPORT_OBSERVATION_WARNING compileDatabaseArtifactUnavailable reason=" + reason + System.lineSeparator());
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("available", false);
        value.put("mode", "FALLBACK");
        value.put("reason", reason);
        return value;
    }

    private Map<String, Object> compileDatabaseWarning(TaskExecutionContext context, String reason, Exception ex) {
        String exceptionName = ex == null ? "unknown" : ex.getClass().getSimpleName();
        String message = ex == null || ex.getMessage() == null
                ? ""
                : ex.getMessage().replace('\r', ' ').replace('\n', ' ');
        context.log(TaskLogType.STDERR, "RVPORT_OBSERVATION_WARNING compileDatabaseArtifactUnavailable reason="
                + reason + " exception=" + exceptionName + " message=" + message + System.lineSeparator());
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("available", false);
        value.put("mode", "FALLBACK");
        value.put("reason", reason);
        return value;
    }

    private record RemoteCompileDatabase(boolean available, String reason, String base64Content) {
        static RemoteCompileDatabase unavailable(String reason) {
            return new RemoteCompileDatabase(false, reason, null);
        }
    }

    private Map<String, Object> archiveSummary(BuildValidationTaskInput input, SourceDeployRunView deployment,
                                               SshCommandResult result, TaskStatus status, String failureCategory,
                                               Map<String, Object> bitcode) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("sourceRevisionId", input.sourceRevisionId());
        summary.put("environmentId", input.environmentId());
        summary.put("sourceDeploymentId", deployment.id());
        summary.put("buildScope", input.buildScope() == null ? BuildValidationScope.BOTH.name() : input.buildScope().name());
        summary.put("status", status.name());
        summary.put("failureCategory", failureCategory);
        summary.put("scriptSha256", input.scriptSha256());
        summary.put("scriptLength", input.scriptLength());
        summary.put("durationMs", result.duration().toMillis());
        summary.put("exitCode", result.exitCode());
        summary.put("bitcode", bitcode);
        return summary;
    }

    private UUID latestLogId(TaskExecutionContext context, TaskLogType type) {
        return taskLogRepository.findLatest(context.task().id(), type)
                .map(cn.rvport.backend.task.TaskLogResponse::id)
                .orElse(null);
    }

    private void markFinishedWithLatestLogs(TaskExecutionContext context, UUID runId, TaskStatus status, Integer exitCode, String failureCategory) {
        context.publish(() -> {
        buildValidationRepository.markFinished(
                runId,
                status,
                exitCode,
                failureCategory,
                latestLogId(context, TaskLogType.COMBINED),
                latestLogId(context, TaskLogType.STDOUT),
                latestLogId(context, TaskLogType.STDERR)
        );
        return null;
        });
    }

    private boolean isAlreadyFinished(UUID runId) {
        return buildValidationRepository.findById(runId)
                .map(run -> run.status() == TaskStatus.SUCCEEDED
                        || run.status() == TaskStatus.FAILED
                        || run.status() == TaskStatus.CANCELLED
                        || run.status() == TaskStatus.TIMEOUT)
                .orElse(false);
    }

    private String category(TaskExecutionException ex) {
        if (ex.errorCode() != null && ex.errorCode().startsWith("SOURCE_DEPLOY")) {
            return "SOURCE_DEPLOYMENT_FAILED";
        }
        if (TaskErrorCode.RESOURCE_NOT_FOUND.equals(ex.errorCode())) {
            return "REMOTE_COMMAND_FAILED";
        }
        return "UNKNOWN";
    }

    private boolean isTimeout(SshRuntimeException ex) {
        String text = String.valueOf(ex.getMessage()).toLowerCase();
        return text.contains("timeout") || text.contains("超时");
    }

    private int effectiveTimeout(Integer timeoutSeconds) {
        return timeoutSeconds == null ? 3600 : Math.max(10, timeoutSeconds);
    }

    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
