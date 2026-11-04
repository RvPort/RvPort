#include "ir_facts.hpp"

#include <filesystem>
#include <fstream>
#include <memory>
#include <set>
#include <string>

#include "gtest/gtest.h"
#include "llvm/AsmParser/Parser.h"
#include "llvm/IR/LLVMContext.h"
#include "llvm/IR/Module.h"
#include "llvm/Support/Error.h"
#include "llvm/Support/SourceMgr.h"

namespace {

fp_scanner::ArtifactIdentity identity() {
  return {std::string(64, 'a'), std::string(64, 'b'), std::string(64, 'c'),
          std::string(64, 'd'), "source-preserving", std::string(64, 'e')};
}

std::unique_ptr<llvm::Module> parse(llvm::LLVMContext &context,
                                    llvm::StringRef text) {
  llvm::SMDiagnostic diagnostic;
  return llvm::parseAssemblyString(text, diagnostic, context);
}

TEST(IrFacts, ReportsLockedVersions) {
  const llvm::json::Object version = fp_scanner::versionJson();
  EXPECT_EQ(*version.getString("llvm_version"), "21.1.8");
  EXPECT_EQ(*version.getString("reader_version"), "1.0");
  EXPECT_EQ(*version.getString("extractor_version"), "1.2");
  EXPECT_EQ(*version.getString("worker_protocol_version"), "1.1");
}

TEST(IrFacts, ExtractsArithmeticComparisonConversionAndCall) {
  llvm::LLVMContext context;
  auto module = parse(context, R"(
    target triple = "x86_64-unknown-linux-gnu"
    declare double @sqrt(double)
    define float @scan(float %a, double %b) {
    entry:
      %sum = fadd fast float %a, 1.000000e+00
      %cmp = fcmp olt float %sum, %a
      %wide = fpext float %sum to double
      %call = call double @sqrt(double %wide)
      %narrow = fptrunc double %call to float
      ret float %narrow
    }
  )");
  ASSERT_NE(module, nullptr);
  auto result = fp_scanner::extractFactsFromModule(*module, identity(), 100);
  ASSERT_TRUE(static_cast<bool>(result)) << llvm::toString(result.takeError());
  const auto *facts = result->getArray("facts");
  ASSERT_NE(facts, nullptr);
  EXPECT_EQ(facts->size(), 5u);
  const auto *first = (*facts)[0].getAsObject();
  ASSERT_NE(first, nullptr);
  EXPECT_EQ(*first->getString("opcode"), "fadd");
  EXPECT_EQ(first->getArray("fast_math_flags")->size(), 7u);
  EXPECT_FALSE(first->getString("predicate").has_value());
  EXPECT_FALSE(first->getObject("reduction_group"));
  EXPECT_EQ(*result->getObject("module")->getString("target_triple"),
            "x86_64-unknown-linux-gnu");
}

TEST(IrFacts, ExcludesIntegerOnlyInstructionsAndDeclarations) {
  llvm::LLVMContext context;
  auto module = parse(context, R"(
    declare float @decl(float)
    define i32 @integer(i32 %a) {
    entry:
      %sum = add i32 %a, 1
      ret i32 %sum
    }
  )");
  ASSERT_NE(module, nullptr);
  auto result = fp_scanner::extractFactsFromModule(*module, identity(), 100);
  ASSERT_TRUE(static_cast<bool>(result)) << llvm::toString(result.takeError());
  EXPECT_TRUE(result->getArray("facts")->empty());
}

TEST(IrFacts, UnrelatedArtifactBytesDoNotChangeLocalEvidenceIdentity) {
  llvm::LLVMContext context;
  auto module = parse(context, R"(
    define double @compute(double %a, double %b) {
      %product = fmul double %a, %b
      %sum = fadd double %product, %a
      ret double %sum
    }
  )");
  ASSERT_NE(module, nullptr);
  auto original = fp_scanner::extractFactsFromModule(*module, identity(), 100);
  auto changed_identity = identity();
  changed_identity.artifact_key = std::string(64, 'f');
  changed_identity.sha256 = std::string(64, '9');
  auto changed = fp_scanner::extractFactsFromModule(*module, changed_identity, 100);
  ASSERT_TRUE(static_cast<bool>(original));
  ASSERT_TRUE(static_cast<bool>(changed));
  for (std::size_t index = 0; index < 2; ++index) {
    EXPECT_EQ(*(*original->getArray("facts"))[index].getAsObject()->getString("fact_id"),
              *(*changed->getArray("facts"))[index].getAsObject()->getString("fact_id"));
  }
  changed_identity.context_id = std::string(64, '8');
  auto different = fp_scanner::extractFactsFromModule(*module, changed_identity, 100);
  ASSERT_TRUE(static_cast<bool>(different));
  EXPECT_NE(*(*original->getArray("facts"))[0].getAsObject()->getString("fact_id"),
            *(*different->getArray("facts"))[0].getAsObject()->getString("fact_id"));
}

TEST(IrFacts, RejectsFactBudgetOverflow) {
  llvm::LLVMContext context;
  auto module = parse(context, R"(
    define float @scan(float %a) {
    entry:
      %sum = fadd float %a, 1.000000e+00
      ret float %sum
    }
  )");
  ASSERT_NE(module, nullptr);
  auto result = fp_scanner::extractFactsFromModule(*module, identity(), 0);
  ASSERT_TRUE(static_cast<bool>(result)) << llvm::toString(result.takeError());
  EXPECT_TRUE(result->getArray("facts")->empty());
  ASSERT_EQ(result->getArray("unknowns")->size(), 1u);
  EXPECT_EQ(*(*result->getArray("unknowns"))[0].getAsObject()->getString("kind"),
            "budget_exceeded");
}

TEST(IrFacts, ExtractsConstrainedMetadataAndIndirectCalls) {
  llvm::LLVMContext context;
  auto module = parse(context, R"(
    declare double @llvm.experimental.constrained.fadd.f64(double, double, metadata, metadata)
    define double @scan(double %a, double %b, ptr %callback) {
    entry:
      %strict = call double @llvm.experimental.constrained.fadd.f64(
          double %a, double %b, metadata !"round.tonearest", metadata !"fpexcept.strict")
      %indirect = call double %callback(double %strict)
      ret double %indirect
    }
  )");
  ASSERT_NE(module, nullptr);
  auto result = fp_scanner::extractFactsFromModule(*module, identity(), 100);
  ASSERT_TRUE(static_cast<bool>(result)) << llvm::toString(result.takeError());
  const auto *facts = result->getArray("facts");
  ASSERT_EQ(facts->size(), 2u);
  const auto *strict = (*facts)[0].getAsObject();
  EXPECT_TRUE(*strict->getBoolean("constrained"));
  EXPECT_EQ(*strict->getString("rounding_mode"), "round.tonearest");
  EXPECT_EQ(*strict->getString("exception_behavior"), "fpexcept.strict");
  const auto *indirect = (*facts)[1].getAsObject();
  EXPECT_FALSE(indirect->getString("callee").has_value());
}

TEST(IrFacts, ReadsTextualIrAndReportsParseFailure) {
  const std::filesystem::path path = "/tmp/fp-ir-worker-test.ll";
  {
    std::ofstream stream(path);
    stream << R"(
      define float @scan(float %a) {
      entry:
        %sum = fadd float %a, 1.000000e+00
        ret float %sum
      }
    )";
  }
  auto result = fp_scanner::extractFacts(path.string(), identity(), 10);
  ASSERT_TRUE(static_cast<bool>(result)) << llvm::toString(result.takeError());
  EXPECT_EQ(result->getArray("facts")->size(), 1u);
  {
    std::ofstream stream(path);
    stream << R"(
      define float @invalid(float %x, i1 %choose) {
      entry:
        br i1 %choose, label %left, label %right
      left:
        %value = fadd float %x, 1.0
        br label %right
      right:
        ret float %value
      }
    )";
  }
  auto verifier_failure = fp_scanner::extractFacts(path.string(), identity(), 10);
  ASSERT_FALSE(static_cast<bool>(verifier_failure));
  EXPECT_NE(llvm::toString(verifier_failure.takeError()).find("module_verifier_failed"),
            std::string::npos);
  {
    std::ofstream stream(path);
    stream << "not llvm ir";
  }
  auto failure = fp_scanner::extractFacts(path.string(), identity(), 10);
  ASSERT_FALSE(static_cast<bool>(failure));
  EXPECT_FALSE(llvm::toString(failure.takeError()).empty());
  std::filesystem::remove(path);
  auto missing = fp_scanner::extractFacts(path.string(), identity(), 10);
  ASSERT_FALSE(static_cast<bool>(missing));
  EXPECT_FALSE(llvm::toString(missing.takeError()).empty());
}

TEST(IrFacts, CoversFloatingOpcodeFamiliesAndVoidCalls) {
  llvm::LLVMContext context;
  auto module = parse(context, R"(
    declare void @sink(double)
    declare void @plain(i32)
    define i32 @scan(float %a, float %b, i32 %signed, i32 %unsigned, i1 %choose) {
    entry:
      %sub = fsub float %a, %b
      %mul = fmul float %sub, %b
      %rem = frem float %mul, %a
      %neg = fneg float %rem
      %sf = sitofp i32 %signed to float
      %uf = uitofp i32 %unsigned to float
      %si = fptosi float %sf to i32
      %ui = fptoui float %uf to i32
      %selected = select i1 %choose, float %neg, float %sf
      %wide = fpext float %selected to double
      call void @sink(double %wide)
      call void @plain(i32 %si)
      %sum = add i32 %si, %ui
      ret i32 %sum
    }
  )");
  ASSERT_NE(module, nullptr);
  auto result = fp_scanner::extractFactsFromModule(*module, identity(), 100);
  ASSERT_TRUE(static_cast<bool>(result)) << llvm::toString(result.takeError());
  const auto *facts = result->getArray("facts");
  EXPECT_EQ(facts->size(), 11u);
  std::set<std::string> opcodes;
  for (const auto &value : *facts) {
    opcodes.emplace(value.getAsObject()->getString("opcode")->str());
  }
  EXPECT_TRUE(opcodes.contains("fsub"));
  EXPECT_TRUE(opcodes.contains("fmul"));
  EXPECT_TRUE(opcodes.contains("frem"));
  EXPECT_TRUE(opcodes.contains("fneg"));
  EXPECT_TRUE(opcodes.contains("select"));
}

TEST(IrFacts, PreservesModuleFlagsAndReportsUnknownIntrinsic) {
  llvm::LLVMContext context;
  auto module = parse(context, R"IR(
    declare double @llvm.future.fp.f64(double)
    define double @future(double %x) {
    entry:
      %r = call double @llvm.future.fp.f64(double %x)
      ret double %r
    }
    !llvm.module.flags = !{!0}
    !0 = !{i32 1, !"wchar_size", i32 4}
  )IR");
  ASSERT_NE(module, nullptr);
  auto result = fp_scanner::extractFactsFromModule(*module, identity(), 10);
  ASSERT_TRUE(static_cast<bool>(result));
  auto *flags = result->getObject("module")->getArray("module_flags");
  ASSERT_NE(flags, nullptr);
  ASSERT_EQ(flags->size(), 1U);
  EXPECT_NE(flags->front().getAsString()->find("wchar_size"), llvm::StringRef::npos);
  auto *unknowns = result->getArray("unknowns");
  ASSERT_NE(unknowns, nullptr);
  ASSERT_EQ(unknowns->size(), 1U);
  EXPECT_EQ(*unknowns->front().getAsObject()->getString("kind"), "unsupported_intrinsic");
}

TEST(IrFacts, ExtractsPhiSelectAndVectorReduction) {
  llvm::LLVMContext context;
  auto module = parse(context, R"IR(
    declare float @llvm.vector.reduce.fadd.v4f32(float, <4 x float>)
    define float @merge(float %left, float %right, <4 x float> %values, i1 %choose) {
    entry:
      br i1 %choose, label %a, label %b
    a:
      br label %join
    b:
      br label %join
    join:
      %value = phi float [ %left, %a ], [ %right, %b ]
      %selected = select i1 %choose, float %value, float %right
      %sum = call float @llvm.vector.reduce.fadd.v4f32(float %selected, <4 x float> %values)
      ret float %sum
    }
    define float @reduce(float %value, i1 %again) {
    entry:
      br label %loop
    loop:
      %acc = phi float [ 0.0, %entry ], [ %updated, %loop ]
      %updated = fadd float %acc, %value
      br i1 %again, label %loop, label %exit
    exit:
      ret float %updated
    }
  )IR");
  ASSERT_NE(module, nullptr);
  auto result = fp_scanner::extractFactsFromModule(*module, identity(), 10);
  ASSERT_TRUE(static_cast<bool>(result));
  auto *facts = result->getArray("facts");
  ASSERT_NE(facts, nullptr);
  ASSERT_EQ(facts->size(), 5U);
  EXPECT_EQ(*(*facts)[0].getAsObject()->getString("opcode"), "phi");
  EXPECT_FALSE((*facts)[0].getAsObject()->getObject("reduction_group"));
  EXPECT_EQ(*(*facts)[1].getAsObject()->getString("opcode"), "select");
  EXPECT_EQ(*(*facts)[2].getAsObject()->getString("opcode"), "call");
  EXPECT_EQ(*(*facts)[2].getAsObject()->getObject("reduction_group")->getString("kind"),
            "vector-reduction");
  EXPECT_EQ(*(*facts)[3].getAsObject()->getObject("reduction_group")->getString("kind"),
            "phi-recurrence");
  EXPECT_FALSE((*facts)[0].getAsObject()->getArray("use_fact_ids")->empty());
  EXPECT_TRUE(result->getArray("unknowns")->empty());
}

TEST(IrFacts, LimitsUseChainEdgesWithStableUnknown) {
  llvm::LLVMContext context;
  auto module = parse(context, R"IR(
    define float @chain(float %value) {
    entry:
      %first = fadd float %value, 1.0
      %second = fmul float %first, 2.0
      ret float %second
    }
  )IR");
  ASSERT_NE(module, nullptr);
  auto result = fp_scanner::extractFactsFromModule(*module, identity(), 10, 0);
  ASSERT_TRUE(static_cast<bool>(result));
  EXPECT_TRUE(result->getArray("facts")->front().getAsObject()
                  ->getArray("use_fact_ids")
                  ->empty());
  ASSERT_EQ(result->getArray("unknowns")->size(), 1U);
  EXPECT_EQ(*result->getArray("unknowns")
                 ->front()
                 .getAsObject()
                 ->getString("kind"),
            "use_chain_budget_exceeded");
  EXPECT_TRUE(*result->getObject("budgets")->getBoolean("use_chain_exhausted"));
}

TEST(IrFacts, PropagatesEnvironmentSummaryAcrossDirectCalls) {
  llvm::LLVMContext context;
  auto module = parse(context, R"IR(
    declare i32 @fesetround(i32)
    define void @mutator() {
    entry:
      %status = call i32 @fesetround(i32 0)
      ret void
    }
    define void @caller() {
    entry:
      call void @mutator()
      ret void
    }
  )IR");
  ASSERT_NE(module, nullptr);
  auto result = fp_scanner::extractFactsFromModule(*module, identity(), 10);
  ASSERT_TRUE(static_cast<bool>(result));
  auto *functions = result->getArray("functions");
  ASSERT_NE(functions, nullptr);
  ASSERT_EQ(functions->size(), 2U);
  const auto *caller = functions->front().getAsObject();
  ASSERT_EQ(*caller->getString("name"), "caller");
  const auto *summary = caller->getObject("environment_summary");
  ASSERT_NE(summary, nullptr);
  ASSERT_EQ(summary->getArray("actions")->size(), 1U);
  EXPECT_EQ(*summary->getArray("actions")->front().getAsString(),
            "changes_rounding");
  EXPECT_FALSE(*summary->getBoolean("unknown_effect"));
  EXPECT_EQ(*summary->getInteger("propagation_depth"), 1);
  EXPECT_EQ(summary->getArray("direct_calls")->size(), 1U);
  EXPECT_EQ(summary->getArray("propagated_callee_ids")->size(), 1U);
  EXPECT_TRUE(summary->getArray("frontier")->empty());
}

TEST(IrFacts, PreservesRecursiveDepthAndCallBudgetFrontiers) {
  llvm::LLVMContext context;
  auto module = parse(context, R"IR(
    define void @a() {
    entry:
      call void @b()
      ret void
    }
    define void @b() {
    entry:
      call void @c()
      ret void
    }
    define void @c() {
    entry:
      call void @c()
      ret void
    }
  )IR");
  ASSERT_NE(module, nullptr);
  auto depth = fp_scanner::extractFactsFromModule(*module, identity(), 10, 1000, 1,
                                                  1000);
  ASSERT_TRUE(static_cast<bool>(depth));
  const auto *depth_summary = depth->getArray("functions")
                                  ->front()
                                  .getAsObject()
                                  ->getObject("environment_summary");
  ASSERT_NE(depth_summary, nullptr);
  EXPECT_TRUE(*depth_summary->getBoolean("unknown_effect"));
  ASSERT_EQ(depth_summary->getArray("frontier")->size(), 1U);
  EXPECT_EQ(*depth_summary->getArray("frontier")
                 ->front()
                 .getAsObject()
                 ->getString("reason"),
            "summary_depth_exceeded");
  EXPECT_TRUE(*depth->getObject("budgets")->getBoolean("summary_depth_exhausted"));

  auto calls = fp_scanner::extractFactsFromModule(*module, identity(), 10, 1000, 3, 0);
  ASSERT_TRUE(static_cast<bool>(calls));
  const auto *call_summary = calls->getArray("functions")
                                 ->front()
                                 .getAsObject()
                                 ->getObject("environment_summary");
  ASSERT_NE(call_summary, nullptr);
  EXPECT_EQ(*call_summary->getArray("frontier")
                 ->front()
                 .getAsObject()
                 ->getString("reason"),
            "summary_call_edge_budget_exceeded");
  EXPECT_TRUE(
      *calls->getObject("budgets")->getBoolean("summary_call_edges_exhausted"));

  auto recursive = fp_scanner::extractFactsFromModule(*module, identity(), 10);
  ASSERT_TRUE(static_cast<bool>(recursive));
  const auto *recursive_summary =
      (*recursive->getArray("functions"))[2].getAsObject()->getObject(
          "environment_summary");
  ASSERT_NE(recursive_summary, nullptr);
  EXPECT_EQ(*recursive_summary->getArray("frontier")
                 ->front()
                 .getAsObject()
                 ->getString("reason"),
            "recursive_call");
}

TEST(IrFacts, ExtractsDebugLocationAndCompleteInlineChain) {
  llvm::LLVMContext context;
  auto module = parse(context, R"IR(
    source_filename = "inline.c"
    define double @outer(double %x) !dbg !5 {
    entry:
      %value = fadd double %x, 1.0, !dbg !10
      ret double %value, !dbg !11
    }
    !llvm.dbg.cu = !{!0}
    !llvm.module.flags = !{!2, !3}
    !0 = distinct !DICompileUnit(language: DW_LANG_C11, file: !1, producer: "clang",
      isOptimized: true, runtimeVersion: 0, emissionKind: FullDebug)
    !1 = !DIFile(filename: "inline.c", directory: "/project")
    !2 = !{i32 7, !"Dwarf Version", i32 5}
    !3 = !{i32 2, !"Debug Info Version", i32 3}
    !5 = distinct !DISubprogram(name: "outer", scope: !1, file: !1, line: 1,
      type: !6, scopeLine: 1, spFlags: DISPFlagDefinition, unit: !0, retainedNodes: !9)
    !6 = !DISubroutineType(types: !7)
    !7 = !{!8, !8}
    !8 = !DIBasicType(name: "double", size: 64, encoding: DW_ATE_float)
    !9 = !{}
    !12 = distinct !DISubprogram(name: "inner", scope: !1, file: !1, line: 2,
      type: !6, scopeLine: 2, spFlags: DISPFlagDefinition, unit: !0, retainedNodes: !9)
    !10 = !DILocation(line: 2, column: 7, scope: !12, inlinedAt: !13)
    !11 = !DILocation(line: 3, column: 10, scope: !5)
    !13 = !DILocation(line: 3, column: 4, scope: !5)
  )IR");
  ASSERT_NE(module, nullptr);
  auto result = fp_scanner::extractFactsFromModule(*module, identity(), 10);
  ASSERT_TRUE(static_cast<bool>(result));
  auto *location = result->getArray("facts")->front().getAsObject()->getObject(
      "debug_location");
  ASSERT_NE(location, nullptr);
  EXPECT_EQ(*location->getString("path"), "/project/inline.c");
  EXPECT_EQ(*location->getString("scope"), "inner");
  auto *chain = location->getArray("inlined_at");
  ASSERT_NE(chain, nullptr);
  ASSERT_EQ(chain->size(), 1U);
  EXPECT_EQ(*chain->front().getAsObject()->getString("scope"), "outer");
}

}  // namespace
