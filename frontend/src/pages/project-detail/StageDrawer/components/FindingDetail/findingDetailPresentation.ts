import type {
  KnowledgeBaseCompletionStatus,
  KnowledgeBaseConclusion,
  KnowledgeBaseEvidenceChain,
  KnowledgeBaseEvidenceStep,
  KnowledgeBaseFact,
  KnowledgeBaseOccurrence,
  KnowledgeBaseReachability,
  KnowledgeBaseSourceLocation,
  ProjectKnowledgeBaseFindingDetail
} from '@/types/project/knowledge-base'

export const missingFindingValue = '暂无数据'

function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {}
}

function asArray<T>(value: unknown): T[] {
  return Array.isArray(value) ? (value as T[]) : []
}

function hasValues(value: object | null | undefined) {
  return Boolean(value && Object.keys(value).length)
}

export interface FindingDetailModel {
  conclusion: KnowledgeBaseConclusion
  defaultOccurrenceIndex: number
  defaultOccurrenceId?: string
  evidenceChain: KnowledgeBaseEvidenceChain
  evidencePath: {
    steps: KnowledgeBaseEvidenceStep[]
    primaryStepCount?: number
    branches?: { from: string; to: string; kind: string }[]
    warnings?: string[]
    budgetTruncated?: boolean
  }
  fact: KnowledgeBaseFact
  occurrences: KnowledgeBaseOccurrence[]
  primaryOccurrenceId?: string
}

export function getFindingDetailModel(
  detail: ProjectKnowledgeBaseFindingDetail
): FindingDetailModel {
  const evidenceFinding = asRecord(asRecord(detail.evidence).finding)
  const fact = hasValues(detail.fact)
    ? (detail.fact as KnowledgeBaseFact)
    : (asRecord(evidenceFinding.fact) as KnowledgeBaseFact)
  const conclusion = hasValues(detail.conclusion)
    ? (detail.conclusion as KnowledgeBaseConclusion)
    : (asRecord(evidenceFinding.conclusion) as KnowledgeBaseConclusion)
  const occurrences = detail.occurrences?.length
    ? detail.occurrences
    : asArray<KnowledgeBaseOccurrence>(evidenceFinding.occurrences)
  const evidenceChain = hasValues(detail.evidenceChain)
    ? (detail.evidenceChain as KnowledgeBaseEvidenceChain)
    : (asRecord(evidenceFinding.evidenceChain) as KnowledgeBaseEvidenceChain)
  const primaryOccurrenceId =
    detail.primaryOccurrenceId ??
    (typeof evidenceFinding.primaryOccurrenceId === 'string'
      ? evidenceFinding.primaryOccurrenceId
      : undefined)
  const primaryOccurrence = occurrences.find((occurrence) => occurrence.id === primaryOccurrenceId)
  const defaultOccurrence =
    primaryOccurrence ??
    occurrences.find((occurrence) => Boolean(occurrence.location?.file)) ??
    occurrences[0]

  return {
    conclusion,
    defaultOccurrenceIndex: defaultOccurrence ? occurrences.indexOf(defaultOccurrence) : 0,
    defaultOccurrenceId: defaultOccurrence?.id,
    evidenceChain,
    evidencePath: {
      // 按服务端返回的步骤完整展示，不用 primaryPath 筛选或重排。
      steps: evidenceChain.steps ?? []
    },
    fact,
    occurrences,
    primaryOccurrenceId
  }
}

export function getFindingSourceLocation(
  detail: ProjectKnowledgeBaseFindingDetail,
  model: FindingDetailModel,
  occurrence?: KnowledgeBaseOccurrence
): KnowledgeBaseSourceLocation | undefined {
  if (occurrence?.location) return occurrence.location
  if (model.fact.primaryLocation) return model.fact.primaryLocation
  if (!detail.filePath) return undefined
  return {
    file: detail.filePath,
    span: {
      end: { line: detail.lineEnd ?? undefined },
      start: { line: detail.lineStart ?? undefined }
    }
  }
}

const completionLabels: Record<KnowledgeBaseCompletionStatus, string> = {
  ambiguous: '存在歧义',
  complete: '分析完整',
  inactive: '当前未启用',
  partial: '分析不完整',
  unknown: '无法判断'
}

export function getCompletionLabel(value?: string) {
  return completionLabels[value as KnowledgeBaseCompletionStatus] ?? value ?? missingFindingValue
}

const reachabilityLabels: Record<KnowledgeBaseReachability, string> = {
  inactive: '当前未启用',
  not_selected: '当前目标未选中',
  selected: '当前目标已选中',
  unknown: '无法判断'
}

export function getReachabilityLabel(value?: string) {
  return reachabilityLabels[value as KnowledgeBaseReachability] ?? value ?? missingFindingValue
}

const reasonCodeLabels: Record<string, string> = {
  missing_input: '缺少完成判断所需的输入',
  source_context_unavailable: '源码上下文不可用',
  traversal_budget_exhausted: '证据搜索达到分析预算上限'
}

export function getReasonCodeLabel(value?: string) {
  return value ? (reasonCodeLabels[value] ?? value) : missingFindingValue
}

const evidenceKindLabels: Record<string, string> = {
  'source-fact': '源码事实',
  'thread-creation': '线程创建',
  'callback-binding': '回调绑定',
  'execution-root': '执行入口',
  'call-path': '调用路径',
  'thread-instance': '线程实例',
  mhp: '潜在并行执行',
  'memory-access': '内存访问',
  'object-provenance': '对象来源',
  'byte-range-overlap': '字节范围重叠',
  'mutex-region': '互斥保护区域',
  'raii-region': 'RAII 保护区域',
  'atomic-event': '原子事件',
  'reads-from': '读取来源',
  'modification-order': '修改顺序',
  'release-sequence': '释放序列',
  'synchronization-proof': '同步证据',
  'synchronization-gap': '同步缺口',
  break: '证据断点',
  sourceObservation: '源码观察',
  irObservation: '中间表示观察',
  conclusion: '分析结论',
  observation: '分析观察',
  architecture_probe: '架构探测',
  assessment: '迁移判断',
  build_argument: '构建参数',
  classification: '影响分类',
  intrinsic_call: '源码调用',
  macro_definition: '宏定义',
  preprocessor_condition: '预处理条件',
  source_evidence: '源码证据',
  source_call: '源码调用'
}

export function getEvidenceKindLabel(value?: string) {
  return value ? (evidenceKindLabels[value] ?? value) : '证据'
}

const evidenceRelationLabels: Record<string, string> = {
  classified_as: '分类为',
  contains: '包含',
  impacts_assessment: '影响迁移判断',
  selected_by: '由此条件选中',
  supports: '支持下一步判断'
}

export function getEvidenceRelationLabel(value?: string) {
  return value ? (evidenceRelationLabels[value] ?? value) : undefined
}

export function formatFindingLocation(location?: KnowledgeBaseSourceLocation | null) {
  if (!location?.file) return '未提供源码位置'
  const start = location.span?.start?.line
  const end = location.span?.end?.line
  if (!start) return location.file
  return `${location.file}:${end && end !== start ? `${start}–${end}` : start}`
}
