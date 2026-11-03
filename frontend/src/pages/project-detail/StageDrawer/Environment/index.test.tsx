import { useEffect, useEffectEvent } from 'react'
import { QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ConfigProvider } from 'antd'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ProjectOverviewContext } from '@/pages/project-detail/ProjectOverviewContext'
import { createEnvironment, createTestQueryClient } from '@/test/test-utils'

import { StagePresentationContext } from '../components/StagePresentationContext'

const api = vi.hoisted(() => ({
  getProjectEnvironments: vi.fn(),
  getProjectEnvironmentScriptExecutions: vi.fn(),
  getProjectEnvironmentScriptExecution: vi.fn()
}))
vi.mock('@/api/project/environments', () => api)
vi.mock('../components/TaskLogPanel', () => ({ TaskLogPanel: () => <div>日志内容</div> }))
vi.mock('@/components/BaseDrawer', () => ({
  default: function MockDrawer({
    visible,
    children,
    title,
    footer,
    afterOpenChange
  }: {
    visible: boolean
    children: React.ReactNode
    title?: React.ReactNode
    footer?: React.ReactNode
    afterOpenChange?: (open: boolean) => void
  }) {
    const notify = useEffectEvent((open: boolean) => afterOpenChange?.(open))
    useEffect(() => {
      notify(visible)
    }, [visible])
    return visible ? (
      <div data-testid="stage-drawer">
        {title}
        {children}
        {footer}
      </div>
    ) : null
  }
}))
import { render } from '@testing-library/react'

import Env from '.'

const execution = {
  id: 'execution-1',
  environmentId: 'env-1',
  scriptName: '自定义脚本',
  scriptType: 'CUSTOM',
  status: 'SUCCEEDED',
  message: 'completed',
  submittedAt: '2026-07-13T00:00:00Z',
  startedAt: null,
  finishedAt: null,
  exitCode: 0,
  logEventCount: 1
}

function setup(presentation: 'drawer' | 'panel', onDirtyChange?: (dirty: boolean) => void) {
  const client = createTestQueryClient()
  const tree = (visible = true) => (
    <ConfigProvider theme={{ token: { motion: false } }}>
      <QueryClientProvider client={client}>
        <ProjectOverviewContext.Provider
          value={{
            projectId: 'project-1',
            isLoading: false,
            isError: false,
            isFetching: false,
            refetch: vi.fn()
          }}
        >
          <StagePresentationContext.Provider value={presentation}>
            <Env
              projectId="project-1"
              visible={visible}
              onClose={vi.fn()}
              onDirtyChange={onDirtyChange}
            />
          </StagePresentationContext.Provider>
        </ProjectOverviewContext.Provider>
      </QueryClientProvider>
    </ConfigProvider>
  )
  return { client, tree, ...render(tree()) }
}

describe('environment presentation and history scope', () => {
  beforeEach(() => {
    api.getProjectEnvironments.mockResolvedValue({
      items: [createEnvironment(), createEnvironment({ id: 'env-2', name: 'second' })],
      total: 2,
      page: 1,
      pageSize: 20
    })
    api.getProjectEnvironmentScriptExecutions.mockImplementation(async (_, params) => ({
      items: [{ ...execution, environmentId: params.environmentId }],
      total: 100,
      page: params.page,
      pageSize: params.pageSize
    }))
    api.getProjectEnvironmentScriptExecution.mockRejectedValue(new Error('unavailable'))
  })

  it('replaces stale environment rows after refreshing the list', async () => {
    const actor = userEvent.setup()
    setup('panel')
    expect(await screen.findByText('second')).toBeInTheDocument()
    api.getProjectEnvironments.mockResolvedValue({
      items: [createEnvironment({ id: 'env-3', name: 'replacement' })],
      total: 1,
      page: 1,
      pageSize: 20
    })
    await actor.click(screen.getByRole('button', { name: '刷新环境列表' }))
    expect(await screen.findByText('replacement')).toBeInTheDocument()
    expect(screen.queryByText('second')).not.toBeInTheDocument()
  })

  it.each(['drawer', 'panel'] as const)(
    'preserves a dirty environment draft when continuing and clears it only after discard in %s',
    async (presentation) => {
      const actor = userEvent.setup()
      const onDirtyChange = vi.fn()
      setup(presentation, onDirtyChange)
      await actor.click(await screen.findByRole('button', { name: /新建环境/ }))
      const name = await screen.findByLabelText('服务器名称')
      await actor.type(name, 'unsaved-environment')
      await waitFor(() => expect(onDirtyChange).toHaveBeenLastCalledWith(true))
      await actor.click(screen.getByRole('button', { name: /取\s*消/ }))
      await actor.click(await screen.findByRole('button', { name: '继续编辑' }))
      expect(name).toHaveValue('unsaved-environment')
      expect(onDirtyChange).toHaveBeenLastCalledWith(true)
      await actor.click(screen.getByRole('button', { name: /取\s*消/ }))
      await actor.click(await screen.findByRole('button', { name: '放弃更改' }))
      await waitFor(() => expect(screen.queryByLabelText('服务器名称')).not.toBeInTheDocument())
      expect(await screen.findByText('second')).toBeInTheDocument()
      await waitFor(() => expect(onDirtyChange).toHaveBeenLastCalledWith(false))
      await actor.click(screen.getByRole('button', { name: /新建环境/ }))
      expect(await screen.findByLabelText('服务器名称')).toHaveValue('')
    }
  )

  it.each(['drawer', 'panel'] as const)(
    'uses the stage drawer only in %s and a modal only in panel for forms and scripts',
    async (presentation) => {
      const user = userEvent.setup()
      setup(presentation)
      await user.click(await screen.findByRole('button', { name: /新建环境/ }))
      await screen.findByLabelText('服务器名称')
      expect(document.querySelectorAll('.ant-modal').length).toBe(presentation === 'panel' ? 1 : 0)
      expect(screen.queryAllByTestId('stage-drawer')).toHaveLength(
        presentation === 'drawer' ? 1 : 0
      )
      if (presentation === 'drawer')
        expect(screen.queryByRole('button', { name: /新建环境/ })).not.toBeInTheDocument()
      await user.click(await screen.findByRole('button', { name: /取\s*消/ }))
      await user.click((await screen.findAllByRole('button', { name: '运行脚本' }))[0])
      await screen.findByRole('textbox', { name: '脚本内容' })
      expect(document.querySelectorAll('.ant-modal').length).toBe(presentation === 'panel' ? 1 : 0)
      expect(screen.queryAllByTestId('stage-drawer')).toHaveLength(
        presentation === 'drawer' ? 1 : 0
      )
    }
  )
  it.each(['drawer', 'panel'] as const)(
    'keeps history pagination through log failure/retry/back in %s and resets on close',
    async (presentation) => {
      const user = userEvent.setup()
      const { rerender, tree } = setup(presentation)
      await user.click((await screen.findAllByRole('button', { name: '执行记录' }))[0])
      const history = await screen.findByRole('heading', { name: 'x86-builder 的执行记录' })
      const list = history.closest('section')!
      await user.click(within(list).getByRole('combobox'))
      await user.click(await screen.findByText('50 / page'))
      await user.click(await within(list).findByTitle('2'))
      await waitFor(() =>
        expect(api.getProjectEnvironmentScriptExecutions).toHaveBeenLastCalledWith(
          'project-1',
          expect.objectContaining({ page: 2, pageSize: 50, environmentId: 'env-1' })
        )
      )
      await user.click(within(list).getByRole('button', { name: '查看日志' }))
      expect(await screen.findByText('执行详情加载失败')).toBeInTheDocument()
      expect(screen.queryByText('正在加载执行记录')).not.toBeInTheDocument()
      api.getProjectEnvironmentScriptExecution.mockResolvedValue(execution)
      await user.click(screen.getByRole('button', { name: /重\s*试/ }))
      expect(await screen.findByText('日志内容')).toBeInTheDocument()
      await user.click(screen.getByRole('button', { name: '返回' }))
      await screen.findByRole('heading', { name: 'x86-builder 的执行记录' })
      await waitFor(() =>
        expect(api.getProjectEnvironmentScriptExecutions).toHaveBeenLastCalledWith(
          'project-1',
          expect.objectContaining({ page: 2, pageSize: 50 })
        )
      )
      await user.click(
        screen.getByRole('button', { name: presentation === 'drawer' ? '返回' : 'Close' })
      )
      await waitFor(() => {
        const heading = screen.queryByRole('heading', { name: /的执行记录/ })
        if (heading) expect(heading).not.toBeVisible()
      })
      await user.click(screen.getAllByRole('button', { name: '执行记录' })[1])
      await screen.findByRole('heading', { name: 'second 的执行记录' })
      await waitFor(() =>
        expect(api.getProjectEnvironmentScriptExecutions).toHaveBeenLastCalledWith(
          'project-1',
          expect.objectContaining({ page: 1, environmentId: 'env-2' })
        )
      )
      await user.click(
        screen.getByRole('button', { name: presentation === 'drawer' ? '返回' : 'Close' })
      )
      await waitFor(() => {
        const heading = screen.queryByRole('heading', { name: /的执行记录/ })
        if (heading) expect(heading).not.toBeVisible()
      })
      rerender(tree(false))
      rerender(tree())
      await user.click((await screen.findAllByRole('button', { name: '执行记录' }))[0])
      await screen.findByRole('heading', { name: 'x86-builder 的执行记录' })
      await waitFor(() =>
        expect(api.getProjectEnvironmentScriptExecutions).toHaveBeenLastCalledWith(
          'project-1',
          expect.objectContaining({ page: 1, environmentId: 'env-1' })
        )
      )
      // page correction follows the new total, including a remount after log navigation.
      const section = screen
        .getByRole('heading', { name: 'x86-builder 的执行记录' })
        .closest('section')!
      fireEvent.click(await within(section).findByTitle('2'))
      await waitFor(() =>
        expect(api.getProjectEnvironmentScriptExecutions).toHaveBeenLastCalledWith(
          'project-1',
          expect.objectContaining({ page: 2 })
        )
      )
      api.getProjectEnvironmentScriptExecutions.mockImplementation(async (_, params) => ({
        items: [execution],
        total: 1,
        page: params.page,
        pageSize: params.pageSize
      }))
      await setupRefresh(section)
    },
    60_000
  )
})
async function setupRefresh(section: HTMLElement) {
  // Native drawer refresh is owned by Env and invalidates the exact history key.
  fireEvent.click(screen.getByRole('button', { name: '刷新执行记录' }))
  await waitFor(() =>
    expect(within(section).getByTitle('1')).toHaveClass('ant-pagination-item-active')
  )
}
