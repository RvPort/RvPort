"""Admission contract shared by detection, classification and report boundaries.

候选准入合同：detector/classifier/report 三条边界共用的候选合法性校验。
核心不变式——status=candidate 的观察必须凭真实证据与正确 basis 进入
分类，任何绕过准入边界的分类都会触发 CandidateAdmissionError。
"""
import re


class CandidateAdmissionError(ValueError):
    """A classification bypassed the architecture candidate admission boundary."""


def architecture_attribute(value: str) -> bool:
    """判断值中是否出现架构类属性（target/vector_size/arch 等）的调用。

    只匹配属性"名字"本身，而非恰好包含 ISA 名的任意字符串：
    先剥离字符串字面量与注释，再按词边界匹配属性名后跟 "("。
    """
    # Attribute *names*, not arbitrary strings/arguments containing an ISA name.
    without_strings = re.sub(r'"(?:\\.|[^"\\])*"', '""', value)
    without_strings = re.sub(r'/\*.*?\*/|//[^\r\n]*', ' ', without_strings, flags=re.S)
    return bool(re.search(r'(?<![\w])(?:__)?(?:target|target_clones|vector_size|ext_vector_type|arch|cpu_specific|cpu_dispatch)(?:__)?\s*\(', without_strings))


def invalid_candidate(observation) -> bool:
    """判断一个候选观察是否不满足准入条件（不得被分类引用）。

    仅检查 status=candidate 的观察；foreign/not-architecture-related/
    duplicate-child 三种处置直接判不合格。inline_asm/target_attribute
    两类候选还必须有证据、basis 正确且处置合法（unknown 或 x86 活跃分支）。
    """
    if observation.status != 'candidate':
        return False
    metadata = observation.metadata
    if metadata.get('architectureDisposition') in {'foreign', 'not-architecture-related', 'duplicate-child'}:
        return True
    if observation.kind not in {'inline_asm_candidate', 'target_attribute_candidate'}:
        return False
    basis = {'inline_asm_candidate': 'unclassified_inline_asm', 'target_attribute_candidate': 'architecture_attribute'}[observation.kind]
    disposition = metadata.get('architectureDisposition')
    return not (observation.evidence_ids and metadata.get('candidateBasis') == basis and (disposition == 'unknown' or (disposition == 'x86' and metadata.get('branchRole') == 'x86')))


def validate_candidates(detections, classifications) -> None:
    """校验所有分类引用的候选观察均满足准入合同，违例抛异常。"""
    observations = {item.id: item for item in (*detections.observations, *classifications.observations)}
    for classification in classifications.classifications:
        for identifier in classification.observation_ids:
            item = observations.get(identifier)
            if item is not None and invalid_candidate(item):
                raise CandidateAdmissionError('candidate.invalid-admission: classification references an ineligible candidate')
