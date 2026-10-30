import { DownloadOutlined } from '@ant-design/icons'
import { Button, Flex, Tag } from 'antd'

import { getSpecializedArtifactDownloadUrl } from '@/api/project/specialized-analysis'
import type {
  SpecializedAnalysis,
  SpecializedAnalysisStageId
} from '@/types/project/specialized-analysis'
import { analysisDiagnostics, isPartialAnalysis, text } from '@/utils/analysisResult'
import { formatDateTime } from '@/utils/display'

import ResultOverview from '../../components/ResultOverview'
import { statusLabels } from '../presentation'

import styles from '../index.module.scss'

export default function ResultSummary({
  run,
  projectId,
  stageId,
  summary,
  showLogs,
  onToggleLogs,
  onOpenDiagnostics
}: {
  run: SpecializedAnalysis
  projectId?: string
  stageId: SpecializedAnalysisStageId
  summary: string
  showLogs: boolean
  onToggleLogs: () => void
  onOpenDiagnostics?: () => void
}) {
  const partial = isPartialAnalysis(run)
  const diagnostics = analysisDiagnostics(run)
  const memoryOrder = run.analyzerType === 'MEMORY_ORDER'
  const primaryDetail = text(diagnostics.primary.detail) || text(diagnostics.primary.detail_en)
  const artifacts = (['json', 'html', 'audit'] as const).filter((format) =>
    format === 'json'
      ? run.resultJsonArtifactId
      : format === 'html'
        ? run.resultHtmlArtifactId
        : run.auditBundleArtifactId
  )
  return (
    <ResultOverview
      status={
        <Tag color={partial ? 'warning' : 'success'}>
          {partial ? '部分分析完成' : statusLabels[run.status]}
        </Tag>
      }
      metadata={[
        { label: '创建时间', value: formatDateTime(run.createdAt) },
        { label: '完成时间', value: formatDateTime(run.finishedAt) }
      ]}
    >
      <div className={styles.resultMetrics}>
        <div>
          <span>{memoryOrder ? '正式风险' : '分析结果'}</span>
          <strong>{run.findingCount}</strong>
        </div>
        <div>
          <span>源码候选</span>
          <strong>{run.candidateCount}</strong>
        </div>
        <div data-warning={diagnostics.count === null || diagnostics.count > 0}>
          <span>分析诊断</span>
          <strong>{diagnostics.count ?? '未知'}</strong>
        </div>
        <div>
          <span>工具版本</span>
          <strong>{run.toolVersion ?? '--'}</strong>
        </div>
      </div>
      <p className={styles.resultNotice} data-warning={partial}>
        {summary}
      </p>
      {memoryOrder ? (
        <p className={styles.resultNotice}>
          {primaryDetail ? `主要诊断：${primaryDetail}；` : ''}分析前沿：
          {diagnostics.frontierCount ?? '未知'}
          {diagnostics.count === null ? '；诊断尚未完整汇总，请查看原始结果。' : ''}
        </p>
      ) : null}
      {!memoryOrder && (diagnostics.count ?? 0) > 0 ? (
        <p className={styles.diagnosticNotice}>
          {diagnostics.count} 项分析诊断，请查看 JSON 结果或审计包。
        </p>
      ) : null}
      <div className={styles.resultArtifacts}>
        <span>
          分析制品 <small>已生成 {artifacts.length} 份制品</small>
        </span>
        <Flex gap={4} wrap>
          {memoryOrder && onOpenDiagnostics ? (
            <Button type="link" size="small" onClick={onOpenDiagnostics}>
              查看诊断明细
            </Button>
          ) : null}
          <Button type="link" size="small" onClick={onToggleLogs}>
            {showLogs ? '收起日志' : '查看日志'}
          </Button>
          {projectId
            ? artifacts.map((format) => (
                <Button
                  key={format}
                  aria-label={`下载${format === 'audit' ? '审计包' : format.toUpperCase()}`}
                  icon={<DownloadOutlined />}
                  type="text"
                  size="small"
                  href={getSpecializedArtifactDownloadUrl(projectId, stageId, run.id, format)}
                >
                  下载{format === 'audit' ? '审计包' : format.toUpperCase()}
                </Button>
              ))
            : null}
        </Flex>
      </div>
    </ResultOverview>
  )
}
