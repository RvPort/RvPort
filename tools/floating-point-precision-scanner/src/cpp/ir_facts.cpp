#include "ir_facts.hpp"

#include <algorithm>
#include <array>
#include <cstdint>
#include <functional>
#include <set>
#include <string>
#include <unordered_map>
#include <vector>

#include "llvm/ADT/SmallString.h"
#include "llvm/ADT/SmallVector.h"
#include "llvm/IR/Attributes.h"
#include "llvm/IR/DebugInfoMetadata.h"
#include "llvm/IR/DerivedTypes.h"
#include "llvm/IR/Function.h"
#include "llvm/IR/InstrTypes.h"
#include "llvm/IR/Instructions.h"
#include "llvm/IR/IntrinsicInst.h"
#include "llvm/IR/Metadata.h"
#include "llvm/IR/Operator.h"
#include "llvm/IR/Type.h"
#include "llvm/IR/Verifier.h"
#include "llvm/IRReader/IRReader.h"
#include "llvm/Support/Error.h"
#include "llvm/Support/JSON.h"
#include "llvm/Support/MemoryBuffer.h"
#include "llvm/Support/Path.h"
#include "llvm/Support/SHA256.h"
#include "llvm/Support/SourceMgr.h"
#include "llvm/Support/raw_ostream.h"

namespace fp_scanner {
namespace {

std::string digest(llvm::StringRef value) {
  const auto bytes = llvm::ArrayRef<std::uint8_t>(
      reinterpret_cast<const std::uint8_t *>(value.data()), value.size());
  const std::array<std::uint8_t, 32> hash = llvm::SHA256::hash(bytes);
  static constexpr char hex[] = "0123456789abcdef";
  std::string result;
  result.reserve(64);
  for (const auto byte : hash) {
    result.push_back(hex[byte >> 4]);
    result.push_back(hex[byte & 0x0f]);
  }
  return result;
}

std::string typeName(const llvm::Type *type) {
  std::string text;
  llvm::raw_string_ostream stream(text);
  type->print(stream);
  return text;
}

bool isFloatingInstruction(const llvm::Instruction &instruction) {
  switch (instruction.getOpcode()) {
    case llvm::Instruction::FAdd:
    case llvm::Instruction::FSub:
    case llvm::Instruction::FMul:
    case llvm::Instruction::FDiv:
    case llvm::Instruction::FRem:
    case llvm::Instruction::FNeg:
    case llvm::Instruction::FCmp:
    case llvm::Instruction::FPTrunc:
    case llvm::Instruction::FPExt:
    case llvm::Instruction::SIToFP:
    case llvm::Instruction::UIToFP:
    case llvm::Instruction::FPToSI:
    case llvm::Instruction::FPToUI:
      return true;
    case llvm::Instruction::PHI:
    case llvm::Instruction::Select:
    case llvm::Instruction::Call:
    case llvm::Instruction::Invoke:
    case llvm::Instruction::CallBr:
      if (instruction.getType()->isFPOrFPVectorTy()) return true;
      for (const llvm::Use &operand : instruction.operands()) {
        if (operand->getType()->isFPOrFPVectorTy()) return true;
      }
      return false;
    default:
      return false;
  }
}

llvm::json::Array fastMathFlags(const llvm::Instruction &instruction) {
  llvm::json::Array result;
  const auto *operator_value = llvm::dyn_cast<llvm::FPMathOperator>(&instruction);
  if (operator_value == nullptr) return result;
  const llvm::FastMathFlags flags = operator_value->getFastMathFlags();
  if (flags.allowReassoc()) result.emplace_back("reassoc");
  if (flags.noNaNs()) result.emplace_back("nnan");
  if (flags.noInfs()) result.emplace_back("ninf");
  if (flags.noSignedZeros()) result.emplace_back("nsz");
  if (flags.allowReciprocal()) result.emplace_back("arcp");
  if (flags.allowContract()) result.emplace_back("contract");
  if (flags.approxFunc()) result.emplace_back("afn");
  return result;
}

llvm::json::Object debugPoint(const llvm::DILocation &location) {
  llvm::SmallString<256> path(location.getDirectory());
  llvm::sys::path::append(path, location.getFilename());
  const llvm::DIScope *scope = location.getScope();
  return llvm::json::Object{
      {"path", path.str().str()},
      {"line", static_cast<std::int64_t>(location.getLine())},
      {"column", static_cast<std::int64_t>(location.getColumn())},
      {"scope", scope->getName().str()}};
}

llvm::json::Value debugLocation(const llvm::Instruction &instruction) {
  const llvm::DebugLoc &debug = instruction.getDebugLoc();
  if (!debug) return nullptr;
  const llvm::DILocation *location = debug.get();
  llvm::json::Object result = debugPoint(*location);
  llvm::json::Array inline_chain;
  for (const llvm::DILocation *parent = location->getInlinedAt(); parent != nullptr;
       parent = parent->getInlinedAt()) {
    inline_chain.emplace_back(debugPoint(*parent));
  }
  result["inlined_at"] = std::move(inline_chain);
  return result;
}

llvm::json::Value nullableString(const std::string &value) {
  if (value.empty()) return nullptr;
  return value;
}

std::string metadataString(const llvm::Value *value) {
  const auto *metadata_value = llvm::cast<llvm::MetadataAsValue>(value);
  const auto *text = llvm::cast<llvm::MDString>(metadata_value->getMetadata());
  return text->getString().str();
}

llvm::json::Array attributes(const llvm::AttributeSet &set) {
  llvm::json::Array result;
  for (const llvm::Attribute &attribute : set) {
    result.emplace_back(attribute.getAsString());
  }
  return result;
}

llvm::json::Array moduleFlags(const llvm::Module &module) {
  llvm::SmallVector<llvm::Module::ModuleFlagEntry, 8> entries;
  module.getModuleFlagsMetadata(entries);
  llvm::json::Array result;
  for (const auto &entry : entries) {
    std::string value;
    llvm::raw_string_ostream stream(value);
    entry.Val->print(stream);
    result.emplace_back(entry.Key->getString().str() + "=" + value);
  }
  return result;
}

bool isSupportedFloatingIntrinsic(llvm::StringRef name) {
  constexpr llvm::StringLiteral prefixes[] = {
      "llvm.fma.",       "llvm.fmuladd.",   "llvm.experimental.constrained.",
      "llvm.vector.reduce.", "llvm.sqrt.",  "llvm.sin.",
      "llvm.cos.",       "llvm.exp.",       "llvm.log.",
      "llvm.pow.",       "llvm.fabs.",      "llvm.copysign.",
      "llvm.minnum.",    "llvm.maxnum.",    "llvm.minimum.",
      "llvm.maximum.",   "llvm.floor.",     "llvm.ceil.",
      "llvm.trunc.",     "llvm.rint.",      "llvm.nearbyint.",
      "llvm.round.",     "llvm.roundeven.", "llvm.canonicalize.",
  };
  for (const llvm::StringRef prefix : prefixes) {
    if (name.starts_with(prefix)) return true;
  }
  return false;
}

bool isReductionPhi(const llvm::PHINode &phi) {
  for (const llvm::Value *incoming : phi.incoming_values()) {
    const auto *update = llvm::dyn_cast<llvm::Instruction>(incoming);
    if (update == nullptr) continue;
    if (update->getOpcode() != llvm::Instruction::FAdd &&
        update->getOpcode() != llvm::Instruction::FSub &&
        update->getOpcode() != llvm::Instruction::FMul) {
      continue;
    }
    for (const llvm::Use &operand : update->operands()) {
      if (operand.get() == &phi) return true;
    }
  }
  return false;
}

struct EnvironmentCallEdge {
  const llvm::Function *callee;
  std::string call_site_id;
};

struct LocalEnvironmentSummary {
  std::set<std::string> actions;
  bool unknown_effect = false;
  std::vector<EnvironmentCallEdge> calls;
};

struct EnvironmentFrontier {
  std::string reason;
  std::string callee_function_id;
  std::string call_site_id;
  std::size_t depth;
};

struct PropagatedEnvironmentSummary {
  std::set<std::string> actions;
  std::set<std::string> propagated_callee_ids;
  std::vector<EnvironmentFrontier> frontier;
  bool unknown_effect = false;
  std::size_t propagation_depth = 0;
};

std::string functionId(const ArtifactIdentity &identity,
                       const llvm::Function &function) {
  return digest(identity.context_id + "\n" + function.getName().str());
}

std::string environmentAction(llvm::StringRef name) {
  if (name == "fesetround") return "changes_rounding";
  if (name == "fegetround") return "reads_environment";
  if (name == "fetestexcept") return "tests_exceptions";
  if (name == "feclearexcept") return "clears_exceptions";
  if (name == "fegetenv" || name == "feholdexcept") return "saves_environment";
  if (name == "fesetenv" || name == "feupdateenv") return "restores_environment";
  if (name.starts_with("llvm.experimental.constrained.")) return "constrained_fp";
  return {};
}

llvm::json::Array stringArray(const std::set<std::string> &values) {
  llvm::json::Array result;
  for (const std::string &value : values) result.emplace_back(value);
  return result;
}

}  // namespace

llvm::json::Object versionJson() {
  return llvm::json::Object{{"tool_version", kToolVersion.str()},
                            {"llvm_version", kLlvmVersion.str()},
                            {"reader_version", kReaderVersion.str()},
                            {"extractor_version", kExtractorVersion.str()},
                            {"worker_protocol_version", kWorkerProtocolVersion.str()}};
}

llvm::Expected<llvm::json::Object> extractFactsFromModule(
    const llvm::Module &module, const ArtifactIdentity &identity,
    std::size_t max_facts, std::size_t max_use_edges,
    std::size_t max_summary_depth, std::size_t max_summary_call_edges) {
  llvm::json::Array facts;
  llvm::json::Array functions;
  llvm::json::Array unknowns;
  std::string digest_material = identity.artifact_key;
  bool exhausted = false;
  bool use_chain_exhausted = false;
  bool use_chain_unknown_reported = false;
  std::size_t use_edges_emitted = 0;
  llvm::json::Value frontier = nullptr;
  std::unordered_map<const llvm::Instruction *, std::string> fact_ids;
  std::vector<const llvm::Function *> ordered_functions;
  for (const llvm::Function &function : module) {
    if (function.isDeclaration()) continue;
    ordered_functions.push_back(&function);
    std::size_t block_index = 0;
    for (const llvm::BasicBlock &block : function) {
      const std::string block_name = block.hasName()
                                         ? block.getName().str()
                                         : std::to_string(block_index);
      std::size_t instruction_index = 0;
      for (const llvm::Instruction &instruction : block) {
        if (isFloatingInstruction(instruction)) {
          std::string instruction_text;
          llvm::raw_string_ostream instruction_stream(instruction_text);
          instruction.print(instruction_stream);
          // 元数据编号和整个 BC 摘要不属于当前指令的语义身份。
          const auto metadata_start = instruction_text.find(", !");
          if (metadata_start != std::string::npos) instruction_text.erase(metadata_start);
          fact_ids.emplace(
              &instruction,
              digest(identity.context_id + "\n" + identity.profile + "\n" +
                     function.getName().str() +
                     "\n" + block_name + "\n" +
                     std::to_string(instruction_index) + "\n" +
                     std::string(instruction.getOpcodeName()) + "\n" +
                     instruction_text));
        }
        ++instruction_index;
      }
      ++block_index;
    }
  }
  std::sort(ordered_functions.begin(), ordered_functions.end(),
            [](const llvm::Function *left, const llvm::Function *right) {
              return left->getName() < right->getName();
            });
  std::unordered_map<const llvm::Function *, LocalEnvironmentSummary>
      local_environment;
  for (const llvm::Function *function : ordered_functions) {
    LocalEnvironmentSummary summary;
    std::size_t block_index = 0;
    for (const llvm::BasicBlock &block : *function) {
      const std::string block_name = block.hasName()
                                         ? block.getName().str()
                                         : std::to_string(block_index);
      std::size_t instruction_index = 0;
      for (const llvm::Instruction &instruction : block) {
        const auto *call = llvm::dyn_cast<llvm::CallBase>(&instruction);
        if (call == nullptr) {
          ++instruction_index;
          continue;
        }
        const llvm::Function *target = call->getCalledFunction();
        if (target == nullptr) {
          summary.unknown_effect = true;
          ++instruction_index;
          continue;
        }
        const std::string action = environmentAction(target->getName());
        if (!action.empty()) summary.actions.insert(action);
        if (!target->isDeclaration()) {
          summary.calls.push_back(EnvironmentCallEdge{
              target,
              digest(identity.context_id + "\n" + function->getName().str() +
                     "\n" + block_name + "\n" +
                     std::to_string(instruction_index) + "\n" +
                     target->getName().str()),
          });
        } else if (action.empty() && !target->getName().starts_with("llvm.")) {
          summary.unknown_effect = true;
        }
        ++instruction_index;
      }
      ++block_index;
    }
    std::sort(summary.calls.begin(), summary.calls.end(),
              [](const EnvironmentCallEdge &left,
                 const EnvironmentCallEdge &right) {
                if (left.callee->getName() != right.callee->getName())
                  return left.callee->getName() < right.callee->getName();
                return left.call_site_id < right.call_site_id;
              });
    local_environment.emplace(function, std::move(summary));
  }
  std::size_t summary_call_edges_emitted = 0;
  bool summary_call_edges_exhausted = false;
  bool summary_depth_exhausted = false;
  for (const llvm::Function *function_ptr : ordered_functions) {
    const llvm::Function &function = *function_ptr;
    const std::string function_name = function.getName().str();
    const std::string function_id = functionId(identity, function);
    std::size_t root_edges = 0;
    std::set<const llvm::Function *> active{function_ptr};
    std::function<PropagatedEnvironmentSummary(const llvm::Function *, std::size_t)>
        propagate = [&](const llvm::Function *current,
                        std::size_t depth) -> PropagatedEnvironmentSummary {
      const LocalEnvironmentSummary &local = local_environment.at(current);
      PropagatedEnvironmentSummary result;
      result.actions = local.actions;
      result.unknown_effect = local.unknown_effect;
      for (const EnvironmentCallEdge &edge : local.calls) {
        const std::string callee_id = functionId(identity, *edge.callee);
        std::string reason;
        if (active.contains(edge.callee)) {
          reason = "recursive_call";
        } else if (depth >= max_summary_depth) {
          reason = "summary_depth_exceeded";
          summary_depth_exhausted = true;
        } else if (root_edges >= max_summary_call_edges) {
          reason = "summary_call_edge_budget_exceeded";
          summary_call_edges_exhausted = true;
        }
        if (!reason.empty()) {
          result.unknown_effect = true;
          result.frontier.push_back(EnvironmentFrontier{
              reason, callee_id, edge.call_site_id, depth + 1});
          continue;
        }
        ++root_edges;
        active.insert(edge.callee);
        PropagatedEnvironmentSummary nested = propagate(edge.callee, depth + 1);
        active.erase(edge.callee);
        result.actions.insert(nested.actions.begin(), nested.actions.end());
        result.propagated_callee_ids.insert(callee_id);
        result.propagated_callee_ids.insert(nested.propagated_callee_ids.begin(),
                                            nested.propagated_callee_ids.end());
        result.frontier.insert(result.frontier.end(), nested.frontier.begin(),
                               nested.frontier.end());
        result.unknown_effect = result.unknown_effect || nested.unknown_effect;
        result.propagation_depth =
            std::max(result.propagation_depth, nested.propagation_depth + 1);
      }
      return result;
    };
    PropagatedEnvironmentSummary propagated = propagate(function_ptr, 0);
    summary_call_edges_emitted =
        std::max(summary_call_edges_emitted, root_edges);
    llvm::json::Array direct_calls;
    for (const EnvironmentCallEdge &edge : local_environment.at(function_ptr).calls) {
      direct_calls.emplace_back(llvm::json::Object{
          {"callee_function_id", functionId(identity, *edge.callee)},
          {"call_site_id", edge.call_site_id},
      });
    }
    llvm::json::Array summary_frontier;
    for (const EnvironmentFrontier &item : propagated.frontier) {
      summary_frontier.emplace_back(llvm::json::Object{
          {"reason", item.reason},
          {"callee_function_id", item.callee_function_id},
          {"call_site_id", item.call_site_id},
          {"depth", static_cast<std::int64_t>(item.depth)},
      });
    }
    functions.emplace_back(llvm::json::Object{
        {"function_id", function_id},
        {"name", function_name},
        {"attributes", attributes(function.getAttributes().getFnAttrs())},
        {"environment_summary",
         llvm::json::Object{
             {"actions", stringArray(propagated.actions)},
             {"unknown_effect", propagated.unknown_effect},
             {"direct_calls", std::move(direct_calls)},
             {"propagated_callee_ids",
              stringArray(propagated.propagated_callee_ids)},
             {"propagation_depth",
              static_cast<std::int64_t>(propagated.propagation_depth)},
             {"frontier", std::move(summary_frontier)},
         }},
    });
    std::size_t block_index = 0;
    for (const llvm::BasicBlock &block : function) {
      const std::string block_name = block.hasName()
                                         ? block.getName().str()
                                         : std::to_string(block_index);
      std::size_t instruction_index = 0;
      for (const llvm::Instruction &instruction : block) {
        if (!isFloatingInstruction(instruction)) {
          ++instruction_index;
          continue;
        }
        if (facts.size() >= max_facts) {
          unknowns.emplace_back(llvm::json::Object{
              {"kind", "budget_exceeded"},
              {"function_id", function_id},
              {"basic_block", block_name},
              {"instruction_index", static_cast<std::int64_t>(instruction_index)},
              {"blocking", true},
          });
          frontier = llvm::json::Object{
              {"function_id", function_id},
              {"basic_block", block_name},
              {"instruction_index", static_cast<std::int64_t>(instruction_index)},
          };
          exhausted = true;
          break;
        }
        llvm::json::Array operand_types;
        llvm::json::Array operand_fact_ids;
        for (const llvm::Use &operand : instruction.operands()) {
          operand_types.emplace_back(typeName(operand->getType()));
          const auto *operand_instruction = llvm::dyn_cast<llvm::Instruction>(operand.get());
          if (operand_instruction != nullptr && fact_ids.contains(operand_instruction) &&
              use_edges_emitted < max_use_edges) {
            operand_fact_ids.emplace_back(fact_ids.at(operand_instruction));
            ++use_edges_emitted;
          } else if (operand_instruction != nullptr &&
                     fact_ids.contains(operand_instruction)) {
            use_chain_exhausted = true;
          }
        }
        llvm::json::Array use_fact_ids;
        for (const llvm::User *user : instruction.users()) {
          const auto *user_instruction = llvm::dyn_cast<llvm::Instruction>(user);
          if (user_instruction != nullptr && fact_ids.contains(user_instruction) &&
              use_edges_emitted < max_use_edges) {
            use_fact_ids.emplace_back(fact_ids.at(user_instruction));
            ++use_edges_emitted;
          } else if (user_instruction != nullptr && fact_ids.contains(user_instruction)) {
            use_chain_exhausted = true;
          }
        }
        std::string callee;
        std::int64_t intrinsic_id = -1;
        bool constrained = false;
        llvm::json::Array instruction_attributes;
        std::string rounding_mode;
        std::string exception_behavior;
        bool unsupported_intrinsic = false;
        if (const auto *call = llvm::dyn_cast<llvm::CallBase>(&instruction)) {
          instruction_attributes = attributes(call->getAttributes().getFnAttrs());
          if (const llvm::Function *target = call->getCalledFunction()) {
            callee = target->getName().str();
            intrinsic_id = static_cast<std::int64_t>(target->getIntrinsicID());
            constrained = target->getName().starts_with("llvm.experimental.constrained.");
            unsupported_intrinsic = target->getName().starts_with("llvm.") &&
                                    !isSupportedFloatingIntrinsic(target->getName());
            if (constrained && call->arg_size() >= 2) {
              rounding_mode = metadataString(call->getArgOperand(call->arg_size() - 2));
              exception_behavior = metadataString(call->getArgOperand(call->arg_size() - 1));
            }
          }
        }
        const std::string fact_id = fact_ids.at(&instruction);
        digest_material += "\n" + fact_id;
        llvm::json::Value predicate = nullptr;
        if (const auto *comparison = llvm::dyn_cast<llvm::FCmpInst>(&instruction)) {
          predicate = comparison->getPredicateName(comparison->getPredicate()).str();
        }
        llvm::json::Value reduction_group = nullptr;
        if (const auto *phi = llvm::dyn_cast<llvm::PHINode>(&instruction);
            phi != nullptr && isReductionPhi(*phi)) {
          reduction_group = llvm::json::Object{{"kind", "phi-recurrence"},
                                                {"group_id", digest(fact_id + "\nphi")}};
        } else if (callee.starts_with("llvm.vector.reduce.")) {
          reduction_group = llvm::json::Object{{"kind", "vector-reduction"},
                                                {"group_id", digest(fact_id + "\nvector")}};
        }
        llvm::json::Object fact{
            {"fact_id", fact_id},
            {"function_id", function_id},
            {"function", function_name},
            {"basic_block", block_name},
            {"instruction_index", static_cast<std::int64_t>(instruction_index)},
            {"opcode", instruction.getOpcodeName()},
            {"result_type", typeName(instruction.getType())},
            {"operand_types", std::move(operand_types)},
            {"operand_fact_ids", std::move(operand_fact_ids)},
            {"use_fact_ids", std::move(use_fact_ids)},
            {"predicate", std::move(predicate)},
            {"reduction_group", std::move(reduction_group)},
            {"fast_math_flags", fastMathFlags(instruction)},
            {"instruction_attributes", std::move(instruction_attributes)},
            {"callee", nullableString(callee)},
            {"intrinsic_id", intrinsic_id < 0 ? llvm::json::Value(nullptr)
                                               : llvm::json::Value(intrinsic_id)},
            {"constrained", constrained},
            {"rounding_mode", nullableString(rounding_mode)},
            {"exception_behavior", nullableString(exception_behavior)},
            {"debug_location", debugLocation(instruction)},
        };
        facts.emplace_back(std::move(fact));
        if (unsupported_intrinsic) {
          unknowns.emplace_back(llvm::json::Object{
              {"kind", "unsupported_intrinsic"},
              {"fact_id", fact_id},
              {"name", callee},
              {"blocking", true},
          });
        }
        if (use_chain_exhausted && !use_chain_unknown_reported) {
          unknowns.emplace_back(llvm::json::Object{
              {"kind", "use_chain_budget_exceeded"},
              {"fact_id", fact_id},
              {"blocking", true},
          });
          use_chain_unknown_reported = true;
        }
        ++instruction_index;
      }
      if (exhausted) break;
      ++block_index;
    }
    if (exhausted) break;
  }
  llvm::json::Object producer = versionJson();
  llvm::json::Object artifact{
      {"artifact_set_id", identity.artifact_set_id},
      {"artifact_key", identity.artifact_key},
      {"physical_tu_id", identity.physical_tu_id},
      {"context_id", identity.context_id},
      {"profile", identity.profile},
      {"sha256", identity.sha256},
  };
  llvm::json::Object module_identity{
      {"identifier", llvm::sys::path::filename(module.getModuleIdentifier()).str()},
      {"target_triple", module.getTargetTriple().str()},
      {"data_layout", module.getDataLayoutStr()},
      {"module_flags", moduleFlags(module)},
  };
  return llvm::json::Object{
      {"kind", "LLVMFloatingPointFactBundle"},
      {"schema_version", "1.1"},
      {"producer", std::move(producer)},
      {"artifact", std::move(artifact)},
      {"module", std::move(module_identity)},
      {"functions", std::move(functions)},
      {"facts", std::move(facts)},
      {"unknowns", std::move(unknowns)},
      {"diagnostics", llvm::json::Array{}},
      {"budgets",
       llvm::json::Object{
           {"max_facts", static_cast<std::int64_t>(max_facts)},
           {"max_use_edges", static_cast<std::int64_t>(max_use_edges)},
           {"max_summary_depth", static_cast<std::int64_t>(max_summary_depth)},
           {"max_summary_call_edges",
            static_cast<std::int64_t>(max_summary_call_edges)},
           {"facts_emitted", static_cast<std::int64_t>(facts.size())},
           {"use_edges_emitted", static_cast<std::int64_t>(use_edges_emitted)},
           {"summary_call_edges_emitted",
            static_cast<std::int64_t>(summary_call_edges_emitted)},
           {"exhausted", exhausted},
           {"use_chain_exhausted", use_chain_exhausted},
           {"summary_depth_exhausted", summary_depth_exhausted},
           {"summary_call_edges_exhausted", summary_call_edges_exhausted},
       }},
      {"frontier", std::move(frontier)},
      {"semantic_digest", digest(digest_material)},
  };
}

llvm::Expected<llvm::json::Object> extractFacts(
    llvm::StringRef path, const ArtifactIdentity &identity,
    std::size_t max_facts, std::size_t max_use_edges,
    std::size_t max_summary_depth, std::size_t max_summary_call_edges) {
  auto buffer = llvm::MemoryBuffer::getFile(path);
  if (!buffer) {
    return llvm::createStringError(buffer.getError(), "unable_to_read_bitcode");
  }
  llvm::LLVMContext context;
  llvm::SMDiagnostic diagnostic;
  std::unique_ptr<llvm::Module> module = llvm::parseIR(
      (*buffer)->getMemBufferRef(), diagnostic, context);
  if (!module) {
    std::string message;
    llvm::raw_string_ostream stream(message);
    diagnostic.print("fp-ir-worker", stream);
    return llvm::createStringError(llvm::inconvertibleErrorCode(), "%s", message.c_str());
  }
  std::string verifier_message;
  llvm::raw_string_ostream verifier_stream(verifier_message);
  if (llvm::verifyModule(*module, &verifier_stream)) {
    return llvm::createStringError(
        llvm::inconvertibleErrorCode(), "module_verifier_failed: %s",
        verifier_message.c_str());
  }
  ArtifactIdentity verified_identity = identity;
  verified_identity.sha256 = digest((*buffer)->getBuffer());
  return extractFactsFromModule(*module, verified_identity, max_facts, max_use_edges,
                                max_summary_depth, max_summary_call_edges);
}

}  // namespace fp_scanner
