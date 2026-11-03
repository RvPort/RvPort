import { act, fireEvent, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { renderWithQueryClient } from '@/test/test-utils'
import type { ProjectListItem } from '@/types/project'

const { createProject, feedback } = vi.hoisted(() => ({
  createProject: vi.fn(),
  feedback: { success: vi.fn(), error: vi.fn() }
}))

vi.mock('@/api/project/projects', () => ({ createProject }))
vi.mock('@/utils/feedback', () => ({ feedback }))

import CreateProjectModal, { type CreateProjectAction } from './index'

const project: ProjectListItem = {
  activeTask: null,
  attention: null,
  buildSummary: null,
  createdAt: '2026-08-31T00:00:00Z',
  currentBaseline: null,
  id: 'project-1',
  lastActivityAt: null,
  latestBuildStatus: null,
  latestScanAt: null,
  latestTestStatus: null,
  name: '新迁移项目',
  primaryAction: null,
  reportSummary: null,
  riskSummary: null,
  sourceArch: 'x86_64',
  stage: 'PROJECT_CREATED',
  status: 'ACTIVE',
  summaryAvailable: false,
  targetArch: 'riscv64',
  testSummary: null
}

describe('CreateProjectModal', () => {
  it('keeps a failed creation retryable, reports non-Error failures and submits keyboard form actions with the latest callbacks', async () => {
    createProject.mockRejectedValueOnce('unavailable').mockResolvedValue(project)
    const created = vi.fn(),
      cancel = vi.fn()
    const view = renderWithQueryClient(
      <CreateProjectModal visible onCancel={cancel} onCreated={created} />
    )
    fireEvent.change(screen.getByLabelText('项目名称'), { target: { value: 'retry-project' } })
    fireEvent.change(screen.getByLabelText('项目描述'), { target: { value: '  项目描述  ' } })
    fireEvent.submit(screen.getByLabelText('项目名称').closest('form')!)
    await waitFor(() => expect(feedback.error).toHaveBeenCalledWith('项目创建失败'))
    await waitFor(() => expect(screen.getByLabelText('项目名称')).toBeEnabled())
    const nextCreated = vi.fn(),
      nextCancel = vi.fn()
    view.rerender(<CreateProjectModal visible onCancel={nextCancel} onCreated={nextCreated} />)
    fireEvent.submit(screen.getByLabelText('项目名称').closest('form')!)
    await waitFor(() => expect(nextCreated).toHaveBeenCalledWith(project, 'open-workbench'))
    expect(createProject.mock.calls.at(-1)?.[0]).toEqual({
      name: 'retry-project',
      description: '项目描述',
      sourceArch: 'x86_64',
      targetArch: 'riscv64'
    })
    expect(created).not.toHaveBeenCalled()
    await waitFor(() => expect(screen.getByRole('button', { name: /取\s*消/ })).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: /取\s*消/ }))
    expect(nextCancel).toHaveBeenCalledOnce()
    expect(cancel).not.toHaveBeenCalled()
  })
  it.each([
    ['创建并进入工作台', 'open-workbench'],
    ['创建并开始配置环境', 'configure-environment']
  ] as const)('keeps the %s label aligned with its destination action', async (label, action) => {
    createProject.mockResolvedValue(project)
    const onCreated = vi.fn<(project: ProjectListItem, action: CreateProjectAction) => void>()
    const user = userEvent.setup()

    renderWithQueryClient(<CreateProjectModal visible onCancel={vi.fn()} onCreated={onCreated} />)

    await user.type(screen.getByLabelText('项目名称'), '新迁移项目')
    await user.click(screen.getByRole('button', { name: label }))

    await waitFor(() => expect(onCreated).toHaveBeenCalledWith(project, action))
    expect(createProject.mock.calls[0]?.[0]).toEqual(
      expect.objectContaining({ name: '新迁移项目', sourceArch: 'x86_64', targetArch: 'riscv64' })
    )
  })
  it('locks validation and captures the first destination before a repeated click', async () => {
    let finish!: (value: ProjectListItem) => void
    createProject.mockReturnValue(
      new Promise((resolve) => {
        finish = resolve
      })
    )
    const onCreated = vi.fn()
    const user = userEvent.setup()
    renderWithQueryClient(<CreateProjectModal visible onCancel={vi.fn()} onCreated={onCreated} />)
    await user.type(screen.getByLabelText('项目名称'), 'protected')
    fireEvent.click(screen.getByRole('button', { name: '创建并进入工作台' }))
    fireEvent.click(screen.getByRole('button', { name: /创建并开始配置环境/ }))
    await waitFor(() => expect(createProject).toHaveBeenCalledOnce())
    expect(screen.getByLabelText('项目名称')).toBeDisabled()
    await act(async () => {
      finish(project)
    })
    expect(onCreated).toHaveBeenCalledWith(project, 'open-workbench')
  })
  it('ignores a delayed creation after the modal closes and reopens', async () => {
    let finish!: (value: ProjectListItem) => void
    createProject.mockReturnValue(
      new Promise((resolve) => {
        finish = resolve
      })
    )
    const onCreated = vi.fn(),
      onCancel = vi.fn()
    const { rerender } = renderWithQueryClient(
      <CreateProjectModal visible onCancel={onCancel} onCreated={onCreated} />
    )
    fireEvent.change(screen.getByLabelText('项目名称'), { target: { value: 'old' } })
    fireEvent.click(screen.getByRole('button', { name: '创建并进入工作台' }))
    await waitFor(() => expect(createProject).toHaveBeenCalledOnce())
    rerender(<CreateProjectModal visible={false} onCancel={onCancel} onCreated={onCreated} />)
    rerender(<CreateProjectModal visible onCancel={onCancel} onCreated={onCreated} />)
    await act(async () => {
      finish(project)
    })
    expect(onCreated).not.toHaveBeenCalled()
    expect(feedback.success).not.toHaveBeenCalled()
  })
})
