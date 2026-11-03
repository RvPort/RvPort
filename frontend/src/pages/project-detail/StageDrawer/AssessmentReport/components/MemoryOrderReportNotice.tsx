import { Alert } from 'antd'

import type { ProjectEvaluationReportSummary } from '@/types/project'
import { isPartialAnalysis, record, text } from '@/utils/analysisResult'

export default function MemoryOrderReportNotice({
  summary
}: {
  summary: ProjectEvaluationReportSummary
}) {
  const analysis = record(summary.memoryOrderAnalysis)
  const pending = summary.pendingVerification ?? []
  const status = text(analysis.analysisStatus)
  const hasMemoryInputs =
    (summary.sourceTypeCounts?.MEMORY_ORDER_FINDING ?? 0) > 0 ||
    (summary.pendingVerificationCount ?? 0) > 0 ||
    pending.length > 0
  const hasAnalysis = Object.keys(analysis).length > 0 && status !== 'UNAVAILABLE'
  if (!hasMemoryInputs && !hasAnalysis) return null
  const title = isPartialAnalysis(analysis)
    ? '部分分析完成'
    : status === 'UNAVAILABLE'
      ? '内存序分析信息不可用'
      : !status && typeof analysis.analysisComplete !== 'boolean'
        ? '历史内存序分析完整性未知'
        : '内存序结论受证据与模型范围限制'
  return (
    <section aria-label="内存序分析局限与待核实项">
      <Alert
        type="warning"
        title={title}
        description={
          text(analysis.limitations) ||
          text(analysis.limitationsEn) ||
          '零正式风险不代表迁移安全；历史记录缺少完整性信息时不能认定分析完整。'
        }
      />
      <p>
        待核实项：
        {summary.pendingVerificationCount ??
          (summary.pendingVerification ? pending.length : '未知')}
        。人工纳入不代表分析已证实，不计入正式风险。
      </p>
      {pending.length ? (
        <details>
          <summary>查看待核实项</summary>
          <ul>
            {pending.map((item, i) => (
              <li key={text(item.id) || i}>
                {text(item.issueTitle) || '未命名待核实项'} ·{' '}
                {text(item.locationLabel) || '未提供位置'} · 待核实
              </li>
            ))}
          </ul>
        </details>
      ) : null}
    </section>
  )
}
