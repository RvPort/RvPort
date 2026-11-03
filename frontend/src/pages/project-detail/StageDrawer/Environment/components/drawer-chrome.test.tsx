import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import type { ProjectEnvironment } from '@/types/project'

import type { EnvironmentView } from '../model'
import { EnvironmentDrawerFooter, EnvironmentDrawerTitle } from './drawer-chrome'

const environment: ProjectEnvironment = {
  id: 'env',
  name: '目标环境',
  role: 'TARGET',
  architecture: 'riscv64',
  os: null,
  host: 'test.invalid',
  jumpHostEnvironmentId: null,
  sshPort: 22,
  workdir: '/workspace',
  authType: 'KEY',
  basicInfo: null,
  credentialsConfigured: true,
  connectionStatus: 'SUCCESS'
}

describe('environment footer transitions', () => {
  it.each(['create', 'edit'] as const)(
    'requires confirmation and prevents duplicate %s saves during connection checks',
    async (type) => {
      const onSave = vi.fn(async () => {}),
        onShowList = vi.fn(),
        onRunScript = vi.fn(async () => {})
      const view: EnvironmentView = type === 'create' ? { type } : { type, environment }
      let props = {
        view,
        isSaving: false,
        isTestingConnection: true,
        isRunningScript: false,
        onSave,
        onShowList,
        onRunScript
      }
      const { rerender } = render(<EnvironmentDrawerFooter {...props} />)
      expect(screen.getByRole('button', { name: /保\s*存/ })).toBeDisabled()
      expect(screen.getByRole('button', { name: /取\s*消/ })).toBeDisabled()
      props = { ...props, isTestingConnection: false }
      rerender(<EnvironmentDrawerFooter {...props} />)
      await userEvent.click(screen.getByRole('button', { name: /保\s*存/ }))
      await waitFor(() =>
        expect(
          screen.getByText(type === 'create' ? '确认保存环境？' : '确认保存修改？')
        ).toBeVisible()
      )
      expect(onSave).not.toHaveBeenCalled()
      await userEvent.click(screen.getByRole('button', { name: '确认保存' }))
      expect(onSave).toHaveBeenCalledOnce()
      await waitFor(() =>
        expect(screen.queryByRole('button', { name: '确认保存' })).not.toBeInTheDocument()
      )
      props = { ...props, isSaving: true }
      rerender(<EnvironmentDrawerFooter {...props} />)
      expect(screen.getByRole('button', { name: /保\s*存/ })).toBeDisabled()
      const nextSave = vi.fn(async () => {})
      rerender(<EnvironmentDrawerFooter {...props} isSaving={false} onSave={nextSave} />)
      await userEvent.click(screen.getByRole('button', { name: /保\s*存/ }))
      await userEvent.click(await screen.findByRole('button', { name: '确认保存' }))
      expect(nextSave).toHaveBeenCalledOnce()
      await waitFor(() =>
        expect(screen.queryByRole('button', { name: '确认保存' })).not.toBeInTheDocument()
      )
      await userEvent.click(screen.getByRole('button', { name: /取\s*消/ }))
      expect(onShowList).toHaveBeenCalledOnce()
    }
  )

  it('confirms script execution, blocks duplicate requests and removes actions outside editable views', async () => {
    const props = {
      view: { type: 'script' as const, environment },
      isSaving: false,
      isTestingConnection: false,
      isRunningScript: false,
      onSave: vi.fn(async () => {}),
      onShowList: vi.fn(),
      onRunScript: vi.fn(async () => {})
    }
    const { rerender } = render(<EnvironmentDrawerFooter {...props} />)
    await userEvent.click(screen.getByRole('button', { name: '运行脚本' }))
    expect(props.onRunScript).not.toHaveBeenCalled()
    await userEvent.click(screen.getByRole('button', { name: '确认执行' }))
    expect(props.onRunScript).toHaveBeenCalledOnce()
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: '确认执行' })).not.toBeInTheDocument()
    )
    rerender(<EnvironmentDrawerFooter {...props} isRunningScript />)
    expect(screen.getByRole('button', { name: /运行脚本/ })).toBeDisabled()
    expect(screen.getByRole('button', { name: /取\s*消/ })).toBeDisabled()
    const next = vi.fn()
    rerender(<EnvironmentDrawerFooter {...props} onShowList={next} />)
    await userEvent.click(screen.getByRole('button', { name: /取\s*消/ }))
    expect(next).toHaveBeenCalledOnce()
    for (const view of [
      { type: 'list' },
      { type: 'history', environment },
      { type: 'log', environment, executionId: 'execution' }
    ] as EnvironmentView[]) {
      rerender(<EnvironmentDrawerFooter {...props} view={view} />)
      expect(screen.queryByRole('button')).not.toBeInTheDocument()
    }
  })
})

describe('EnvironmentDrawerTitle', () => {
  it('updates title, subtitle and refresh ownership as the view changes', async () => {
    const refresh = vi.fn(async () => {}),
      back = vi.fn()
    const props = { onBack: back, onRefresh: refresh }
    const { rerender } = render(<EnvironmentDrawerTitle {...props} view={{ type: 'list' }} />)
    rerender(
      <EnvironmentDrawerTitle
        {...props}
        stage={{
          id: 'env',
          order: 2,
          title: '运行环境',
          subtitle: '独立副标题',
          status: 'pending'
        }}
        view={{ type: 'list' }}
      />
    )
    expect(screen.getByText('运行环境')).toBeVisible()
    expect(screen.getByText('独立副标题')).toBeVisible()
    const cases: Array<[EnvironmentView, string, string | null]> = [
      [{ type: 'script', environment }, '运行脚本', null],
      [{ type: 'history', environment }, '执行记录', '刷新执行记录'],
      [{ type: 'log', environment, executionId: 'execution' }, '执行日志', '刷新执行日志']
    ]
    for (const [view, title, label] of cases) {
      rerender(<EnvironmentDrawerTitle {...props} view={view} />)
      expect(screen.getByText(title)).toBeVisible()
      expect(screen.getByText('环境脚本执行与审计记录')).toBeVisible()
      if (label) await userEvent.click(screen.getByRole('button', { name: label }))
      else expect(screen.queryByRole('button', { name: /刷新/ })).not.toBeInTheDocument()
    }
    expect(refresh).toHaveBeenCalledTimes(2)
    const nextRefresh = vi.fn(async () => {})
    rerender(<EnvironmentDrawerTitle {...props} view={{ type: 'list' }} onRefresh={nextRefresh} />)
    await userEvent.click(screen.getByRole('button', { name: '刷新环境列表' }))
    expect(nextRefresh).toHaveBeenCalledOnce()
  })
  it('exposes a scoped refresh action for the environment list', async () => {
    const onRefresh = vi.fn(async () => {})
    const user = userEvent.setup()

    render(
      <EnvironmentDrawerTitle
        stage={null}
        view={{ type: 'list' }}
        onBack={vi.fn()}
        onRefresh={onRefresh}
      />
    )

    const refreshButton = screen.getByRole('button', { name: '刷新环境列表' })
    expect(refreshButton).not.toHaveTextContent('刷新')

    await user.click(refreshButton)

    expect(onRefresh).toHaveBeenCalledTimes(1)
  })

  it('does not offer refresh while editing unsaved configuration', () => {
    render(
      <EnvironmentDrawerTitle
        stage={null}
        view={{ environment: {} as never, type: 'edit' }}
        onBack={vi.fn()}
        onRefresh={vi.fn()}
      />
    )

    expect(screen.queryByRole('button', { name: /刷新/ })).not.toBeInTheDocument()
  })

  it('blocks Workflow internal back during submission and restores it afterward', async () => {
    const onBack = vi.fn()
    const user = userEvent.setup()
    const props = {
      showBack: true,
      view: { type: 'create' as const },
      onBack,
      onRefresh: vi.fn(async () => {})
    }
    const { rerender } = render(<EnvironmentDrawerTitle {...props} busy />)
    expect(screen.getByRole('button', { name: '返回' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: '返回' }))
    expect(onBack).not.toHaveBeenCalled()
    rerender(<EnvironmentDrawerTitle {...props} />)
    await user.click(screen.getByRole('button', { name: '返回' }))
    expect(onBack).toHaveBeenCalledOnce()
  })

  it('uses the drawer close action for history and an internal back action only for logs', async () => {
    const onBack = vi.fn()
    const user = userEvent.setup()
    const { rerender } = render(
      <EnvironmentDrawerTitle
        stage={null}
        view={{ environment: {} as never, type: 'history' }}
        onBack={onBack}
        onRefresh={vi.fn(async () => {})}
      />
    )

    expect(screen.queryByRole('button', { name: '返回' })).not.toBeInTheDocument()

    rerender(
      <EnvironmentDrawerTitle
        stage={null}
        view={{ environment: {} as never, executionId: 'execution-1', type: 'log' }}
        onBack={onBack}
        onRefresh={vi.fn(async () => {})}
      />
    )

    await user.click(screen.getByRole('button', { name: '返回' }))
    expect(onBack).toHaveBeenCalledOnce()
  })
})
