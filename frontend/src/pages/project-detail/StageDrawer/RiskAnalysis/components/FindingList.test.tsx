import { screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { getSpecializedFindings } from '@/api/project/specialized-analysis'
import { renderWithStrictQueryClient } from '@/test/strict-mode-utils'

import { finding, run } from '../fixtures.test-support'
import FindingList from './FindingList'

vi.mock('@/api/project/specialized-analysis')

beforeEach(() => {
  vi.mocked(getSpecializedFindings).mockResolvedValue({
    items: [{ ...finding, findingKind: 'SOURCE_CANDIDATE', severity: 'MEDIUM' }],
    total: 1,
    page: 1,
    pageSize: 20,
    hasMore: false
  })
})

describe('专项分析的筛选边界', () => {
  it('内存序忽略旧等级筛选，仅保留服务端搜索与分页，候选仍显示为提示', async () => {
    renderWithStrictQueryClient(
      <FindingList
        run={run}
        projectId="project"
        stageId="memory-order-analysis"
        search="publication"
        severity="medium"
        onFiltersChange={vi.fn()}
        onSelect={vi.fn()}
      />
    )
    expect(await screen.findByRole('button', { name: finding.title! })).toBeVisible()
    expect(screen.getByText('提示')).toBeVisible()
    expect(screen.queryByText('中风险')).not.toBeInTheDocument()
    expect(screen.queryByRole('combobox', { name: '风险等级' })).not.toBeInTheDocument()
    expect(screen.getByLabelText('搜索风险')).toBeVisible()
    expect(getSpecializedFindings).toHaveBeenCalledWith(
      'project',
      'memory-order-analysis',
      run.id,
      expect.objectContaining({
        keyword: 'publication',
        severity: undefined,
        page: 1,
        pageSize: 20
      })
    )
  })
  it('浮点保留服务端等级筛选，包含 critical', async () => {
    vi.mocked(getSpecializedFindings).mockResolvedValue({
      items: [{ ...finding, analyzerType: 'FLOATING_POINT', severity: 'CRITICAL' }],
      total: 1,
      page: 1,
      pageSize: 20,
      hasMore: false
    })
    renderWithStrictQueryClient(
      <FindingList
        run={{ ...run, analyzerType: 'FLOATING_POINT' }}
        projectId="project"
        stageId="floating-point-analysis"
        search=""
        severity="critical"
        onFiltersChange={vi.fn()}
        onSelect={vi.fn()}
      />
    )
    expect(screen.getByRole('combobox', { name: '风险等级' })).toBeVisible()
    await waitFor(() =>
      expect(getSpecializedFindings).toHaveBeenCalledWith(
        'project',
        'floating-point-analysis',
        run.id,
        expect.objectContaining({ severity: 'CRITICAL' })
      )
    )
    const row = (await screen.findByRole('button', { name: finding.title! })).closest('tr')!
    expect(within(row).getByText('严重风险')).toBeVisible()
  })
})
