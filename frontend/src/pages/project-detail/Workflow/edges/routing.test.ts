import { Position } from '@xyflow/react'
import { describe, expect, it } from 'vitest'

import { WORKFLOW_STAGE_HEIGHT, WORKFLOW_STAGE_WIDTH, workflowStageById } from '../topology'
import { getFeedbackRoute, getWorkflowStepPath } from './routing'

// 回流线端点取节点底部中心；坐标从拓扑定义读取，节点位置调整时测试无需改动。
function bottomCenter(stageId: string) {
  const stage = workflowStageById.get(stageId)
  if (!stage) throw new Error(`unknown stage: ${stageId}`)
  return {
    x: stage.position.x + WORKFLOW_STAGE_WIDTH / 2,
    y: stage.position.y + WORKFLOW_STAGE_HEIGHT
  }
}

function feedbackRoute(edgeId: string, sourceId: string, targetId: string, deltaX = 0) {
  const source = bottomCenter(sourceId)
  const target = bottomCenter(targetId)
  return getFeedbackRoute(edgeId, source.x + deltaX, source.y, target.x + deltaX, target.y)
}

describe('workflow edge routing', () => {
  it.each([0.000001, 0.5, 1])(
    'keeps the manual label on its horizontal segment with a %spx measured handle offset',
    (delta) => {
      const sourceX = bottomCenter('manual').x + delta
      const targetX = bottomCenter('assets').x + delta
      const manual = feedbackRoute('manual-assets', 'manual', 'assets', delta)

      expect(manual.labelX).toBeCloseTo((sourceX + targetX) / 2)
      expect(sourceX - manual.labelX).toBeGreaterThan(70)
    }
  )

  it.each([36, 38, 40])('keeps a single vertical channel across a %ipx gap', (gap) => {
    const path = getWorkflowStepPath({
      sourceX: 0,
      sourceY: 0,
      sourcePosition: Position.Right,
      targetX: gap,
      targetY: 130,
      targetPosition: Position.Left
    })

    // 两个圆角分别属于出发和到达转弯，中间不再产生额外的折返圆角。
    expect(path.match(/Q/g)).toHaveLength(2)
    expect(path).toContain(`L${gap / 2} 65`)
  })

  it('separates feedback lanes below the groups and keeps labels clear of vertical edges', () => {
    const manual = feedbackRoute('manual-assets', 'manual', 'assets')
    const optimize = feedbackRoute('optimize-assets', 'optimize', 'assets')

    // 人工修复是评估分组里最靠下的节点，车道必须在它下方，不得穿过分组内容。
    expect(manual.laneY).toBeGreaterThan(bottomCenter('manual').y)
    expect(optimize.laneY - manual.laneY).toBeGreaterThanOrEqual(40)
    const verticals = [
      bottomCenter('assets').x,
      bottomCenter('manual').x,
      bottomCenter('optimize').x
    ]
    for (const route of [manual, optimize]) {
      for (const verticalX of verticals) {
        expect(Math.abs(route.labelX - verticalX)).toBeGreaterThan(70)
      }
    }
    expect(optimize.labelX).toBeGreaterThan(bottomCenter('manual').x)
  })

  it('gives AI migration a feedback lane separate from the manual repair lane', () => {
    // AI 迁移与人工修复同列，两条回流线共用起点的竖线位置，只能靠车道高度区分。
    const migration = feedbackRoute('ai-migration-assets', 'ai-migration', 'assets')
    const manual = feedbackRoute('manual-assets', 'manual', 'assets')

    expect(Math.abs(migration.laneY - manual.laneY)).toBeGreaterThanOrEqual(40)
    expect(migration.laneY).toBeGreaterThan(bottomCenter('ai-migration').y)
    expect(manual.laneY).toBeGreaterThan(bottomCenter('manual').y)
    for (const route of [migration, manual]) {
      expect(Math.abs(route.labelX - bottomCenter('assets').x)).toBeGreaterThan(70)
    }
  })
})
