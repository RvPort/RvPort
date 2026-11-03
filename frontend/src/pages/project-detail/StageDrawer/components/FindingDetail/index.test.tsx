import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ComponentProps } from 'react'
import { describe, expect, it, vi } from 'vitest'

import { createKnowledgeFinding } from '@/test/knowledge-base-fixtures'

import { FindingDetail } from '.'

vi.mock('@/components/MonacoEditor', () => ({
  default: ({ value }: { value: string }) => <pre aria-label="代码证据内容">{value}</pre>
}))
vi.mock('@/components/CodeMirrorEditor', () => ({
  default: ({ value }: { value: string }) => <pre aria-label="原始数据内容">{value}</pre>
}))

describe('finding evidence workspace', () => {
  it('updates independent conclusion fields and optional AI evidence while preserving read-only status', () => {
    let detail = createKnowledgeFinding()
    const view = render(<FindingDetail detail={detail} loading={false} />)
    const changes: Array<[Partial<typeof detail>, string]> = [
      [{ title: '新判断标题' }, '新判断标题'],
      [{ description: '刷新判断原因' }, '刷新判断原因'],
      [{ severity: 'critical' }, '严重'],
      [{ suggestion: '新的处理建议' }, '新的处理建议'],
      [{ migrationInputStatus: 'CONFIRMED' }, '已纳入报告'],
      [{ conclusion: { confidence: 0.75, blocking: true } }, '75%']
    ]
    for (const [patch, expected] of changes) {
      detail = { ...detail, ...patch }
      view.rerender(<FindingDetail detail={detail} loading={false} />)
      expect(screen.getByText(expected)).toBeInTheDocument()
      expect(screen.getByText('历史记录 · 只读')).toBeInTheDocument()
    }
    detail = {
      ...detail,
      aiExtension: {
        id: 'ai',
        runId: 'run',
        findingId: detail.id,
        aiSummary: null,
        aiSuggestion: null,
        confidence: null,
        actionType: null,
        rawPayload: {},
        createdAt: '2026-10-28T00:00:00Z'
      }
    }
    view.rerender(<FindingDetail detail={detail} loading={false} />)
    expect(screen.getByText('没有补充说明。')).toBeInTheDocument()
    expect(screen.getByText('没有补充建议。')).toBeInTheDocument()
    detail = {
      ...detail,
      aiExtension: {
        ...detail.aiExtension!,
        aiSummary: 'AI 诊断',
        aiSuggestion: 'AI 建议',
        confidence: 0.8
      }
    }
    view.rerender(
      <FindingDetail
        detail={detail}
        loading={false}
        showMigrationInputStatus={false}
        reportActions={<button>报告操作</button>}
      />
    )
    expect(screen.getByText('AI 诊断')).toBeInTheDocument()
    expect(screen.getByText(/置信度 80%/)).toBeInTheDocument()
    expect(screen.queryByText('历史记录 · 只读')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '报告操作' })).toBeInTheDocument()
  })

  it('resets occurrence and evidence selection when another finding replaces the current detail', async () => {
    const location = (file: string, text: string) => ({
      file,
      context: { lines: [{ line: 8, text }] }
    })
    const detail = createKnowledgeFinding({
      occurrences: [
        {
          id: 'first',
          kind: 'intrinsic_call',
          selectionStatus: 'selected',
          location: location('src/one.c', 'primary source')
        },
        {
          id: 'second',
          kind: 'intrinsic_call',
          configuration: 'debug',
          reasonCodes: ['target_selected'],
          location: location('src/two.c', 'second source')
        }
      ],
      evidenceChain: {
        steps: [
          {
            id: 'source',
            kind: 'source',
            title: '证据代码',
            source: location('src/evidence.c', 'evidence source')
          },
          { id: 'reason', kind: 'conclusion', title: '判断条件', reason: '确认调用条件' }
        ]
      }
    })
    const props: ComponentProps<typeof FindingDetail> = {
      detail,
      loading: false,
      showMigrationInputStatus: false
    }
    const view = render(<FindingDetail {...props} />)
    await userEvent.click(screen.getByRole('combobox', { name: '选择 Finding 命中位置' }))
    await userEvent.click(screen.getByTitle('位置 2 · src/two.c'))
    expect(screen.getByLabelText('代码证据内容')).toHaveTextContent('second source')
    await userEvent.click(screen.getByRole('button', { name: /证据代码/ }))
    expect(screen.getByLabelText('代码证据内容')).toHaveTextContent('evidence source')
    await userEvent.click(screen.getByRole('button', { name: /判断条件/ }))
    expect(screen.getByLabelText('代码证据内容')).toHaveTextContent('evidence source')
    expect(screen.getByText('所选判断没有直接源码，继续保留最近一次有效代码。')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '返回主要代码' }))
    expect(screen.getByLabelText('代码证据内容')).toHaveTextContent('second source')
    view.rerender(<FindingDetail {...props} detail={{ ...detail, id: 'finding-next' }} />)
    expect(screen.getByLabelText('代码证据内容')).toHaveTextContent('primary source')
    fireEvent.click(screen.getByText('原始扫描数据').closest('summary')!)
    await waitFor(() => expect(screen.getByLabelText('原始数据内容')).toHaveTextContent('{}'))
    view.rerender(<FindingDetail loading={true} detail={detail} />)
    expect(screen.getByText('正在加载 Finding 详情')).toBeInTheDocument()
    view.rerender(<FindingDetail loading={false} />)
    expect(screen.getByText('正在加载 Finding 详情')).toBeInTheDocument()
  })
})
