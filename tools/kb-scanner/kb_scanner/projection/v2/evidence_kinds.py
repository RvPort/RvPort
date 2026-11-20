"""Evidence-kind labels backed by exact, applicable compiler knowledge."""


def intrinsic_symbols(entries, architecture_family: str = "x86"):
    return frozenset(item.canonical_value for item in entries if item.architecture_family == architecture_family and item.fact_kind in {"intrinsics", "builtins"})


def is_intrinsic(evidence, symbols):
    return evidence.kind in {"call_expression", "symbol"} and isinstance(evidence.value, str) and evidence.value in symbols


def proven_macro_terminals(relations):
    return frozenset(relation.metadata['macroEffect']['terminalEvidenceId'] for relation in relations
                     if relation.status == 'complete' and relation.metadata.get('macroEffect', {}).get('proven') is True)
