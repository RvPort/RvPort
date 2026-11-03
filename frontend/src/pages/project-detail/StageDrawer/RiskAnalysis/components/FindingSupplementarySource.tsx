import { useState } from 'react'

import type { RiskSourceLocation } from '../evidencePresentation'
import type { RiskFinding } from '../types'
import FindingSource, { type FindingSourceState } from './FindingSource'

// 现有 API 不提供分析快照 digest，只能作为独立的补充阅读材料。
export default function FindingSupplementarySource({
  finding,
  location,
  source
}: {
  finding: Pick<RiskFinding, 'id' | 'runId' | 'location' | 'line'>
  location?: RiskSourceLocation
  source?: FindingSourceState
}) {
  const [open, setOpen] = useState(false)
  const data = source?.data
  const file = location?.file ?? finding.location
  const start =
    location?.span?.start?.line ?? (file === finding.location ? finding.line : undefined)
  const end = location?.span?.end?.line ?? start
  const covered = new Set(
    data?.lines
      .filter((line) => start && end && line.lineNumber >= start && line.lineNumber <= end)
      .map((line) => line.lineNumber)
  )
  if (
    !data ||
    !finding.runId ||
    data.findingId !== finding.id ||
    data.runId !== finding.runId ||
    !source?.expectedSourceRevisionId ||
    data.sourceRevisionId !== source.expectedSourceRevisionId ||
    data.filePath !== file ||
    !start ||
    !end ||
    !Number.isInteger(start) ||
    !Number.isInteger(end) ||
    start < 1 ||
    end < start ||
    covered.size !== end - start + 1
  )
    return null

  return (
    <details open={open} onToggle={(event) => setOpen(event.currentTarget.open)}>
      <summary>查看补充源码（未校验分析 digest）</summary>
      <p>
        此片段来自该执行绑定的源码版本，仅供阅读；接口未提供分析快照
        digest，不能替代分析时源码证据，也不用于步骤或位置切换。
      </p>
      {open ? <FindingSource finding={finding} source={source} ariaLabel="补充源码" /> : null}
    </details>
  )
}
