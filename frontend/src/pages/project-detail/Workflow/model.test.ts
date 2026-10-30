import { describe, expect, it } from 'vitest'

import type { ProjectWorkflowStatus } from '@/types/project'

import { createWorkflowEdges, highlightConnectedWorkflowEdges } from './edges'
import { createWorkflowNodes } from './nodes'
import { getWorkflowStageData } from './stageData'
import { workflowStages } from './topology'

function status(overrides: Partial<ProjectWorkflowStatus> = {}): ProjectWorkflowStatus {
  return {
    projectId: 'project-1',
    currentStageId: 'env',
    stages: [{ id: 'env', status: 'RUNNING' }],
    activeEdgeIds: ['project-env'],
    updatedAt: '',
    ...overrides
  }
}

describe('workflow model structural sharing', () => {
  it.each([{ analysisStatus: 'PARTIAL' }, { analysisComplete: false }])(
    '保留 DONE 事实并在工作流和 Step 数据提示部分分析：%j',
    (partial) => {
      const current = status({
        stages: [{ id: 'memory-order-analysis', status: 'DONE', ...partial }]
      })
      expect(getWorkflowStageData('memory-order-analysis', current)).toMatchObject({
        status: 'done',
        analysisWarning: '部分分析完成'
      })
      expect(
        createWorkflowNodes(current).find((node) => node.id === 'memory-order-analysis')?.data
      ).toMatchObject({ status: 'done', hint: expect.stringContaining('部分分析完成') })
    }
  )
  it('preserves unchanged nodes when one stage changes', () => {
    const first = createWorkflowNodes(status())
    const next = createWorkflowNodes(status({ stages: [{ id: 'env', status: 'DONE' }] }), first)
    expect(next.find((node) => node.id === 'assets')).toBe(
      first.find((node) => node.id === 'assets')
    )
    expect(next.find((node) => node.id === 'env')).not.toBe(first.find((node) => node.id === 'env'))
  })

  it('marks the recommended stage as the current workflow focus', () => {
    const nodes = createWorkflowNodes(status({ currentStageId: 'report' }))

    expect(nodes.find((node) => node.id === 'report')?.data.focused).toBe(true)
    expect(nodes.find((node) => node.id === 'env')?.data.focused).toBe(false)
  })

  it('does not mark a completed recommendation as current', () => {
    const nodes = createWorkflowNodes(
      status({ currentStageId: 'archive', stages: [{ id: 'archive', status: 'DONE' }] })
    )

    expect(nodes.find((node) => node.id === 'archive')?.data.focused).toBe(false)
  })

  it('preserves unchanged edges and caps animations', () => {
    const first = createWorkflowEdges(status())
    const next = createWorkflowEdges(
      status({ activeEdgeIds: ['project-env', 'assets-x86-build'] }),
      first,
      1
    )
    expect(next.find((edge) => edge.id === 'project-env')).toBe(
      first.find((edge) => edge.id === 'project-env')
    )
    expect(next.find((edge) => edge.id === 'assets-x86-build')?.animated).toBe(false)
  })

  it('maps upstream execution and propagated blocks to edge semantics', () => {
    const edges = createWorkflowEdges(
      status({
        activeEdgeIds: [],
        stages: [
          { id: 'project', status: 'DONE' },
          { id: 'env', status: 'RUNNING' },
          { id: 'assets', status: 'BLOCKED' },
          { id: 'report', status: 'DONE' }
        ]
      })
    )

    expect(edges.find((edge) => edge.id === 'project-env')?.className).toContain(
      'workflow-edge-active'
    )
    expect(edges.find((edge) => edge.id === 'env-x86-build')?.className).toContain(
      'workflow-edge-blocked'
    )
    expect(edges.find((edge) => edge.id === 'x86-build-kb')?.className).toContain(
      'workflow-edge-blocked'
    )
    expect(edges.find((edge) => edge.id === 'kb-report')?.className).toContain(
      'workflow-edge-blocked'
    )
    expect(edges.find((edge) => edge.id === 'report-build')?.className).toContain(
      'workflow-edge-complete'
    )
    expect(edges.find((edge) => edge.id === 'optimize-archive')?.className).toContain(
      'workflow-edge-waiting'
    )
    expect(edges.find((edge) => edge.id === 'manual-assets')?.className).not.toContain(
      'workflow-edge-blocked'
    )
  })

  it('highlights and elevates every edge connected to every workflow stage', () => {
    const edges = createWorkflowEdges()

    for (const stage of workflowStages) {
      const highlightedEdges = highlightConnectedWorkflowEdges(edges, stage.id)

      for (const edge of highlightedEdges) {
        const isConnected = edge.source === stage.id || edge.target === stage.id

        expect(edge.className?.includes('workflow-edge-hovered')).toBe(isConnected)
        expect(edge.zIndex).toBe(isConnected ? 2 : 1)
      }
    }
  })

  it('preserves the edge collection when no node is hovered', () => {
    const edges = createWorkflowEdges()

    expect(highlightConnectedWorkflowEdges(edges)).toBe(edges)
  })

  it('keeps current-stage edges emphasized while hovering another node and after leaving', () => {
    const edges = createWorkflowEdges(status())
    for (const hoveredId of ['assets', undefined]) {
      const highlighted = highlightConnectedWorkflowEdges(edges, hoveredId, 'env')
      for (const edge of highlighted) {
        const connected =
          edge.source === 'env' ||
          edge.target === 'env' ||
          (hoveredId !== undefined && (edge.source === hoveredId || edge.target === hoveredId))
        expect(edge.className?.includes('workflow-edge-hovered')).toBe(connected)
        expect(edge.style).toBe(edges.find((original) => original.id === edge.id)?.style)
        expect(edge.markerEnd).toBe(edges.find((original) => original.id === edge.id)?.markerEnd)
      }
    }
  })

  it('does not resolve removed workflow stages', () => {
    expect(getWorkflowStageData('diff', status())).toBeNull()
    expect(createWorkflowNodes(status()).some((node) => node.id === 'diff')).toBe(false)
  })
})
