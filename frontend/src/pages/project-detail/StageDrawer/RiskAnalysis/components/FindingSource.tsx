import ContentState from '@/components/ContentState'
import MonacoEditor from '@/components/MonacoEditor'

import type { RiskFinding } from '../types'

import evidenceStyles from '../../components/FindingDetail/index.module.scss'
import styles from './FindingSource.module.scss'

// 同时承接详情接口内嵌的主位置上下文与证据步骤自带的片段。
export interface FindingSourceState {
  data?: {
    findingId: string
    runId?: string
    sourceRevisionId?: string
    filePath: string
    lines: { lineNumber: number; text: string; highlighted: boolean }[]
  }
  unavailableReason?: string
  expectedSourceRevisionId?: string
}

const unavailableReasons: Record<string, string> = {
  SOURCE_EVIDENCE_IDENTITY_UNVERIFIED:
    '源码片段的 Finding、文件、digest 或位置范围未匹配，不能作为当前证据。',
  SOURCE_REVISION_NOT_FOUND: '本次分析绑定的源码版本不存在。',
  FINDING_SOURCE_LOCATION_UNAVAILABLE:
    '本条结果没有有效的源码位置，或行号范围已超出文件。旧结果可尝试重新扫描。',
  FINDING_SOURCE_FILE_NOT_FOUND: '本次分析对应的源码文件或工作副本已不可用。',
  FINDING_SOURCE_PATH_INVALID: '源码路径无效，无法读取上下文。',
  FINDING_SOURCE_FILE_UNREADABLE: '源码文件无法读取，或不是有效的 UTF-8 文本。',
  FINDING_SOURCE_FILE_TOO_LARGE: '源码文件超过 8 MiB，无法读取上下文。'
}

export default function FindingSource({
  finding,
  source,
  ariaLabel = '源码证据'
}: {
  finding: Pick<RiskFinding, 'id' | 'location'>
  source?: FindingSourceState
  ariaLabel?: string
}) {
  // 切换结果时不能把上一条 finding 的缓存片段当作当前证据。
  const data = source?.data?.findingId === finding.id ? source.data : undefined
  const lines = data?.lines ?? []
  const highlights = lines.flatMap((line, index) => (line.highlighted ? [index + 1] : []))
  if (!lines.length) {
    return (
      <ContentState
        compact
        description={
          data || source?.unavailableReason
            ? '本条结果没有可用的源码上下文'
            : '分析结果未提供源码片段'
        }
        details={
          source?.unavailableReason
            ? (unavailableReasons[source.unavailableReason] ??
              `源码上下文暂不可用（${source.unavailableReason}）。`)
            : finding.location === '未提供位置'
              ? '本条结果未提供源码位置；下方保留工具返回的原始证据。'
              : '可根据文件位置查看源码；下方保留工具返回的原始证据。'
        }
      />
    )
  }
  const extension = data?.filePath.split('.').pop()?.toLowerCase()
  const language = ['c', 'cc', 'cpp', 'cxx', 'h', 'hh', 'hpp', 'hxx'].includes(extension ?? '')
    ? 'cpp'
    : 'plaintext'
  return (
    <div className={styles.source}>
      <MonacoEditor
        key={finding.id}
        className={evidenceStyles.findingCodeEditor}
        ariaLabel={ariaLabel}
        height="100%"
        language={language}
        readOnly
        value={lines.map((line) => line.text).join('\n')}
        lineNumbers={lines.map((line) => line.lineNumber)}
        highlightedLineNumbers={highlights}
        revealLineKey={`${finding.id}:${data?.filePath}:${highlights.join(',')}`}
        revealLineNumber={highlights[0]}
        wordWrap="off"
      />
    </div>
  )
}
