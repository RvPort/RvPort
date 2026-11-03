import type { KnowledgeBaseEvidenceStep } from '@/types/project/knowledge-base'
import { record, text } from '@/utils/analysisResult'

const stepKinds = new Set([
  'source-fact',
  'thread-creation',
  'callback-binding',
  'execution-root',
  'call-path',
  'thread-instance',
  'mhp',
  'memory-access',
  'object-provenance',
  'byte-range-overlap',
  'mutex-region',
  'raii-region',
  'atomic-event',
  'reads-from',
  'modification-order',
  'release-sequence',
  'synchronization-proof',
  'synchronization-gap',
  'conclusion'
])
const edgeKinds = new Set([
  'creates',
  'binds',
  'reaches',
  'calls',
  'executes-as',
  'may-happen-in-parallel',
  'accesses',
  'aliases',
  'overlaps',
  'protected-by',
  'communicates-via',
  'ordered-by',
  'supports',
  'supports-candidate',
  'alternative-to'
])

export function memoryOrderEvidence(
  chain: Record<string, unknown>,
  steps: KnowledgeBaseEvidenceStep[]
) {
  const warnings = new Set<string>()
  const index = new Map<string, KnowledgeBaseEvidenceStep>()
  for (const step of steps) {
    if (!step.id || index.has(step.id)) {
      warnings.add('证据步骤 ID 缺失或重复，无法确认完整结构。')
      continue
    }
    if (!stepKinds.has(step.kind ?? '')) warnings.add(`未知步骤类型：${step.kind ?? '未提供'}`)
    index.set(step.id, step)
  }
  const path = Array.isArray(chain.primaryPath)
    ? chain.primaryPath.filter((id): id is string => typeof id === 'string')
    : []
  if (!index.size && (chain.status || chain.rootStepId))
    warnings.add('证据链缺少可验证步骤，不能确认完整性。')
  if (!path.length && steps.length) warnings.add('未提供主证据链，不能从存储顺序推断。')
  if (path.length && chain.rootStepId !== path[0]) warnings.add('主链起点与根步骤不一致。')
  const seen = new Set<string>()
  const main = path.flatMap((id) => {
    if (seen.has(id)) {
      warnings.add('主证据链包含重复步骤或环。')
      return []
    }
    seen.add(id)
    const step = index.get(id)
    if (!step) warnings.add(`缺少引用步骤：${id}`)
    return step ? [step] : []
  })
  const edges = (Array.isArray(chain.edges) ? chain.edges : []).map(record).map((edge) => ({
    from: text(edge.fromStepId),
    to: text(edge.toStepId),
    kind: text(edge.kind)
  }))
  const adjacency = new Map([...index.keys()].map((id) => [id, [] as string[]]))
  const degrees = new Map([...index.keys()].map((id) => [id, 0]))
  for (const edge of edges) {
    if (!edgeKinds.has(edge.kind)) warnings.add(`未知关系类型：${edge.kind || '未提供'}`)
    if (!index.has(edge.from) || !index.has(edge.to)) {
      warnings.add(`关系引用缺失：${edge.from || '?'} → ${edge.to || '?'}`)
      continue
    }
    adjacency.get(edge.from)!.push(edge.to)
    degrees.set(edge.to, degrees.get(edge.to)! + 1)
  }
  // 拓扑检查只验证结构，不用存储顺序生成主链；迭代避免异常图递归溢出。
  const queue = [...degrees].filter(([, degree]) => degree === 0).map(([id]) => id)
  for (let i = 0; i < queue.length; i++) {
    for (const to of adjacency.get(queue[i]) ?? []) {
      degrees.set(to, degrees.get(to)! - 1)
      if (degrees.get(to) === 0) queue.push(to)
    }
  }
  if (queue.length !== index.size) warnings.add('证据关系包含环，不能视为完整证明。')
  for (let i = 1; i < path.length; i++) {
    if (!edges.some((edge) => edge.from === path[i - 1] && edge.to === path[i]))
      warnings.add(`主链缺少连接：${path[i - 1]} → ${path[i]}`)
  }
  const breaks = (Array.isArray(chain.breaks) ? chain.breaks : []).map(record).map((item, i) => {
    // expectedKind 是开放的证据缺口名称，不是 Step.kind 枚举。
    const refs = [
      item.fromStepId,
      ...(Array.isArray(item.candidateStepIds) ? item.candidateStepIds : [])
    ]
    for (const ref of refs)
      if (typeof ref === 'string' && !index.has(ref)) warnings.add(`断点引用缺失：${ref}`)
    return {
      id: `break:${text(item.id) || i}`,
      kind: 'break',
      title: `证据断点：${text(item.reasonCode) || '未知原因'}`,
      reason: [
        text(item.detail) || text(item.detail_en),
        text(item.expectedKind) && `期望证据：${text(item.expectedKind)}`,
        text(item.suggestion) || text(item.suggestion_en)
      ]
        .filter(Boolean)
        .join('；'),
      status: text(item.status) || 'partial'
    }
  })
  return {
    status:
      warnings.size || breaks.length || record(chain.budget).truncated === true
        ? 'partial'
        : text(chain.status),
    steps: [...main, ...[...index.values()].filter((step) => !seen.has(step.id!)), ...breaks],
    primaryStepCount: main.length,
    branches: edges.filter(
      (edge) => !path.some((id, i) => id === edge.from && path[i + 1] === edge.to)
    ),
    warnings: [...warnings],
    budgetTruncated: record(chain.budget).truncated === true
  }
}
