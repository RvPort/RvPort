import type { KnowledgeBaseSourceLocation } from '@/types/project/knowledge-base'

import type { FindingDetailModel } from '../components/FindingDetail/findingDetailPresentation'
import { memoryOrderEvidence } from './memoryOrderEvidence'

export type RiskSourceLocation = KnowledgeBaseSourceLocation & {
  sourceSha256?: string
  unavailable?: boolean
}

function object(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {}
}
function text(value: unknown) {
  return typeof value === 'string' ? value : undefined
}
function rows(value: unknown) {
  return Array.isArray(value) ? value.map(object) : []
}
function number(value: unknown) {
  return typeof value === 'number' && Number.isInteger(value) && value > 0 ? value : undefined
}
function location(value: unknown): RiskSourceLocation | undefined {
  const raw = object(value)
  if (!text(raw.file)) return undefined
  const span = object(raw.span)
  const start = object(span.start)
  const end = object(span.end)
  const context = object(raw.context)
  return {
    file: text(raw.file),
    sourceSha256: text(raw.sourceSha256),
    unavailable: context.status === 'unavailable',
    span: {
      start: { line: number(start.line) ?? number(raw.line) },
      end: { line: number(end.line) ?? number(start.line) ?? number(raw.line) }
    },
    context: {
      lines: rows(context.lines).flatMap((line) =>
        number(line.line) && typeof line.text === 'string'
          ? [{ line: number(line.line)!, text: line.text }]
          : []
      ),
      highlights: rows(context.highlights).flatMap((line) =>
        number(line.line) && number(line.startColumn) && number(line.endColumn)
          ? [
              {
                line: number(line.line)!,
                startColumn: number(line.startColumn)!,
                endColumn: number(line.endColumn)!
              }
            ]
          : []
      )
    }
  }
}

// 只适配证据工作区需要的展示字段，不把专项结论伪装成知识库业务结果。
export function presentRiskEvidence(raw: Record<string, unknown>, memoryOrder = false) {
  const fact = object(raw.fact)
  const chain = object(raw.evidenceChain)
  const occurrences = rows(raw.occurrences).map((item) => ({
    id: text(item.id),
    location: location(item.location),
    configuration: text(item.configuration),
    status: text(item.status)
  }))
  const primary = occurrences.findIndex((item) => item.id === text(raw.primaryOccurrenceId))
  const model: Pick<FindingDetailModel, 'evidenceChain' | 'evidencePath'> = {
    evidenceChain: { status: text(chain.status) },
    evidencePath: {
      steps: rows(chain.steps).map((step) => ({
        id: text(step.id),
        kind: text(step.kind),
        title: text(step.title) || text(step.title_en),
        reason: text(step.reason) || text(step.reason_en),
        status: text(step.status),
        source: location(step.source)
      }))
    }
  }
  if (memoryOrder) {
    const graph = memoryOrderEvidence(chain, model.evidencePath.steps)
    model.evidenceChain.status = graph.status
    model.evidencePath = graph
  }
  return {
    model,
    occurrences,
    defaultOccurrenceIndex: Math.max(0, primary),
    primaryLocation: location(fact.primaryLocation),
    fact: { kind: text(fact.kind), matchedText: text(fact.matchedText), symbol: text(fact.symbol) }
  }
}
