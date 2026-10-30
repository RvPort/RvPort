import { render, screen } from '@testing-library/react'
import { type EdgeProps, Position } from '@xyflow/react'
import { describe, expect, it, vi } from 'vitest'

import { FeedbackEdge } from './FeedbackEdge'
import { getFeedbackRoute, getWorkflowStepPath } from './routing'
import { WorkflowSmoothStepEdge } from './WorkflowSmoothStepEdge'

vi.mock('@xyflow/react', async (load) => ({
  ...(await load<typeof import('@xyflow/react')>()),
  BaseEdge: ({
    id,
    path,
    markerStart,
    markerEnd,
    style,
    interactionWidth
  }: {
    id: string
    path: string
    markerStart?: string
    markerEnd?: string
    style?: React.CSSProperties
    interactionWidth?: number
  }) => (
    <path
      id={id}
      d={path}
      markerStart={markerStart}
      markerEnd={markerEnd}
      style={style}
      data-interaction-width={interactionWidth}
    />
  )
}))

const initial: EdgeProps = {
  id: 'feedback-test',
  source: 'test',
  target: 'source',
  sourceX: 400,
  sourceY: 200,
  targetX: 40,
  targetY: 100,
  sourcePosition: Position.Bottom,
  targetPosition: Position.Bottom,
  selected: false,
  animated: true,
  deletable: false,
  selectable: false
}

describe('workflow edge rendering', () => {
  it('reroutes feedback as endpoints move and independently updates label, marker and animation', () => {
    let edge: EdgeProps = { ...initial, label: '重新处理', markerEnd: 'url(#old)' }
    const view = render(
      <svg>
        <FeedbackEdge {...edge} />
      </svg>
    )
    const path = () => view.container.querySelector('path.react-flow__edge-path')!
    expect(path().querySelector('animate')).toBeInTheDocument()
    edge = { ...edge, sourceX: 500, sourceY: 230, targetX: 80, targetY: 150 }
    view.rerender(
      <svg>
        <FeedbackEdge {...edge} />
      </svg>
    )
    const expected = getFeedbackRoute(edge.id, 500, 230, 80, 150)
    expect(path()).toHaveAttribute('d', expected.path)
    expect(view.container.querySelector('.workflow-edge-hover-underlay')).toHaveAttribute(
      'd',
      expected.path
    )
    expect(screen.getByText('重新处理')).toHaveAttribute('x', String(expected.labelX))
    expect(screen.getByText('重新处理')).toHaveAttribute('y', String(expected.labelY))
    const route = path().getAttribute('d')
    edge = { ...edge, markerEnd: 'url(#new)' }
    view.rerender(
      <svg>
        <FeedbackEdge {...edge} />
      </svg>
    )
    expect(path()).toHaveAttribute('marker-end', 'url(#new)')
    expect(path()).toHaveAttribute('d', route)
    edge = { ...edge, label: '复检闭环' }
    view.rerender(
      <svg>
        <FeedbackEdge {...edge} />
      </svg>
    )
    expect(screen.queryByText('重新处理')).not.toBeInTheDocument()
    expect(screen.getByText('复检闭环')).toBeInTheDocument()
    edge = { ...edge, animated: false }
    view.rerender(
      <svg>
        <FeedbackEdge {...edge} />
      </svg>
    )
    expect(path().querySelector('animate')).not.toBeInTheDocument()
    edge = { ...edge, id: 'feedback-new', label: undefined }
    view.rerender(
      <svg>
        <FeedbackEdge {...edge} />
      </svg>
    )
    expect(path()).toHaveAttribute('id', 'feedback-new')
    expect(screen.queryByText('复检闭环')).not.toBeInTheDocument()
  })

  it('updates step geometry and forwards independent directional markers and interaction width', () => {
    let edge: EdgeProps = {
      ...initial,
      id: 'step-edge',
      sourceX: 100,
      targetX: 140,
      sourcePosition: Position.Right,
      targetPosition: Position.Left,
      interactionWidth: 20,
      style: { stroke: 'blue' }
    }
    const view = render(
      <svg>
        <WorkflowSmoothStepEdge {...edge} />
      </svg>
    )
    const path = () => view.container.querySelector('path[id]')!
    edge = {
      ...edge,
      sourceX: 110,
      sourceY: 220,
      targetX: 180,
      targetY: 120,
      sourcePosition: Position.Bottom,
      targetPosition: Position.Top
    }
    view.rerender(
      <svg>
        <WorkflowSmoothStepEdge {...edge} />
      </svg>
    )
    const expected = getWorkflowStepPath(edge)
    expect(path()).toHaveAttribute('d', expected)
    expect(view.container.querySelector('.workflow-edge-hover-underlay')).toHaveAttribute(
      'd',
      expected
    )
    const route = path().getAttribute('d')
    edge = {
      ...edge,
      markerStart: 'url(#start)',
      markerEnd: 'url(#end)',
      interactionWidth: 30,
      style: { stroke: 'red' },
      id: 'step-new'
    }
    view.rerender(
      <svg>
        <WorkflowSmoothStepEdge {...edge} />
      </svg>
    )
    expect(path()).toHaveAttribute('d', route)
    expect(path()).toHaveAttribute('id', 'step-new')
    expect(path()).toHaveAttribute('marker-start', 'url(#start)')
    expect(path()).toHaveAttribute('marker-end', 'url(#end)')
    expect(path()).toHaveAttribute('data-interaction-width', '30')
    expect(path()).toHaveStyle({ stroke: 'rgb(255, 0, 0)' })
  })
})
