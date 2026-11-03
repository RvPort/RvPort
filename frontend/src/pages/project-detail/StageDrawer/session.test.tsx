import { useState } from 'react'
import { act, fireEvent, render, screen } from '@testing-library/react'
import { expect, it, vi } from 'vitest'

import { advanceAuthSession } from '@/store/authSession'

import StageDrawer from '.'
import type { StageDrawerProps, StageDrawerStage } from './types'

vi.mock('./Default', () => ({
  default: function MockStage({
    projectId,
    stage,
    visible,
    presentation,
    onClose,
    onDirtyChange,
    onStageChange
  }: StageDrawerProps) {
    const [draft, setDraft] = useState('')
    return (
      <div>
        <output>{`${projectId}:${stage?.title}:${visible}:${presentation}`}</output>
        <input
          aria-label="draft"
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
        />
        <button onClick={onClose}>close</button>
        <button onClick={() => onDirtyChange?.(true)}>dirty</button>
        <button onClick={() => onStageChange?.('env')}>navigate</button>
      </div>
    )
  }
}))

it('retains the closing stage but resets local state on project, stage and auth session changes', () => {
  let stage: StageDrawerStage | null = {
    id: 'extension-a',
    title: '扩展阶段',
    subtitle: '',
    order: 99,
    status: 'pending'
  }
  let projectId = 'project-a'
  let visible = true
  let presentation: 'drawer' | 'panel' = 'drawer'
  let onClose = vi.fn()
  let onDirtyChange = vi.fn()
  let onStageChange = vi.fn()
  const draw = () => (
    <StageDrawer
      projectId={projectId}
      stage={stage}
      visible={visible}
      presentation={presentation}
      onClose={onClose}
      onDirtyChange={onDirtyChange}
      onStageChange={onStageChange}
    />
  )
  const view = render(draw())
  const input = () => screen.getByRole('textbox', { name: 'draft' })
  fireEvent.change(input(), { target: { value: 'draft' } })
  visible = false
  stage = null
  view.rerender(draw())
  expect(screen.getByText('project-a:扩展阶段:false:drawer')).toBeInTheDocument()
  expect(input()).toHaveValue('draft')
  visible = true
  stage = { id: 'extension-a', title: '更新标题', subtitle: '', order: 99, status: 'pending' }
  view.rerender(draw())
  expect(input()).toHaveValue('draft')
  presentation = 'panel'
  view.rerender(draw())
  expect(screen.getByText('project-a:更新标题:true:panel')).toBeInTheDocument()
  const oldClose = onClose,
    oldDirty = onDirtyChange,
    oldStage = onStageChange
  onClose = vi.fn()
  view.rerender(draw())
  fireEvent.click(screen.getByText('close'))
  expect(onClose).toHaveBeenCalledOnce()
  expect(oldClose).not.toHaveBeenCalled()
  onDirtyChange = vi.fn()
  view.rerender(draw())
  fireEvent.click(screen.getByText('dirty'))
  expect(onDirtyChange).toHaveBeenCalledWith(true)
  expect(oldDirty).not.toHaveBeenCalled()
  onStageChange = vi.fn()
  view.rerender(draw())
  fireEvent.click(screen.getByText('navigate'))
  expect(onStageChange).toHaveBeenCalledWith('env')
  expect(oldStage).not.toHaveBeenCalled()
  projectId = 'project-b'
  view.rerender(draw())
  expect(input()).toHaveValue('')
  fireEvent.change(input(), { target: { value: 'second' } })
  stage = { ...stage, id: 'extension-b' }
  view.rerender(draw())
  expect(input()).toHaveValue('')
  fireEvent.change(input(), { target: { value: 'secret' } })
  act(() => advanceAuthSession())
  expect(input()).toHaveValue('')
})

it('renders an empty initial selection safely', () => {
  render(<StageDrawer visible={false} onClose={vi.fn()} />)
  expect(screen.getByRole('textbox', { name: 'draft' })).toHaveValue('')
})
