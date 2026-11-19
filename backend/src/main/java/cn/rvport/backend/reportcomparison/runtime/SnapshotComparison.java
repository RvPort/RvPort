package cn.rvport.backend.reportcomparison.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import cn.rvport.backend.report.ReportInputView;
import cn.rvport.backend.reportcomparison.ReportComparisonRepository;

/** 按已持久化的两侧快照匹配问题，保留命中依据及人工排除事实。 */
public final class SnapshotComparison {
    public List<ReportComparisonRepository.DiffItemCreateCommand> compare(
            List<ReportInputView> base, List<ReportInputView> target, boolean confirmed, String missingStatus) {
        var previous = base.stream().map(this::comparableInput)
                .filter(item -> !confirmed || "TO_FIX".equals(value(item, "confirmationStatus"))).toList();
        var current = target.stream().map(this::comparableInput).toList();
        return diff(previous, current, confirmed, missingStatus);
    }

    private ComparableInput comparableInput(ReportInputView input) {
        Map<String, Object> snapshot = input.inputSnapshot() == null ? Map.of() : input.inputSnapshot();
        Map<String, Object> detail = mapValue(snapshot.get("sourceDetail"));
        String sourceType = firstNonBlank(string(snapshot.get("sourceType")), input.inputType().name());
        String ruleId = firstNonBlank(string(snapshot.get("ruleId")), string(detail.get("ruleId")), string(detail.get("componentType")), sourceType);
        String path = firstNonBlank(string(snapshot.get("identityPath")), string(detail.get("filePath")), string(snapshot.get("filePath")), string(snapshot.get("locationLabel")), string(detail.get("sourcePackageName")), string(detail.get("sourceName")));
        String symbol = firstNonBlank(string(snapshot.get("identitySymbol")), string(detail.get("symbol")), string(detail.get("sourcePackageName")), string(detail.get("sourceName")));
        String anchor = firstNonBlank(string(snapshot.get("identityAnchor")), string(detail.get("fingerprint")), string(snapshot.get("fingerprint")), string(detail.get("title")), string(snapshot.get("issueTitle")));
        String fingerprint = firstNonBlank(string(snapshot.get("stableFingerprint")), string(detail.get("fingerprint")), stableFingerprint(sourceType, ruleId, normalizePath(path), normalize(symbol), normalize(anchor)));
        return new ComparableInput(
                input,
                snapshot,
                sourceType,
                ruleId,
                normalizePath(path),
                normalize(symbol),
                normalize(anchor),
                fingerprint
        );
    }

    private List<ReportComparisonRepository.DiffItemCreateCommand> diff(List<ComparableInput> base, List<ComparableInput> target, boolean confirmed, String missingStatus) {
        List<ReportComparisonRepository.DiffItemCreateCommand> result = new ArrayList<>();
        Map<String, ComparableInput> targetByFingerprint = target.stream()
                .filter(item -> !item.fingerprint().isBlank())
                .collect(Collectors.toMap(ComparableInput::fingerprint, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        Set<UUID> matchedTargetIds = new HashSet<>();
        for (ComparableInput oldItem : base) {
            ComparableInput newItem = targetByFingerprint.get(oldItem.fingerprint());
            Match match = null;
            if (newItem != null && !matchedTargetIds.contains(newItem.input().id())) {
                match = new Match(newItem, "HIGH", "EXACT_FINGERPRINT");
            } else {
                match = fuzzyMatch(oldItem, target, matchedTargetIds);
            }
            if (match == null) {
                result.add(command(missingStatus, oldItem, null, null, "UNMATCHED_OLD"));
            } else {
                matchedTargetIds.add(match.item().input().id());
                result.add(command(confirmed && "EXCLUDED".equals(value(match.item(),"confirmationStatus"))?"EXCLUDED":changed(oldItem, match.item()) ? "CHANGED" : "UNCHANGED", oldItem, match.item(), match.confidence(), match.reason()));
            }
        }
        for (ComparableInput newItem : target) {
            if (!matchedTargetIds.contains(newItem.input().id()) && (!confirmed || "TO_FIX".equals(value(newItem,"confirmationStatus")))) {
                result.add(command("NEW", null, newItem, null, "UNMATCHED_NEW"));
            }
        }
        return result;
    }

    private Match fuzzyMatch(ComparableInput oldItem, List<ComparableInput> target, Set<UUID> matchedTargetIds) {
        for (ComparableInput candidate : target) {
            if (matchedTargetIds.contains(candidate.input().id()) || !same(oldItem.sourceType(), candidate.sourceType())) {
                continue;
            }
            if (same(oldItem.ruleId(), candidate.ruleId())
                    && same(oldItem.path(), candidate.path())
                    && same(oldItem.symbol(), candidate.symbol())
                    && same(oldItem.anchor(), candidate.anchor())) {
                return new Match(candidate, "HIGH", "SAME_RULE_PATH_SYMBOL_ANCHOR");
            }
        }
        for (ComparableInput candidate : target) {
            if (matchedTargetIds.contains(candidate.input().id()) || !same(oldItem.sourceType(), candidate.sourceType())) {
                continue;
            }
            if (same(oldItem.ruleId(), candidate.ruleId())
                    && same(oldItem.path(), candidate.path())
                    && same(oldItem.anchor(), candidate.anchor())) {
                return new Match(candidate, "MEDIUM", "SAME_RULE_PATH_ANCHOR");
            }
        }
        for (ComparableInput candidate : target) {
            if (matchedTargetIds.contains(candidate.input().id()) || !same(oldItem.sourceType(), candidate.sourceType())) {
                continue;
            }
            if (same(oldItem.ruleId(), candidate.ruleId()) && same(oldItem.anchor(), candidate.anchor())) {
                return new Match(candidate, "LOW", "SAME_RULE_ANCHOR");
            }
        }
        return null;
    }

    private ReportComparisonRepository.DiffItemCreateCommand command(
            String diffStatus,
            ComparableInput oldItem,
            ComparableInput newItem,
            String matchConfidence,
            String matchReason
    ) {
        ComparableInput display = newItem == null ? oldItem : newItem;
        return new ReportComparisonRepository.DiffItemCreateCommand(
                display.sourceType(),
                oldItem == null ? null : oldItem.input().inputId(),
                newItem == null ? null : newItem.input().inputId(),
                diffStatus,
                firstNonBlank(value(display, "detectedLevel"), value(display, "riskLevel")),
                firstNonBlank(value(display, "issueTitle"), value(display, "title")),
                firstNonBlank(value(display, "locationLabel"), display.path()),
                firstNonBlank(value(display, "recommendationSummary"), value(display, "recommendation")),
                value(display, "confirmationStatus"),
                firstNonBlank(display.fingerprint(), oldItem == null ? null : oldItem.fingerprint(), newItem == null ? null : newItem.fingerprint()),
                matchConfidence,
                matchReason,
                oldItem == null ? null : oldItem.snapshot(),
                newItem == null ? null : newItem.snapshot()
        );
    }

    private boolean changed(ComparableInput oldItem, ComparableInput newItem) {
        return !Objects.equals(value(oldItem, "semanticDigest"), value(newItem, "semanticDigest"))
                || !Objects.equals(value(oldItem, "issueTitle"), value(newItem, "issueTitle"))
                || !Objects.equals(value(oldItem, "detectedLevel"), value(newItem, "detectedLevel"))
                || !Objects.equals(value(oldItem, "recommendationSummary"), value(newItem, "recommendationSummary"))
                || !Objects.equals(oldItem.path(), newItem.path())
                || !Objects.equals(oldItem.symbol(), newItem.symbol())
                || !Objects.equals(oldItem.anchor(), newItem.anchor());
    }

    private boolean same(String left, String right) {
        return !left.isBlank() && Objects.equals(left, right);
    }

    private String value(ComparableInput input, String key) {
        return input == null ? null : string(input.snapshot().get(key));
    }

    private String stableFingerprint(String... parts) {
        String joined = String.join("|", parts);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(joined.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private String normalizePath(String value) {
        return normalize(value).replace('\\', '/');
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return Map.of();
    }

    private record ComparableInput(
            ReportInputView input,
            Map<String, Object> snapshot,
            String sourceType,
            String ruleId,
            String path,
            String symbol,
            String anchor,
            String fingerprint
    ) {
    }

    private record Match(ComparableInput item, String confidence, String reason) {
    }
}
