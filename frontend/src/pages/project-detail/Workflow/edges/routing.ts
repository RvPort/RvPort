import { getSmoothStepPath, Position } from '@xyflow/react'

import {
  WORKFLOW_GROUP_PADDING,
  WORKFLOW_STAGE_HEIGHT,
  WORKFLOW_STAGE_WIDTH,
  workflowEdges,
  workflowGroups,
  workflowStageById,
  workflowStages
} from '../topology'

export function getWorkflowStepPath(options: Parameters<typeof getSmoothStepPath>[0]) {
  const gap = options.targetX - options.sourceX
  // 窄间距下两端默认的 20px 偏移会交错，导致竖线中部出现多余折返。
  const offset =
    options.sourcePosition === Position.Right && options.targetPosition === Position.Left && gap > 0
      ? Math.min(20, gap / 2)
      : 20
  return getSmoothStepPath({ ...options, offset })[0]
}

const feedbackEdges = workflowEdges.filter((edge) => edge.feedback)
const groupBottom = Math.max(
  ...workflowGroups.flatMap((group) =>
    workflowStages
      .filter((stage) => group.stageIds.includes(stage.id))
      .map(
        (stage) =>
          stage.position.y +
          WORKFLOW_STAGE_HEIGHT +
          (group.padding?.bottom ?? WORKFLOW_GROUP_PADDING.bottom)
      )
  )
)
const feedbackSources = feedbackEdges.map((edge) => ({
  id: edge.id,
  x: workflowStageById.get(edge.source)!.position.x + WORKFLOW_STAGE_WIDTH / 2
}))

export function getFeedbackRoute(
  id: string,
  sourceX: number,
  sourceY: number,
  targetX: number,
  targetY: number
) {
  const laneIndex = Math.max(
    0,
    feedbackEdges.findIndex((edge) => edge.id === id)
  )
  // 所有回流线以分组底部为统一基线，避免各自端点高度导致通道交错。
  const laneY = Math.max(groupBottom, sourceY, targetY) + 42 + laneIndex * 40
  const left = Math.min(sourceX, targetX)
  const right = Math.max(sourceX, targetX)
  // 实测端点可能有小数偏差；按边 ID 排除自身，避免把自己的竖线误判为交叉线。
  // 同列的其它回流线（如并列的两种修复方式）与本线并行而非横穿，起点在亚像素级别
  // 重合时同样不算交叉，否则偏差会让标签跳到两条线重合的竖线上。
  const sameColumnTolerance = 1
  const crossings = feedbackSources
    .filter(
      (edge) =>
        edge.id !== id &&
        Math.abs(edge.x - sourceX) > sameColumnTolerance &&
        edge.x > left &&
        edge.x < right
    )
    .map((edge) => edge.x)
  // 标签放在靠近起点的无交叉区间，而非整条横线的中点。
  const labelX =
    sourceX > targetX
      ? (Math.max(left, ...crossings) + sourceX) / 2
      : (Math.min(right, ...crossings) + sourceX) / 2
  return {
    path: `M ${sourceX} ${sourceY} V ${laneY} H ${targetX} V ${targetY}`,
    laneY,
    labelX,
    labelY: laneY - 10
  }
}
