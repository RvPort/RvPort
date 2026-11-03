import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { type BackendProject, getProject, updateProject } from '@/api/project/projects'
import { renderWithQueryClient } from '@/test/test-utils'

import { projectQueryKeys } from '../../queryKeys'
import { StagePresentationContext } from '../components/StagePresentationContext'
import Project from './index'

vi.mock('@/api/project/projects', () => ({ getProject: vi.fn(), updateProject: vi.fn() }))

const project: BackendProject = {
  id: 'project-1',
  name: '迁移项目',
  description: '原描述',
  sourceArch: 'x86_64',
  targetArch: 'riscv64',
  stage: 'PROJECT_CREATED',
  status: 'ACTIVE',
  sourceInputType: null,
  createdBy: 'admin',
  createdAt: '2026-09-29T00:00:00Z',
  updatedAt: null
}

describe('Project description submission', () => {
  it('refreshes independent project facts, discards cancelled drafts and forwards current stage navigation', async () => {
    vi.mocked(getProject).mockResolvedValue(project)
    const navigate = vi.fn()
    const tree = (onStageChange = navigate) => (
      <StagePresentationContext.Provider value="panel">
        <Project projectId="project-1" visible onClose={vi.fn()} onStageChange={onStageChange} />
      </StagePresentationContext.Provider>
    )
    const view = renderWithQueryClient(tree())
    await screen.findByText('迁移项目')
    let current = project
    const changes: Array<[Partial<BackendProject>, string]> = [
      [{ name: '刷新项目名' }, '刷新项目名'],
      [{ sourceArch: 'riscv64' }, 'riscv64'],
      [{ targetArch: 'x86_64' }, 'x86_64'],
      [{ description: '刷新说明' }, '刷新说明']
    ]
    for (const [patch, expected] of changes) {
      current = { ...current, ...patch }
      act(() => view.queryClient.setQueryData(projectQueryKeys.detail('project-1'), current))
      await waitFor(() => expect(screen.getAllByText(expected).length).toBeGreaterThan(0))
    }
    await userEvent.click(screen.getByRole('button', { name: '编辑项目描述' }))
    expect(screen.getByRole('button', { name: '进入环境管理' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '进入源码管理' })).toBeDisabled()
    fireEvent.change(screen.getByRole('textbox'), { target: { value: '未保存草稿' } })
    await userEvent.click(screen.getByRole('button', { name: /取\s*消/ }))
    expect(screen.getByText('刷新说明')).toBeInTheDocument()
    expect(updateProject).not.toHaveBeenCalled()
    const next = vi.fn()
    view.rerender(tree(next))
    fireEvent.click(screen.getByRole('button', { name: '进入环境管理' }))
    fireEvent.click(screen.getByRole('button', { name: '进入源码管理' }))
    expect(next.mock.calls).toEqual([['env'], ['assets']])
    expect(navigate).not.toHaveBeenCalled()
  })

  it('does not apply an old project save to a different selected project', async () => {
    let finish!: (value: BackendProject) => void
    vi.mocked(getProject).mockImplementation(async (id) => ({
      ...project,
      id,
      name: id,
      description: id === 'project-1' ? null : '新项目描述'
    }))
    vi.mocked(updateProject).mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve
        })
    )
    const tree = (projectId: string) => (
      <StagePresentationContext.Provider value="panel">
        <Project projectId={projectId} visible onClose={vi.fn()} />
      </StagePresentationContext.Provider>
    )
    const view = renderWithQueryClient(tree('project-1'))
    await userEvent.click(await screen.findByRole('button', { name: '编辑项目描述' }))
    fireEvent.change(screen.getByRole('textbox'), { target: { value: '旧请求描述' } })
    await userEvent.click(screen.getByRole('button', { name: /保\s*存/ }))
    await userEvent.click(await screen.findByRole('button', { name: '确认保存' }))
    await waitFor(() => expect(updateProject).toHaveBeenCalledOnce())
    view.rerender(tree('project-2'))
    await screen.findByText('project-2')
    await act(async () => finish({ ...project, description: '旧请求描述' }))
    expect(
      view.queryClient.getQueryData<BackendProject>(projectQueryKeys.detail('project-2'))
        ?.description
    ).toBe('新项目描述')
    expect(
      view.queryClient.getQueryData<BackendProject>(projectQueryKeys.detail('project-1'))
        ?.description
    ).toBeNull()
  })

  it('shows a failed project read without enabling editing', async () => {
    vi.mocked(getProject).mockRejectedValueOnce(new Error('offline'))
    renderWithQueryClient(
      <StagePresentationContext.Provider value="panel">
        <Project projectId="project-1" visible presentation="panel" onClose={vi.fn()} />
      </StagePresentationContext.Provider>
    )
    expect(await screen.findByText('项目基础配置加载失败')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '编辑项目描述' })).not.toBeInTheDocument()
  })
  it('locks editing while saving and displays the submitted description after success', async () => {
    const user = userEvent.setup()
    let resolveRequest!: (value: BackendProject) => void
    vi.mocked(getProject).mockResolvedValue(project)
    vi.mocked(updateProject).mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveRequest = resolve
        })
    )
    renderWithQueryClient(
      <StagePresentationContext.Provider value="panel">
        <Project projectId="project-1" visible presentation="panel" onClose={vi.fn()} />
      </StagePresentationContext.Provider>
    )
    await user.click(await screen.findByRole('button', { name: '编辑项目描述' }))
    const input = screen.getByRole('textbox')
    await user.clear(input)
    await user.type(input, '已提交描述')
    await user.click(screen.getByRole('button', { name: /保\s*存/ }))
    await user.click(await screen.findByRole('button', { name: '确认保存' }))
    await waitFor(() => expect(input).toBeDisabled())
    expect(within(input.closest('form')!).getByRole('button', { name: /取\s*消/ })).toBeDisabled()
    expect(updateProject).toHaveBeenCalledWith('project-1', { description: '已提交描述' })
    resolveRequest({ ...project, description: '已提交描述' })
    await waitFor(() => expect(screen.queryByRole('textbox')).not.toBeInTheDocument())
    expect(screen.getByText('已提交描述')).toBeVisible()
  })
})
