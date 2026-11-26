package cn.rvport.backend.analysis;

import cn.rvport.backend.buildvalidation.bitcode.BitcodeBundleValidator;
import cn.rvport.backend.buildvalidation.bitcode.BitcodeManifest;
import cn.rvport.backend.buildvalidation.bitcode.BitcodeManifestSchema;
import cn.rvport.backend.buildvalidation.bitcode.BitcodeReasonCode;
import cn.rvport.backend.buildvalidation.bitcode.BitcodeRuntimeException;
import cn.rvport.backend.buildvalidation.bitcode.BitcodeVerificationFailure;
import cn.rvport.backend.buildvalidation.bitcode.BitcodeVerificationStage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

@Component
public class SharedBitcodeArtifactVerifier {

    private final ObjectMapper objectMapper;

    public SharedBitcodeArtifactVerifier(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 校验当前分析计划，而不只校验制品内部自洽性。失败由调用方降级到可信源码/编译上下文。 */
    public static void requireCompatible(BitcodeManifest manifest, AnalysisInputPlan plan,
            CompileEntryClassification classification) {
        String compileHash = plan.artifacts().stream().filter(a -> "COMPILE_COMMANDS_JSON".equals(a.type()))
                .map(AnalysisInputPlan.Artifact::sha256).findFirst().orElse("");
        boolean contextMatches = manifest.compileContext() != null
                && compileHash.equals(manifest.compileContext().rawCompileCommandsSha256());
        if (!contextMatches || !"x86".equals(plan.identity().effectiveSourceArch()))
            throw new SpecializedAnalysisRuntimeException("ANALYSIS_ARTIFACT_IDENTITY_MISMATCH");
        var paths = classification.projectUnits().stream().map(CompileEntryClassification.ProjectUnit::path).toList();
        for (var record : manifest.records()) {
            if (!"produced".equals(record.status())) continue;
            var producer = record.producer();
            var target = record.target();
            String path = record.source().path();
            boolean cpp = !path.toLowerCase(java.util.Locale.ROOT).endsWith(".c");
            if (!paths.contains(path) || !java.util.Objects.equals(producer, manifest.producer())
                    || !"clang".equals(producer.family()) || !"21.1.8".equals(producer.llvmVersion())
                    || !target.triple().startsWith("x86_64-") || !target.triple().contains("linux")
                    || !"x86_64".equals(target.abi().get("architecture"))
                    || !"little".equals(target.abi().get("endianness")) || !target.dataLayout().startsWith("e")
                    || !(cpp ? "c++" : "c").equals(record.language()))
                throw new SpecializedAnalysisRuntimeException("ANALYSIS_ARTIFACT_PROFILE_MISMATCH");
        }
    }

    public BitcodeManifest verify(
            Path manifestFile,
            Path bundleFile,
            int maxRecords,
            long maxBundleBytes,
            long maxEntryBytes
    ) throws IOException {
        try {
            JsonNode rawManifest = objectMapper.readTree(manifestFile.toFile());
            BitcodeManifestSchema.validateRaw(rawManifest);
            BitcodeManifest manifest = objectMapper.treeToValue(rawManifest, BitcodeManifest.class);
            BitcodeBundleValidator.validate(manifest, rawManifest, manifestFile, bundleFile, objectMapper,
                    maxRecords, maxBundleBytes, maxEntryBytes);
            return manifest;
        } catch (BitcodeRuntimeException ex) {
            throw ex;
        } catch (JsonProcessingException ex) {
            throw new BitcodeRuntimeException(new BitcodeVerificationFailure(
                    BitcodeVerificationStage.MANIFEST_PARSE,
                    BitcodeReasonCode.MANIFEST_PARSE_INVALID,
                    null, null, null, null, null, false,
                    "共享 BC manifest 无法解析。", "The shared BC manifest could not be parsed.",
                    "重新获取或重新生成 manifest。", "Fetch or regenerate the manifest."), ex);
        }
    }
}
