import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { renderWithQueryClient } from '@/test/test-utils'
import type { ProjectDependencyAutoFixCandidate } from '@/types/project/dependency-analysis'

import DependencyAutoFixModal from './DependencyAutoFixModal'

const { createProjectDependencyAutoFix, getProjectDependencyAutoFixCandidates } = vi.hoisted(
  () => ({
    createProjectDependencyAutoFix: vi.fn(),
    getProjectDependencyAutoFixCandidates: vi.fn()
  })
)

vi.mock('@/api/project/dependency-analysis', () => ({
  createProjectDependencyAutoFix,
  getProjectDependencyAutoFixCandidates
}))

describe('DependencyAutoFixModal', () => {
  it('submits only selected dependencies, locks duplicate operations and resets selection after success', async () => {
    const candidate: ProjectDependencyAutoFixCandidate = {
      resultItemId: 'zlib',
      dependencyType: 'LIBRARY',
      dependencyName: 'zlib',
      dependencyVersion: '1.2',
      riskLevel: 'HIGH',
      targetMatchStatus: 'REPO_AVAILABLE',
      sourceProviderPackage: null,
      sourceProviderVersion: null,
      targetProviderPackage: null,
      targetProviderVersion: null,
      installPackageName: 'zlib-dev',
      installVersionConstraint: '>=1.2',
      defaultSelected: true,
      selectable: true,
      reason: ''
    }
    getProjectDependencyAutoFixCandidates.mockResolvedValue({
      items: [
        candidate,
        {
          ...candidate,
          resultItemId: 'other',
          dependencyName: 'other-lib',
          dependencyVersion: null,
          riskLevel: 'CUSTOM',
          installPackageName: 'other-dev',
          installVersionConstraint: null,
          defaultSelected: false,
          reason: '有可用替代包'
        }
      ],
      page: 1,
      pageSize: 200,
      total: 2,
      hasMore: false
    })
    let finish!: (value: { autoFixRunId: string; selectedCount: number; taskId: string }) => void
    createProjectDependencyAutoFix.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        })
    )
    const close = vi.fn()
    const view = renderWithQueryClient(
      <DependencyAutoFixModal analysisId="analysis-1" open onClose={close} />
    )
    const zlib = await screen.findByText('zlib')
    expect(screen.getByText('zlib-dev >=1.2')).toBeInTheDocument()
    expect(screen.getByText('other-dev')).toBeInTheDocument()
    expect(screen.getByText('CUSTOM')).toBeInTheDocument()
    const zlibCheckbox = within(zlib.closest('tr')!).getByRole('checkbox')
    const otherCheckbox = within(screen.getByText('other-lib').closest('tr')!).getByRole('checkbox')
    expect(zlibCheckbox).toBeChecked()
    expect(otherCheckbox).not.toBeChecked()
    await userEvent.click(zlibCheckbox)
    expect(screen.getByRole('button', { name: '修复选中的依赖' })).toBeDisabled()
    await userEvent.click(otherCheckbox)
    await userEvent.click(screen.getByRole('checkbox', { name: '修复完成后重新执行依赖分析' }))
    const nextClose = vi.fn()
    view.rerender(<DependencyAutoFixModal analysisId="analysis-1" open onClose={nextClose} />)
    fireEvent.click(screen.getByRole('button', { name: '修复选中的依赖（1）' }))
    await waitFor(() => expect(createProjectDependencyAutoFix).toHaveBeenCalledOnce())
    expect(createProjectDependencyAutoFix).toHaveBeenCalledWith('analysis-1', {
      resultItemIds: ['other'],
      rerunDependencyAnalysis: false
    })
    for (const checkbox of screen.getAllByRole('checkbox')) expect(checkbox).toBeDisabled()
    expect(screen.getByRole('button', { name: /取\s*消/ })).toBeDisabled()
    await act(async () => finish({ autoFixRunId: 'fix-1', selectedCount: 1, taskId: 'task-1' }))
    expect(nextClose).toHaveBeenCalledOnce()
    expect(close).not.toHaveBeenCalled()
    expect(screen.getByRole('checkbox', { name: '修复完成后重新执行依赖分析' })).toBeChecked()
    expect(within(screen.getByText('zlib').closest('tr')!).getByRole('checkbox')).toBeChecked()
  })
  beforeEach(() => {
    vi.clearAllMocks()
    createProjectDependencyAutoFix.mockResolvedValue({
      autoFixRunId: 'fix-1',
      selectedCount: 1,
      taskId: 'task-1'
    })
  })

  it('disables submission when the analysis has no repairable candidates', async () => {
    getProjectDependencyAutoFixCandidates.mockResolvedValue({
      hasMore: false,
      items: [],
      page: 1,
      pageSize: 200,
      total: 0
    })

    renderWithQueryClient(<DependencyAutoFixModal analysisId="analysis-1" open onClose={vi.fn()} />)

    expect(await screen.findByText('本次分析没有可自动修复的依赖')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '修复选中的依赖' })).toBeDisabled()
  })

  it('shows a retry action when candidate loading fails', async () => {
    getProjectDependencyAutoFixCandidates
      .mockRejectedValueOnce(new Error('network error'))
      .mockResolvedValueOnce({ hasMore: false, items: [], page: 1, pageSize: 200, total: 0 })

    renderWithQueryClient(<DependencyAutoFixModal analysisId="analysis-1" open onClose={vi.fn()} />)

    expect(await screen.findByText('自动修复候选项加载失败')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '重新加载' }))

    expect(getProjectDependencyAutoFixCandidates).toHaveBeenCalledTimes(2)
  })
})
