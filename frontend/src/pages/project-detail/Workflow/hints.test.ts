import { describe, expect, it } from 'vitest'

import type { ProjectWorkflowStatus } from '@/types/project'

import {
  deriveWorkflowPresentation,
  getWorkflowAssistText,
  getWorkflowNodeHint
} from './presentation'
import { workflowStages } from './topology'

function workflow(
  overrides: Record<string, ProjectWorkflowStatus['stages'][number]['status']>
): ProjectWorkflowStatus {
  return {
    projectId: 'project-1',
    currentStageId: 'project',
    activeEdgeIds: [],
    updatedAt: '2026-10-28T00:00:00Z',
    stages: workflowStages.map((stage) => ({
      id: stage.id,
      status: overrides[stage.id] ?? 'PENDING'
    }))
  }
}

describe('workflow node explanation contracts', () => {
  it('keeps completed and running facts ahead of stale failure reasons', () => {
    for (const [status, expected] of [
      ['DONE', '已完成'],
      ['RUNNING', '正在执行']
    ] as const) {
      const model = deriveWorkflowPresentation(workflow({ 'x86-build': status, env: 'BLOCKED' }))
      expect(getWorkflowNodeHint('x86-build', model.get('x86-build')!, 'X86_BUILD_FAILED')).toBe(
        expected
      )
    }
  })
  it('explains readiness, upstream blocks and unknown server failure codes using the derived graph state', () => {
    const ready = deriveWorkflowPresentation(
      workflow({ project: 'DONE', assets: 'DONE', env: 'DONE' })
    )
    expect(getWorkflowNodeHint('x86-build', ready.get('x86-build')!)).toBe('可以开始')
    const blocked = deriveWorkflowPresentation(workflow({ 'x86-build': 'BLOCKED' }))
    expect(getWorkflowNodeHint('x86-build', blocked.get('x86-build')!, 'X86_BUILD_FAILED')).toBe(
      'x86 构建失败'
    )
    expect(getWorkflowNodeHint('x86-build', blocked.get('x86-build')!, 'NEW_BACKEND_CODE')).toBe(
      '阶段执行失败'
    )
    expect(getWorkflowNodeHint('report', blocked.get('report')!)).toMatch(/^受 .* 阻塞$/)
  })
  it('keeps unknown dependencies out of user-facing titles while retaining a useful fallback', () => {
    expect(getWorkflowAssistText('READY', [])).toBe('前置阶段已完成，可以开始')
    expect(getWorkflowAssistText('WAITING', ['unknown-stage'])).toBe('等待前置阶段完成')
    expect(getWorkflowAssistText('BLOCKED', ['unknown-stage'])).toBe('前置阶段已阻塞')
    expect(getWorkflowAssistText('WAITING', ['env'])).toBe('等待：环境管理')
    expect(getWorkflowAssistText('BLOCKED', ['env'])).toBe('阻塞：环境管理')
  })
})
