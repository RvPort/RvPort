import { MemoryRouter, useLocation } from 'react-router'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { renderWithStrictQueryClient as renderWithQueryClient } from '@/test/strict-mode-utils'
import type { ProjectListItem } from '@/types/project'

const { fetchNextPage, useProjectList } = vi.hoisted(() => ({
  fetchNextPage: vi.fn(),
  useProjectList: vi.fn()
}))

vi.mock('./useProjectList', () => ({
  PROJECTS_QUERY_KEY: ['projects'],
  useProjectList
}))

vi.mock('./components/ProjectCard', () => ({
  default: ({ project }: { project: ProjectListItem }) => (
    <article aria-label={`${project.name} 卡片`}>{project.name}</article>
  ),
  ProjectCardSkeleton: () => <div>加载中</div>
}))

vi.mock('./components/CreateProjectModal', () => ({
  default: ({
    onCreated,
    visible
  }: {
    onCreated: (
      project: ProjectListItem,
      action: 'configure-environment' | 'open-workbench'
    ) => void
    visible: boolean
  }) =>
    visible ? (
      <div>
        <button onClick={() => onCreated(project, 'configure-environment')}>完成并配置环境</button>
        <button onClick={() => onCreated(project, 'open-workbench')}>完成并进入工作台</button>
      </div>
    ) : null
}))

import HomePage from './index'

const project: ProjectListItem = {
  activeTask: null,
  attention: null,
  buildSummary: null,
  createdAt: '2026-08-01T00:00:00Z',
  currentBaseline: null,
  id: 'project-1',
  lastActivityAt: null,
  latestBuildStatus: null,
  latestScanAt: null,
  latestTestStatus: null,
  name: '迁移项目',
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

function LocationProbe() {
  const location = useLocation()
  return <output aria-label="当前地址">{`${location.pathname}${location.search}`}</output>
}

function renderPage() {
  return renderWithQueryClient(
    <MemoryRouter initialEntries={['/home']}>
      <LocationProbe />
      <HomePage />
    </MemoryRouter>
  )
}

describe('HomePage', () => {
  beforeEach(() => {
    useProjectList.mockReturnValue({
      fetchNextPage,
      hasNextPage: true,
      isError: false,
      isFetchNextPageError: false,
      isFetchingNextPage: false,
      isLoading: false,
      projects: [project],
      refetch: vi.fn(),
      total: 42
    })
  })

  it('shows initial loading, allows retry after an initial failure, and preserves loaded cards during a background failure', async () => {
    const refetch = vi.fn()
    const state = { ...useProjectList(), refetch }
    useProjectList.mockReturnValue({ ...state, projects: [], total: 0, isLoading: true })
    const view = renderPage()
    expect(screen.getByLabelText('项目列表加载中')).toBeInTheDocument()
    expect(screen.queryByText('暂无项目，点击新建项目开始创建迁移项目')).not.toBeInTheDocument()
    useProjectList.mockReturnValue({ ...state, projects: [], total: 0, isError: true })
    view.rerender(
      <MemoryRouter>
        <LocationProbe />
        <HomePage />
      </MemoryRouter>
    )
    await userEvent.click(screen.getByRole('button', { name: /重\s*试/ }))
    expect(refetch).toHaveBeenCalledOnce()
    useProjectList.mockReturnValue({ ...state, isError: true })
    view.rerender(
      <MemoryRouter>
        <LocationProbe />
        <HomePage />
      </MemoryRouter>
    )
    expect(screen.getByRole('article', { name: '迁移项目 卡片' })).toBeInTheDocument()
    expect(screen.queryByText('项目列表加载失败，请稍后重试')).not.toBeInTheDocument()
  })

  it('does not enqueue another page while loading and maps all project status tabs to their filters', async () => {
    useProjectList.mockReturnValue({ ...useProjectList(), isFetchingNextPage: true })
    renderPage()
    const actor = userEvent.setup()
    const load = screen.getByRole('button', { name: '正在加载' })
    expect(load).toBeDisabled()
    await actor.click(load)
    expect(fetchNextPage).not.toHaveBeenCalled()
    for (const [name, status] of [
      ['进行中', 'ACTIVE'],
      ['已归档', 'ARCHIVED'],
      ['全部', undefined]
    ] as const) {
      await actor.click(screen.getByText(name, { selector: '.ant-segmented-item-label' }))
      expect(useProjectList).toHaveBeenLastCalledWith({ status })
    }
  })

  it('shows loaded and total counts with an explicit load-more action', async () => {
    const user = userEvent.setup()
    renderPage()

    expect(screen.getByText('已加载 1 个')).toBeInTheDocument()
    expect(screen.getByText('共 42 个项目')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '继续加载' }))
    expect(fetchNextPage).toHaveBeenCalled()
  })

  it('keeps loaded projects visible and provides retry when a later page fails', async () => {
    useProjectList.mockReturnValue({
      ...useProjectList(),
      isFetchNextPageError: true
    })
    const user = userEvent.setup()
    renderPage()

    expect(screen.getByRole('article', { name: '迁移项目 卡片' })).toBeInTheDocument()
    expect(screen.getByText('下一批项目暂时没有加载成功')).toBeInTheDocument()
    expect(screen.getByText('当前已显示的 1 个项目仍可正常使用')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '重试' }))
    expect(fetchNextPage).toHaveBeenCalled()
  })

  it('shows a concise terminal state when all projects are loaded', () => {
    useProjectList.mockReturnValue({
      ...useProjectList(),
      hasNextPage: false
    })
    renderPage()

    expect(screen.getByText('暂无更多数据')).toBeInTheDocument()
  })

  it('shows a directed empty state when no projects exist', () => {
    useProjectList.mockReturnValue({
      ...useProjectList(),
      hasNextPage: false,
      projects: [],
      total: 0
    })
    renderPage()

    expect(screen.getByText('暂无项目，点击新建项目开始创建迁移项目')).toBeInTheDocument()
    expect(screen.getByText('共 0 个项目')).toBeInTheDocument()
  })

  it('distinguishes filtered results and clears the active filters', async () => {
    useProjectList.mockReturnValue({
      ...useProjectList(),
      hasNextPage: false,
      projects: [],
      total: 0
    })
    const user = userEvent.setup()
    renderPage()
    await user.type(screen.getByPlaceholderText('搜索项目名称或描述'), 'demo{Enter}')
    expect(screen.getByRole('status', { name: '没有符合筛选条件的项目' })).toHaveAttribute(
      'data-state',
      'filtered'
    )
    await user.click(screen.getByRole('button', { name: '清空筛选' }))
    expect(useProjectList).toHaveBeenLastCalledWith({})
    expect(screen.getByText('暂无项目，点击新建项目开始创建迁移项目')).toBeInTheDocument()
  })

  it('keeps all supported filters reachable and applies keyword searches', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.type(screen.getByPlaceholderText('搜索项目名称或描述'), 'demo{Enter}')

    expect(screen.getByLabelText('项目状态')).toBeInTheDocument()
    expect(screen.getByLabelText('流程阶段')).toBeInTheDocument()
    expect(useProjectList).toHaveBeenLastCalledWith(expect.objectContaining({ keyword: 'demo' }))
  })

  it.each([
    ['完成并配置环境', '/projects/project-1/steps/env'],
    ['完成并进入工作台', '/projects/project-1/workflow']
  ])('navigates %s to its declared destination', async (buttonName, expectedLocation) => {
    const user = userEvent.setup()
    renderPage()

    await user.click(screen.getByRole('button', { name: '+ 新建' }))
    await user.click(await screen.findByRole('button', { name: buttonName }))

    expect(screen.getByLabelText('当前地址')).toHaveTextContent(expectedLocation)
  })
})
