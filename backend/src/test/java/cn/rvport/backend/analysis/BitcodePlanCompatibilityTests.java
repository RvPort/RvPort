package cn.rvport.backend.analysis;

import static org.assertj.core.api.Assertions.*;
import cn.rvport.backend.buildvalidation.bitcode.BitcodeManifest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;

class BitcodePlanCompatibilityTests {
    private final ObjectMapper json = new ObjectMapper();
    private final UUID project=UUID.randomUUID(), source=UUID.randomUUID(), build=UUID.randomUUID();
    private AnalysisInputPlan plan(String arch) {
        return new AnalysisInputPlan(1,project,source,"a".repeat(64),"FLOATING_POINT","0.6.0","profile",
                AnalysisInputPlan.Identity.of("c,cpp",arch,"riscv64",Map.of(),List.of(),"r1"),
                AnalysisInputPlan.InputOrigin.BUILD_ARTIFACTS,
                new AnalysisInputPlan.BuildBinding(build,project,source,UUID.randomUUID(),1,arch,"clang","target","abi"),
                List.of(new AnalysisInputPlan.Artifact(UUID.randomUUID(),build,"COMPILE_COMMANDS_JSON","b".repeat(64))),
                List.of(),List.of(),null);
    }
    private ObjectNode manifest() throws Exception {
        ObjectNode node=(ObjectNode)json.readTree("""
            {"producer":{"family":"clang","llvm_version":"21.1.8"},
             "records":[{"status":"produced","source":{"path":"main.c"},"language":"c",
               "producer":{"family":"clang","llvm_version":"21.1.8"},
               "target":{"triple":"x86_64-unknown-linux-gnu","data_layout":"e-m:e",
                 "abi":{"architecture":"x86_64","endianness":"little"}}}]}
            """);
        node.putObject("compile_context").put("raw_compile_commands_sha256","b".repeat(64));
        return node;
    }
    private void validate(ObjectNode node,String arch) throws Exception {
        SharedBitcodeArtifactVerifier.requireCompatible(json.treeToValue(node,BitcodeManifest.class),plan(arch),
                new CompileEntryClassification(2,2,2,List.of(new CompileEntryClassification.ProjectUnit("main.c",""),
                        new CompileEntryClassification.ProjectUnit("main.cpp","")),Map.of()));
    }
    @Test void acceptsOnlyCurrentCompileContextAndMatchingProducedProfiles() throws Exception {
        validate(manifest(),"x86");
        var cpp=manifest(); ((ObjectNode)cpp.at("/records/0/source")).put("path","main.cpp");
        ((ObjectNode)cpp.at("/records/0")).put("language","c++"); validate(cpp,"x86");
        var failed=manifest(); ((ObjectNode)failed.at("/records/0")).put("status","failed"); validate(failed,"x86");
        assertThatThrownBy(()->validate(manifest(),"arm64")).hasMessage("ANALYSIS_ARTIFACT_IDENTITY_MISMATCH");
        var missing=manifest(); missing.remove("compile_context");
        assertThatThrownBy(()->validate(missing,"x86")).hasMessage("ANALYSIS_ARTIFACT_IDENTITY_MISMATCH");
        var wrong=manifest(); ((ObjectNode)wrong.get("compile_context")).put("raw_compile_commands_sha256","c".repeat(64));
        assertThatThrownBy(()->validate(wrong,"x86")).hasMessage("ANALYSIS_ARTIFACT_IDENTITY_MISMATCH");
        for (String[] change:List.of(new String[]{"/records/0/source","path","outside.c"},
                new String[]{"/records/0/producer","family","gcc"},new String[]{"/records/0/target","triple","aarch64-linux-gnu"},
                new String[]{"/records/0/target","triple","x86_64-windows-msvc"},
                new String[]{"/records/0/target/abi","architecture","arm64"},
                new String[]{"/records/0/target/abi","endianness","big"},
                new String[]{"/records/0/target","data_layout","E-m:e"},new String[]{"/records/0","language","c++"})) {
            var changed=manifest(); ((ObjectNode)changed.at(change[0])).put(change[1],change[2]);
            assertThatThrownBy(()->validate(changed,"x86")).hasMessage("ANALYSIS_ARTIFACT_PROFILE_MISMATCH");
        }
        for (String[] producer:List.of(new String[]{"family","gcc"},new String[]{"llvm_version","18.0"})) {
            var changed=manifest(); ((ObjectNode)changed.get("producer")).put(producer[0],producer[1]);
            ((ObjectNode)changed.at("/records/0/producer")).put(producer[0],producer[1]);
            assertThatThrownBy(()->validate(changed,"x86")).hasMessage("ANALYSIS_ARTIFACT_PROFILE_MISMATCH");
        }
    }
}
