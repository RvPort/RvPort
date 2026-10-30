import { DownloadOutlined } from '@ant-design/icons'
import { Alert, Button, Flex } from 'antd'

import { getSpecializedArtifactDownloadUrl } from '@/api/project/specialized-analysis'
import type {
  SpecializedAnalysis,
  SpecializedAnalysisStageId
} from '@/types/project/specialized-analysis'
import { analysisDiagnostics, isPartialAnalysis, record, text } from '@/utils/analysisResult'

import styles from './AnalysisDiagnostics.module.scss'

export default function AnalysisDiagnostics({
  run,
  notice,
  projectId,
  stageId = 'memory-order-analysis'
}: {
  run: SpecializedAnalysis
  notice?: string
  projectId?: string
  stageId?: SpecializedAnalysisStageId
}) {
  const diagnostics = analysisDiagnostics(run)
  const primary = diagnostics.primary
  const partial = isPartialAnalysis(run)
  const primaryDetail = text(primary.detail) || text(primary.detail_en)
  const items = diagnostics.items.length
    ? diagnostics.items
    : Object.keys(primary).length
      ? [primary]
      : []
  const title = partial
    ? '当前证据不足以形成完整结论'
    : run.findingCount > 0
      ? '发现需要处理的正式风险'
      : run.candidateCount > 0
        ? '发现需要核实的源码候选'
        : '本次分析范围内未发现正式风险'
  return (
    <section className={styles.summary} aria-label="分析诊断详情">
      <div className={styles.conclusion}>
        <h4>{title}</h4>
        {primaryDetail ? (
          <p>
            <span>{partial ? '主要阻碍' : '主要诊断'}</span>
            {primaryDetail}
          </p>
        ) : null}
      </div>
      <dl className={styles.facts}>
        <div>
          <dt>正式风险</dt>
          <dd>{run.findingCount}</dd>
        </div>
        <div>
          <dt>源码候选</dt>
          <dd>{run.candidateCount}</dd>
        </div>
        <div>
          <dt>分析诊断</dt>
          <dd>{diagnostics.count ?? '未知'}</dd>
        </div>
        <div>
          <dt>分析前沿</dt>
          <dd>{diagnostics.frontierCount ?? '未知'}</dd>
        </div>
        <div>
          <dt>工具版本</dt>
          <dd>{run.toolVersion ?? '--'}</dd>
        </div>
      </dl>
      <p className={styles.notice} data-warning={partial}>
        {notice || '结论限于本次分析的证据和模型范围，零正式风险不代表迁移安全。'}
      </p>
      <p className={styles.notice}>执行记录：{run.id}</p>
      {projectId ? (
        <Flex gap={8} wrap>
          {(['json', 'audit'] as const)
            .filter((format) =>
              format === 'json' ? run.resultJsonArtifactId : run.auditBundleArtifactId
            )
            .map((format) => (
              <Button
                key={format}
                aria-label={`下载${format === 'json' ? 'JSON' : '审计包'}`}
                icon={<DownloadOutlined />}
                href={getSpecializedArtifactDownloadUrl(projectId, stageId, run.id, format)}
              >
                下载{format === 'json' ? 'JSON' : '审计包'}
              </Button>
            ))}
        </Flex>
      ) : null}
      {diagnostics.count === null ? (
        <Alert
          type="warning"
          title={diagnostics.message || '诊断尚未完整汇总，请查看原始结果；未知不代表零诊断。'}
        />
      ) : null}
      {items.length ? (
        <section aria-label="诊断记录">
          <h4 className={styles.recordsTitle}>诊断记录（已提供 {items.length} 项）</h4>
          <ol className={styles.records} aria-label="诊断明细">
            {items.map((item, i) => (
              <li key={i}>
                <div className={styles.recordMeta}>
                  {text(item.code) ? <code>{text(item.code)}</code> : null}
                  {text(item.stage) ? <span>阶段：{text(item.stage)}</span> : null}
                  {text(item.scope_id) ? <span>范围：{text(item.scope_id)}</span> : null}
                </div>
                <p>{text(item.detail) || text(item.detail_en) || '未提供诊断说明'}</p>
                {text(item.suggestion) || text(item.suggestion_en) ? (
                  <p className={styles.suggestion}>
                    建议：{text(item.suggestion) || text(item.suggestion_en)}
                  </p>
                ) : null}
                {Array.isArray(item.references) ? (
                  <p className={styles.references}>
                    引用：
                    {item.references
                      .filter((ref): ref is string => typeof ref === 'string')
                      .join('、')}
                  </p>
                ) : null}
              </li>
            ))}
          </ol>
        </section>
      ) : (
        <p className={styles.notice}>
          {diagnostics.count === 0
            ? '本次记录没有分析诊断。'
            : '本次记录未提供诊断明细，请查看原始 JSON 或审计包。'}
        </p>
      )}
      {diagnostics.truncated ? (
        <Alert
          type="warning"
          title="诊断明细已截断，当前展示数量不是总数。"
          description="下载 JSON 结果查看已保存诊断；完整超预算诊断请下载审计包并查看 diagnostics/full.json。"
        />
      ) : null}
      {diagnostics.truncated && !run.auditBundleArtifactId ? (
        <p>本记录未提供完整审计包，无法下载全部诊断。</p>
      ) : null}
      {record(run.summary).diagnosticsDownload === 'result-json' && !run.resultJsonArtifactId ? (
        <p>本记录的诊断 JSON 制品不可用。</p>
      ) : null}
    </section>
  )
}
