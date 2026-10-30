import { type ReactNode, useState } from 'react'
import { SafetyCertificateOutlined } from '@ant-design/icons'
import { Alert, Button, Select, Tag } from 'antd'

import { formatFindingLocation } from '../../components/FindingDetail/findingDetailPresentation'
import { FindingFacts, FindingRawData } from '../../components/FindingDetail/FindingDisclosures'
import { EvidencePath } from '../../components/FindingDetail/FindingEvidence'
import FindingNextSteps from '../../components/FindingDetail/FindingNextSteps'
import { presentRiskEvidence, type RiskSourceLocation } from '../evidencePresentation'
import { severityColors, severityLabels } from '../presentation'
import type { RiskFinding } from '../types'
import FindingSource, { type FindingSourceState } from './FindingSource'
import FindingSupplementarySource from './FindingSupplementarySource'

import styles from '../../components/FindingDetail/index.module.scss'

function FindingWorkspace({
  finding,
  source,
  reportActions
}: {
  finding: RiskFinding
  source?: FindingSourceState
  reportActions?: ReactNode
}) {
  const evidence = presentRiskEvidence(finding.rawDetail, finding.memoryOrder)
  const [occurrenceIndex, setOccurrenceIndex] = useState(evidence.defaultOccurrenceIndex)
  const [selectedStep, setSelectedStep] = useState<number | null>(null)
  const [sourceStep, setSourceStep] = useState<number | null>(null)
  const occurrence = evidence.occurrences[occurrenceIndex]
  const primaryLocation =
    occurrence?.location ??
    (occurrenceIndex === evidence.defaultOccurrenceIndex && !(finding.memoryOrder && occurrence)
      ? evidence.primaryLocation
      : undefined)
  const step = sourceStep === null ? undefined : evidence.model.evidencePath.steps[sourceStep]
  const location = (
    sourceStep !== null && finding.memoryOrder ? step?.source : (step?.source ?? primaryLocation)
  ) as RiskSourceLocation | undefined
  const embedded =
    location?.unavailable || (finding.memoryOrder && !location?.sourceSha256)
      ? []
      : (location?.context?.lines ?? [])
  const highlightLines = new Set(location?.context?.highlights?.map((item) => item.line))
  const embeddedSource: FindingSourceState | undefined = embedded.length
    ? {
        data: {
          findingId: finding.id,
          filePath: location?.file ?? finding.location,
          lines: embedded.map((line) => ({
            lineNumber: line.line,
            text: line.text,
            highlighted: highlightLines.has(line.line)
          }))
        }
      }
    : undefined
  const allowPrimaryContext =
    sourceStep === null &&
    occurrenceIndex === evidence.defaultOccurrenceIndex &&
    !(finding.memoryOrder && occurrence && !occurrence.location)
  const startLine = location?.span?.start?.line
  const endLine = location?.span?.end?.line ?? startLine
  const cached = source?.data
  // 仅复用同一结果、同一文件且完整覆盖选中范围的历史片段。
  const coveredLines = cached?.lines.filter(
    (line) => startLine && endLine && line.lineNumber >= startLine && line.lineNumber <= endLine
  )
  const canReuse =
    !finding.memoryOrder &&
    !location?.unavailable &&
    cached?.findingId === finding.id &&
    cached?.filePath === location?.file &&
    startLine &&
    endLine &&
    endLine >= startLine &&
    new Set(coveredLines?.map((line) => line.lineNumber)).size === endLine - startLine + 1
  const reusedSource: FindingSourceState | undefined =
    canReuse && cached
      ? {
          data: {
            ...cached,
            lines: cached.lines.map((line) => ({
              ...line,
              highlighted: line.lineNumber >= startLine && line.lineNumber <= endLine
            }))
          }
        }
      : undefined
  const selectedSource = finding.memoryOrder
    ? embeddedSource
    : allowPrimaryContext
      ? (source ?? embeddedSource)
      : (embeddedSource ?? reusedSource)
  const displayedSource: FindingSourceState | undefined =
    selectedSource ??
    (allowPrimaryContext && source?.unavailableReason
      ? { unavailableReason: source.unavailableReason }
      : undefined) ??
    (finding.memoryOrder && location && (cached || !location.sourceSha256)
      ? {
          unavailableReason: 'SOURCE_EVIDENCE_IDENTITY_UNVERIFIED'
        }
      : undefined)
  const context = displayedSource?.data?.findingId === finding.id ? displayedSource.data : undefined
  const displayedFinding = {
    id: finding.id,
    location:
      context?.filePath ?? location?.file ?? (allowPrimaryContext ? finding.location : '未提供位置')
  }
  const contextLine =
    context?.lines.find((line) => line.highlighted)?.lineNumber ??
    location?.span?.start?.line ??
    (allowPrimaryContext && context?.filePath === finding.location ? finding.line : undefined)
  const locationText = context
    ? `${context.filePath}${contextLine ? `:${contextLine}` : ''}`
    : location
      ? formatFindingLocation(location) +
        (!location.span?.start?.line &&
        allowPrimaryContext &&
        location.file === finding.location &&
        finding.line
          ? `:${finding.line}`
          : '')
      : `${displayedFinding.location}${allowPrimaryContext && finding.line ? `:${finding.line}` : ''}`
  function selectStep(index: number) {
    setSelectedStep(index)
    // 内存序步骤必须展示自己的证据；断点不能借用上一位置的代码。
    if (
      finding.memoryOrder ||
      evidence.model.evidencePath.steps[index]?.source?.context?.lines?.length
    )
      setSourceStep(index)
  }
  return (
    <article className={styles.findingWorkspace}>
      {finding.protocolWarning ? <Alert type="warning" title={finding.protocolWarning} /> : null}
      {finding.memoryOrder && finding.findingKind !== 'FINDING' ? (
        <Alert
          type="warning"
          title={
            (
              {
                SOURCE_CANDIDATE: '源码候选 · 待核实',
                INCONCLUSIVE: '证据不足 · 待核实',
                DISCHARGED: '受限模型内已消解 · 不代表迁移安全'
              } as Record<string, string>
            )[finding.findingKind] || '结论待核实'
          }
          description="人工纳入报告只改变报告输入选择，不代表分析已证实，也不计入正式风险。"
        />
      ) : null}
      <section
        className={styles.findingConclusionV2}
        data-severity={finding.severity}
        aria-label="迁移判断"
      >
        <div className={styles.findingConclusionIcon} aria-hidden="true">
          <SafetyCertificateOutlined />
        </div>
        <div className={styles.findingConclusionCopy}>
          <span className={styles.findingEyebrow}>迁移判断</span>
          <h2>{finding.title}</h2>
          <p>{finding.detail}</p>
        </div>
        <div className={styles.findingConclusionMeta}>
          <div className={styles.findingConclusionTags}>
            <Tag color={severityColors[finding.severity]}>{severityLabels[finding.severity]}</Tag>
            {finding.requiresHumanConfirmation ? <Tag color="warning">需人工确认</Tag> : null}
            <Tag>
              {finding.findingKind === 'SOURCE_CANDIDATE'
                ? '源码候选 · 需人工确认'
                : finding.resultStatus}
            </Tag>
          </div>
        </div>
      </section>
      <section className={styles.evidenceWorkspace} aria-label="证据工作区">
        <div className={styles.evidenceWorkspaceHeader}>
          <div className={styles.evidenceWorkspaceIdentity}>
            <h3>证据工作区</h3>
            <strong>{locationText}</strong>
          </div>
          {evidence.occurrences.length > 1 ? (
            <Select
              aria-label="选择分析命中位置"
              value={occurrenceIndex}
              options={evidence.occurrences.map((item, index) => ({
                value: index,
                label: `位置 ${index + 1} · ${formatFindingLocation(item.location)}`
              }))}
              onChange={(index) => {
                setOccurrenceIndex(index)
                setSelectedStep(null)
                setSourceStep(null)
              }}
            />
          ) : null}
        </div>
        <div className={styles.evidenceWorkbench}>
          <EvidencePath model={evidence.model} selectedIndex={selectedStep} onSelect={selectStep} />
          <div className={styles.sourceEvidencePanel}>
            <div className={styles.codeEvidenceMode}>
              <div>
                <strong>
                  {step?.title ?? (allowPrimaryContext ? '主要命中上下文' : '当前命中上下文')}
                </strong>
                {selectedStep !== null &&
                !evidence.model.evidencePath.steps[selectedStep]?.source?.context?.lines?.length ? (
                  <small>
                    {finding.memoryOrder
                      ? '所选步骤或断点没有可用源码，不借用其他位置片段。'
                      : '所选判断没有直接源码，继续保留当前代码。'}
                  </small>
                ) : null}
              </div>
              {sourceStep !== null ? (
                <Button
                  size="small"
                  type="text"
                  onClick={() => {
                    setSourceStep(null)
                    setSelectedStep(null)
                  }}
                >
                  返回主要代码
                </Button>
              ) : null}
            </div>
            <FindingSource finding={displayedFinding} source={displayedSource} />
            {finding.memoryOrder && allowPrimaryContext ? (
              <FindingSupplementarySource
                key={`${finding.id}:${occurrence?.id ?? 'primary'}`}
                finding={finding}
                location={location}
                source={source}
              />
            ) : null}
          </div>
        </div>
      </section>
      <FindingNextSteps suggestion={finding.suggestion} reportActions={reportActions}>
        {finding.impact || finding.validation || finding.validationPlan.length ? (
          <div className={styles.findingSuggestion}>
            {finding.impact ? <p>迁移影响：{finding.impact}</p> : null}
            {finding.validation ? <p>验证建议：{finding.validation}</p> : null}
            {finding.validationPlan.map((item) => (
              <p key={item.label}>
                {item.label}：{item.value}
              </p>
            ))}
          </div>
        ) : null}
      </FindingNextSteps>
      <FindingFacts>
        <dl className={styles.findingFactsContent}>
          <div>
            <dt>规则</dt>
            <dd>{finding.code}</dd>
          </div>
          <div>
            <dt>判断可信度</dt>
            <dd>{finding.confidence}</dd>
          </div>
          {finding.evidenceLevel ? (
            <div>
              <dt>证据级别</dt>
              <dd>{finding.evidenceLevel}</dd>
            </div>
          ) : null}
          {finding.evidenceOrigin ? (
            <div>
              <dt>分析依据</dt>
              <dd>{finding.evidenceOrigin}</dd>
            </div>
          ) : null}
          {evidence.fact.matchedText ? (
            <div>
              <dt>命中文本</dt>
              <dd>{evidence.fact.matchedText}</dd>
            </div>
          ) : null}
          {occurrence?.configuration ? (
            <div>
              <dt>当前配置</dt>
              <dd>{occurrence.configuration}</dd>
            </div>
          ) : null}
        </dl>
        {finding.assumptions.length ? (
          <div className={styles.findingReasonCodes}>
            <strong>分析边界</strong>
            <ul>
              {finding.assumptions.map((item) => (
                <li key={item}>{item}</li>
              ))}
            </ul>
          </div>
        ) : null}
      </FindingFacts>
      <FindingRawData value={finding.rawDetail} />
    </article>
  )
}

export default function FindingDetail(props: {
  finding: RiskFinding
  source?: FindingSourceState
  reportActions?: ReactNode
}) {
  return <FindingWorkspace key={props.finding.id} {...props} />
}
