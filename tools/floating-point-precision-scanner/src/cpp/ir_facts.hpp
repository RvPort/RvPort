#pragma once

#include <cstddef>
#include <string>

#include "llvm/ADT/StringRef.h"
#include "llvm/IR/Module.h"
#include "llvm/Support/Error.h"
#include "llvm/Support/JSON.h"

namespace fp_scanner {

inline constexpr llvm::StringLiteral kToolVersion = "0.6.0";
inline constexpr llvm::StringLiteral kLlvmVersion = "21.1.8";
inline constexpr llvm::StringLiteral kReaderVersion = "1.0";
inline constexpr llvm::StringLiteral kExtractorVersion = "1.2";
inline constexpr llvm::StringLiteral kWorkerProtocolVersion = "1.1";

struct ArtifactIdentity {
  std::string artifact_set_id;
  std::string artifact_key;
  std::string physical_tu_id;
  std::string context_id;
  std::string profile;
  std::string sha256;
};

llvm::json::Object versionJson();
llvm::Expected<llvm::json::Object> extractFactsFromModule(
    const llvm::Module &module, const ArtifactIdentity &identity,
    std::size_t max_facts, std::size_t max_use_edges = 1000,
    std::size_t max_summary_depth = 3,
    std::size_t max_summary_call_edges = 1000);
llvm::Expected<llvm::json::Object> extractFacts(
    llvm::StringRef path, const ArtifactIdentity &identity,
    std::size_t max_facts, std::size_t max_use_edges = 1000,
    std::size_t max_summary_depth = 3,
    std::size_t max_summary_call_edges = 1000);

}  // namespace fp_scanner
