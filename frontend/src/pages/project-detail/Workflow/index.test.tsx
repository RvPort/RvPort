import { useState } from 'react'
import { act, fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import type { ProjectWorkflowStatus } from '@/types/project'

import Workflow from './index'

describe('Workflow desktop canvas', () => {
  const workflowStatus: ProjectWorkflowStatus = {
    projectId: 'project-1',
    currentStageId: 'env',
    stages: [
      { id: 'env', status: 'PENDING' },
      { id: 'assets', status: 'DONE' }
    ],
    activeEdgeIds: [],
    updatedAt: ''
  }

  it('keeps the current stage and externally controlled selection independent', () => {
    const { rerender } = render(
      <Workflow selectedStageId="assets" workflowStatus={workflowStatus} onStageClick={vi.fn()} />
    )
    const current = screen.getByText('环境管理').closest('[data-status]')
    const selected = screen.getByText('源码管理').closest('[data-status]')

    expect(current).toHaveAttribute('data-current-stage', 'true')
    expect(current).toHaveAttribute('aria-current', 'step')
    expect(current).toHaveAttribute('title', '当前阶段：环境管理')
    expect(current).not.toHaveAttribute('data-selected')
    expect(screen.getByText('当前阶段').closest('[data-status]')).toBe(current)
    expect(selected).toHaveAttribute('data-selected', 'true')
    expect(selected).not.toHaveAttribute('data-current-stage')

    rerender(
      <Workflow selectedStageId="env" workflowStatus={workflowStatus} onStageClick={vi.fn()} />
    )
    expect(current).toHaveAttribute('data-current-stage', 'true')
    expect(current).toHaveAttribute('data-selected', 'true')
    expect(selected).not.toHaveAttribute('data-selected')

    rerender(<Workflow workflowStatus={workflowStatus} onStageClick={vi.fn()} />)
    expect(current).toHaveAttribute('data-current-stage', 'true')
    expect(current).not.toHaveAttribute('data-selected')
  })

  it('opens the clicked stage with its identity while retaining the current workflow stage', async () => {
    const onStageClick = vi.fn()
    function Selection() {
      const [selectedStageId, setSelected] = useState<string>()
      return (
        <>
          <Workflow
            workflowStatus={workflowStatus}
            selectedStageId={selectedStageId}
            onStageClick={(id) => {
              onStageClick(id)
              setSelected(id)
            }}
          />
          <button onClick={() => setSelected(undefined)}>关闭所选阶段</button>
        </>
      )
    }
    render(<Selection />)
    const current = screen.getByText('环境管理').closest('[data-status]')!
    const source = screen.getByText('源码管理').closest('[data-status]')!
    fireEvent.click(screen.getByText('源码管理'))
    expect(onStageClick).toHaveBeenCalledExactlyOnceWith('assets')
    expect(source).toHaveAttribute('data-selected', 'true')
    expect(current).toHaveAttribute('data-current-stage', 'true')
    expect(current).not.toHaveAttribute('data-selected')
    fireEvent.click(screen.getByText('环境管理'))
    expect(onStageClick).toHaveBeenLastCalledWith('env')
    expect(onStageClick).toHaveBeenCalledTimes(2)
    expect(current).toHaveAttribute('data-selected', 'true')
    expect(source).not.toHaveAttribute('data-selected')
    fireEvent.click(screen.getByRole('button', { name: '关闭所选阶段' }))
    expect(current).not.toHaveAttribute('data-selected')
    expect(current).toHaveAttribute('aria-current', 'step')
    expect(onStageClick).toHaveBeenCalledTimes(2)
  })

  it('does not open stages when a group, gateway or the canvas is clicked, and uses the latest callback', async () => {
    const first = vi.fn(),
      latest = vi.fn()
    const view = render(<Workflow workflowStatus={workflowStatus} onStageClick={first} />)
    const groups = view.container.querySelectorAll('.react-flow__node-workflow-group')
    const gateways = view.container.querySelectorAll('.react-flow__node-workflow-gateway')
    expect(groups.length).toBeGreaterThan(0)
    expect(gateways.length).toBeGreaterThan(0)
    for (const node of [...groups, ...gateways]) {
      fireEvent.click(node)
    }
    const pane = view.container.querySelector('.react-flow__pane')!
    fireEvent.click(pane)
    expect(first).not.toHaveBeenCalled()
    view.rerender(<Workflow workflowStatus={workflowStatus} onStageClick={latest} />)
    fireEvent.click(screen.getByText('源码管理'))
    expect(latest).toHaveBeenCalledExactlyOnceWith('assets')
    expect(first).not.toHaveBeenCalled()
  })

  it('opens the focused stage once with Enter, preserves the current stage and uses the latest callback', async () => {
    const first = vi.fn(),
      latest = vi.fn()
    function KeyboardSelection({ notify }: { notify: (id: string) => void }) {
      const [selected, setSelected] = useState<string>()
      return (
        <Workflow
          workflowStatus={workflowStatus}
          selectedStageId={selected}
          onStageClick={(id) => {
            notify(id)
            setSelected(id)
          }}
        />
      )
    }
    const view = render(<KeyboardSelection notify={first} />)
    const stage = screen.getByText('源码管理').closest('.react-flow__node') as HTMLElement
    expect(stage).toHaveAttribute('tabindex', '0')
    act(() => stage.focus())
    expect(stage).toHaveFocus()
    const actor = userEvent.setup()
    await actor.keyboard('{Enter}')
    expect(first).toHaveBeenCalledExactlyOnceWith('assets')
    expect(screen.getByText('源码管理').closest('[data-status]')).toHaveAttribute(
      'data-selected',
      'true'
    )
    expect(screen.getByText('环境管理').closest('[data-status]')).toHaveAttribute(
      'data-current-stage',
      'true'
    )
    view.rerender(<KeyboardSelection notify={latest} />)
    await actor.keyboard('{Enter}')
    expect(latest).toHaveBeenCalledExactlyOnceWith('assets')
    expect(first).toHaveBeenCalledOnce()
    fireEvent.keyDown(stage, { key: 'Enter', repeat: true })
    fireEvent.keyDown(stage, { key: 'Enter', isComposing: true })
    await actor.keyboard('{Escape}')
    for (const node of view.container.querySelectorAll(
      '.react-flow__node-workflow-group, .react-flow__node-workflow-gateway, .react-flow__pane'
    )) {
      fireEvent.keyDown(node, { key: 'Enter' })
    }
    expect(latest).toHaveBeenCalledOnce()
  })

  it.each(['PENDING', 'RUNNING', 'BLOCKED'] as const)(
    'preserves %s business status on the current and selected stage',
    (status) => {
      render(
        <Workflow
          selectedStageId="env"
          workflowStatus={{ ...workflowStatus, stages: [{ id: 'env', status }] }}
          onStageClick={vi.fn()}
        />
      )
      const node = screen.getByText('环境管理').closest('[data-status]')

      expect(node).toHaveAttribute('data-current-stage', 'true')
      expect(node).toHaveAttribute('data-selected', 'true')
      expect(node).toHaveAttribute('data-status', status.toLowerCase())
      expect(screen.getByText('当前阶段').closest('[data-status]')).toBe(node)
    }
  )

  it('does not switch to a mobile-only navigation when the viewport is narrow', () => {
    vi.spyOn(window, 'matchMedia').mockImplementation(
      (query) =>
        ({
          addEventListener: vi.fn(),
          addListener: vi.fn(),
          dispatchEvent: vi.fn(),
          matches: query.includes('max-width'),
          media: query,
          onchange: null,
          removeEventListener: vi.fn(),
          removeListener: vi.fn()
        }) as unknown as MediaQueryList
    )

    render(<Workflow onStageClick={vi.fn()} />)

    expect(screen.queryByRole('region', { name: '项目阶段导航' })).not.toBeInTheDocument()
    expect(screen.getByText('项目基础配置')).toBeInTheDocument()
  })
})
