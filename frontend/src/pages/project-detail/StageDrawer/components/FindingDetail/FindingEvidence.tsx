import { BranchesOutlined } from '@ant-design/icons'
import { Alert, Button, Tag } from 'antd'

import ContentState from '@/components/ContentState'
import MonacoEditor, { type MonacoEditorHighlightRange } from '@/components/MonacoEditor'
import type {
  KnowledgeBaseEvidenceStep,
  KnowledgeBaseSourceLocation,
  ProjectKnowledgeBaseCodeContext
} from '@/types/project/knowledge-base'

import StageViewLoading from '../StageViewLoading'
import type { FindingDetailModel } from './findingDetailPresentation'
import { getCompletionLabel, getEvidenceKindLabel } from './findingDetailPresentation'

import styles from './index.module.scss'

function sourceLanguage(file?: string | null) {
  const extension = file?.split('.').pop()?.toLowerCase()
  if (extension === 'json') return 'json'
  if (extension === 'sh' || extension === 'bash') return 'shell'
  if (['c', 'cc', 'cpp', 'cxx', 'h', 'hh', 'hpp', 'hxx'].includes(extension ?? '')) return 'cpp'
  return 'plaintext'
}

function exactHighlightRange(
  text: string,
  editorLineNumber: number,
  candidate?: string
): MonacoEditorHighlightRange | undefined {
  const exactText = candidate?.trim()
  if (!exactText) return undefined
  const startIndex = text.indexOf(exactText)
  if (startIndex < 0) return undefined
  return {
    endColumn: startIndex + exactText.length + 1,
    endLineNumber: editorLineNumber,
    startColumn: startIndex + 1,
    startLineNumber: editorLineNumber
  }
}

function completionTag(value?: string) {
  const color =
    value === 'complete'
      ? 'success'
      : value === 'partial' || value === 'ambiguous'
        ? 'warning'
        : undefined
  return <Tag color={color}>{getCompletionLabel(value)}</Tag>
}

export function SourceEvidence({
  codeContext,
  codeContextError,
  codeContextLoading,
  highlightText,
  location,
  onShowPrimaryContext,
  preferCodeContext,
  selectedStep,
  sourceStep,
  showingEvidenceSource
}: {
  codeContext?: ProjectKnowledgeBaseCodeContext
  codeContextError?: boolean
  codeContextLoading?: boolean
  highlightText?: string
  location?: KnowledgeBaseSourceLocation | null
  onShowPrimaryContext: () => void
  preferCodeContext: boolean
  selectedStep?: KnowledgeBaseEvidenceStep
  sourceStep?: KnowledgeBaseEvidenceStep
  showingEvidenceSource: boolean
}) {
  const embeddedLines = location?.context?.lines ?? []
  const contextLines = preferCodeContext ? (codeContext?.lines ?? []) : []
  // 主要位置优先展示补充接口的完整窗口，证据步骤只使用自身保存的可信片段。
  const useCodeContext = contextLines.length > 0
  const lines = useCodeContext
    ? contextLines
    : embeddedLines.map((line) => ({ lineNumber: line.line, text: line.text }))
  const highlightedSourceLines = new Set(
    useCodeContext
      ? contextLines.filter((line) => line.highlighted).map((line) => line.lineNumber)
      : (location?.context?.highlights?.map((highlight) => highlight.line) ?? [])
  )
  const highlightedLineNumbers = lines.flatMap((line, index) =>
    highlightedSourceLines.has(line.lineNumber) ? [index + 1] : []
  )
  const locationHighlights = new Map(
    location?.context?.highlights?.map((highlight) => [highlight.line, highlight]) ?? []
  )
  const firstHighlightedLine = lines.find((line) => highlightedSourceLines.has(line.lineNumber))
  const highlightedRanges = lines.flatMap((line, index) => {
    if (!highlightedSourceLines.has(line.lineNumber)) return []
    const explicitHighlight = locationHighlights.get(line.lineNumber)
    if (explicitHighlight) {
      const startColumn = Math.max(1, Math.min(explicitHighlight.startColumn, line.text.length + 1))
      return [
        {
          endColumn: Math.max(
            startColumn + 1,
            Math.min(explicitHighlight.endColumn, line.text.length + 1)
          ),
          endLineNumber: index + 1,
          startColumn,
          startLineNumber: index + 1
        }
      ]
    }
    const lineOffset = firstHighlightedLine ? line.lineNumber - firstHighlightedLine.lineNumber : 0
    const matchedLine = highlightText?.split(/\r?\n/)[lineOffset]
    const range = exactHighlightRange(line.text, index + 1, matchedLine)
    return range ? [range] : []
  })
  const waitingForContext = preferCodeContext && codeContextLoading && !codeContext
  const selectedStepHasNoSource = Boolean(
    selectedStep && !selectedStep.source?.context?.lines?.length
  )
  const activeStep = selectedStep ?? sourceStep
  const contextDescription = selectedStepHasNoSource
    ? '所选判断没有直接源码，继续保留最近一次有效代码。'
    : useCodeContext
      ? `已展示接口返回的 ${contextLines.length} 行上下文`
      : undefined

  return (
    <div className={styles.sourceEvidencePanel} aria-label="代码证据">
      <div className={styles.codeEvidenceMode}>
        <div>
          <strong>
            {activeStep
              ? (activeStep.title ?? getEvidenceKindLabel(activeStep.kind))
              : '主要命中上下文'}
          </strong>
          {activeStep?.reason ? <span>{activeStep.reason}</span> : null}
          {contextDescription ? <small>{contextDescription}</small> : null}
        </div>
        {showingEvidenceSource ? (
          <Button
            className={styles.sourceResetButton}
            size="small"
            type="text"
            onClick={onShowPrimaryContext}
          >
            返回主要代码
          </Button>
        ) : null}
      </div>
      {waitingForContext ? (
        <StageViewLoading description="正在读取代码上下文" />
      ) : lines.length ? (
        <MonacoEditor
          ariaLabel="Finding 代码证据"
          className={styles.findingCodeEditor}
          height="100%"
          highlightedLineNumbers={highlightedLineNumbers}
          highlightedRanges={highlightedRanges}
          language={sourceLanguage(location?.file ?? codeContext?.filePath)}
          lineNumbers={lines.map((line) => line.lineNumber)}
          readOnly
          revealLineKey={`${location?.file ?? codeContext?.filePath ?? 'source'}:${firstHighlightedLine?.lineNumber ?? 'initial'}`}
          revealLineNumber={highlightedLineNumbers[0]}
          value={lines.map((line) => line.text).join('\n')}
          wordWrap="off"
        />
      ) : (
        <ContentState
          kind={preferCodeContext && codeContextError ? 'error' : 'empty'}
          description={
            preferCodeContext && codeContextError
              ? '代码上下文读取失败，其余判断依据仍可继续查看。'
              : '该命中位置没有可用的代码上下文。'
          }
          compact
        />
      )}
      {preferCodeContext && codeContextError && lines.length ? (
        <Alert
          className={styles.findingInlineNotice}
          description="当前展示扫描器保存的局部片段。"
          message="代码上下文读取失败，其余判断依据仍可继续查看。"
          showIcon
          type="warning"
        />
      ) : null}
      {location?.context?.budget?.truncated ? (
        <Alert
          className={styles.findingInlineNotice}
          message="代码上下文已按扫描预算截断"
          showIcon
          type="warning"
        />
      ) : null}
    </div>
  )
}

export function EvidencePath({
  model,
  selectedIndex,
  onSelect
}: {
  model: Pick<FindingDetailModel, 'evidenceChain' | 'evidencePath'>
  selectedIndex: number | null
  onSelect: (index: number) => void
}) {
  const { evidenceChain, evidencePath } = model

  return (
    <section className={styles.evidencePathPanel} aria-labelledby="finding-evidence-title">
      <div className={styles.findingSectionHeading}>
        <div>
          <div>
            <h3 id="finding-evidence-title">
              <BranchesOutlined aria-hidden="true" />
              判断依据
            </h3>
            <p>从扫描事实到迁移结论的可追溯路径</p>
          </div>
        </div>
        {completionTag(evidenceChain.status)}
      </div>
      {evidencePath.warnings?.map((warning) => (
        <Alert key={warning} type="warning" showIcon title={warning} />
      ))}
      {evidencePath.budgetTruncated ? (
        <Alert type="warning" title="证据链已按分析预算截断" />
      ) : null}
      {evidencePath.steps.length ? (
        <ol className={styles.evidencePath}>
          {evidencePath.steps.slice(0, evidencePath.primaryStepCount).map((step, index) => (
            <li key={step.id ?? `${step.kind}-${index}`}>
              <span className={styles.evidenceStepMarker} aria-hidden="true" />
              <button
                aria-label={`${getEvidenceKindLabel(step.kind)}：${step.title ?? '未提供步骤标题'}`}
                aria-pressed={selectedIndex === index}
                className={selectedIndex === index ? styles.evidenceStepSelected : undefined}
                type="button"
                onClick={() => onSelect(index)}
              >
                <div className={styles.evidenceStepContent}>
                  <div>
                    <span>{getEvidenceKindLabel(step.kind)}</span>
                    {completionTag(step.status)}
                  </div>
                  <strong title={step.title ?? '未提供步骤标题'}>
                    {step.title ?? '未提供步骤标题'}
                  </strong>
                  <p title={step.reason ?? '未提供该步骤的判断说明。'}>
                    {step.reason ?? '未提供该步骤的判断说明。'}
                  </p>
                  <div className={styles.evidenceStepFooter}>
                    <span />
                    <small>{step.source?.context?.lines?.length ? '查看源码' : '查看说明'}</small>
                  </div>
                </div>
              </button>
            </li>
          ))}
        </ol>
      ) : (
        <div className={styles.findingNeutralNotice}>
          <strong>暂无结构化证据路径</strong>
          <span>仍可根据代码位置、扫描事实和原始扫描数据核对该结论。</span>
        </div>
      )}
      {evidencePath.primaryStepCount !== undefined &&
      evidencePath.steps.length > evidencePath.primaryStepCount ? (
        <details>
          <summary>分支与断点</summary>
          {evidencePath.steps.slice(evidencePath.primaryStepCount).map((step, offset) => (
            <div key={step.id}>
              <Button type="link" onClick={() => onSelect(evidencePath.primaryStepCount! + offset)}>
                {step.title || step.id}
              </Button>
              <p>{step.reason || '未提供说明'}</p>
            </div>
          ))}
        </details>
      ) : null}
      {evidencePath.branches?.length ? (
        <details>
          <summary>证据关系分支</summary>
          <ul>
            {evidencePath.branches.map((edge, i) => (
              <li key={`${edge.from}:${edge.to}:${i}`}>
                {edge.from} → {edge.to}（{edge.kind}）
              </li>
            ))}
          </ul>
        </details>
      ) : null}
    </section>
  )
}
