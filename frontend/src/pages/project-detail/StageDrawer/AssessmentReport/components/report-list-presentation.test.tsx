import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ConfigProvider } from 'antd'
import type { ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'

import {
  FindingTooltip,
  FindingTypeFilter,
  ReportActions,
  SummaryFact,
  TypeCounts
} from './report-list-presentation'

const NoMotion = ({ children }: { children: ReactNode }) => (
  <ConfigProvider theme={{ token: { motion: false } }}>{children}</ConfigProvider>
)

describe('report list presentation', () => {
  it('confirms generation and dispatches downloads to the current handlers even after permissions change', async () => {
    const onCreate = vi.fn(),
      onDownload = vi.fn()
    const props = { canGenerate: true, isCreating: false, onCreate, onDownload }
    const view = render(<ReportActions {...props} />, { wrapper: NoMotion })
    fireEvent.click(screen.getByRole('button', { name: '下载 HTML' }))
    fireEvent.click(screen.getByRole('button', { name: '下载 JSON' }))
    expect(onDownload.mock.calls).toEqual([['HTML'], ['JSON']])
    await userEvent.click(screen.getByRole('button', { name: '重新生成报告' }))
    expect(onCreate).not.toHaveBeenCalled()
    await userEvent.click(await screen.findByRole('button', { name: '确认重新生成' }))
    expect(onCreate).toHaveBeenCalledOnce()
    const next = vi.fn()
    view.rerender(<ReportActions {...props} onDownload={next} />)
    fireEvent.click(screen.getByRole('button', { name: '下载 JSON' }))
    expect(next).toHaveBeenCalledWith('JSON')
    view.rerender(<ReportActions {...props} onDownload={next} isCreating />)
    expect(screen.getByRole('button', { name: /重新生成报告/ })).toHaveClass('ant-btn-loading')
    view.rerender(<ReportActions {...props} canGenerate={false} />)
    expect(screen.queryByRole('button', { name: '重新生成报告' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '下载 HTML' })).toBeEnabled()
  })

  it('sorts type counts before limiting and handles empty and exact-limit summaries', () => {
    const view = render(<TypeCounts counts={{}} />)
    expect(screen.getByText('暂无类型统计')).toBeInTheDocument()
    view.rerender(<TypeCounts counts={{ first: 1, second: 3, third: 2 }} limit={2} />)
    expect(screen.getByText('另有 1 种')).toBeInTheDocument()
    expect(screen.getByText('second')).toHaveTextContent('second')
    expect(screen.queryByText('first')).not.toBeInTheDocument()
    view.rerender(<TypeCounts counts={{ first: 1, second: 3 }} limit={2} />)
    expect(screen.queryByText(/另有/)).not.toBeInTheDocument()
    view.rerender(<TypeCounts counts={{ first: 1, second: 3 }} />)
    expect(screen.getByText('first')).toBeInTheDocument()
    view.rerender(<SummaryFact label="来源" value="当前源码" />)
    view.rerender(<SummaryFact label="版本" value="v2" primary />)
    expect(screen.getByText('版本').parentElement).toHaveTextContent('v2')
    expect(screen.queryByText('当前源码')).not.toBeInTheDocument()
  })

  it('maps all-types to an absent filter and updates option counts and callbacks independently', async () => {
    const onChange = vi.fn()
    const props = {
      ariaLabel: '问题筛选',
      findingCount: 7,
      options: [{ type: 'custom', count: 3 }],
      onChange
    }
    const view = render(<FindingTypeFilter {...props} />, { wrapper: NoMotion })
    await userEvent.click(screen.getByRole('combobox', { name: '问题筛选' }))
    await userEvent.click(screen.getByTitle('custom（3）'))
    expect(onChange).toHaveBeenCalledWith('custom')
    view.rerender(<FindingTypeFilter {...props} value="custom" />)
    const next = vi.fn()
    view.rerender(<FindingTypeFilter {...props} value="custom" onChange={next} />)
    await userEvent.click(screen.getByRole('combobox', { name: '问题筛选' }))
    await userEvent.click(screen.getByTitle('全部问题（7）'))
    expect(next).toHaveBeenCalledWith(undefined)
    view.rerender(
      <FindingTypeFilter
        {...props}
        findingCount={9}
        options={[{ type: 'next', count: 5 }]}
        ariaLabel="新筛选"
      />
    )
    expect(screen.getByRole('combobox', { name: '新筛选' })).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: '新筛选' }).parentElement).toHaveAttribute(
      'title',
      '全部问题（9）'
    )
  })

  it('keeps a controlled evidence tooltip inside its pane and suppresses disabled hover events', async () => {
    const onOpenChange = vi.fn()
    const view = render(
      <div data-finding-pane>
        <FindingTooltip
          activeId="evidence"
          id="evidence"
          title="源码证据"
          onOpenChange={onOpenChange}
        >
          <span>代码片段</span>
        </FindingTooltip>
      </div>,
      { wrapper: NoMotion }
    )
    const tooltip = await screen.findByRole('tooltip')
    expect(tooltip.closest('[data-finding-pane]')).not.toBeNull()
    view.rerender(
      <FindingTooltip
        activeId="evidence"
        id="evidence"
        title="源码证据"
        disabled
        onOpenChange={onOpenChange}
      >
        <span>代码片段</span>
      </FindingTooltip>
    )
    await userEvent.hover(screen.getByText('代码片段'))
    await waitFor(() => expect(screen.queryByRole('tooltip')).not.toBeInTheDocument())
    expect(onOpenChange).not.toHaveBeenCalled()
    const next = vi.fn()
    view.rerender(
      <FindingTooltip id="next" title="新证据" onOpenChange={next}>
        <span>代码片段</span>
      </FindingTooltip>
    )
    await userEvent.unhover(screen.getByText('代码片段'))
    await userEvent.hover(screen.getByText('代码片段'))
    await waitFor(() => expect(next).toHaveBeenCalledWith('next', true))
  })
})
