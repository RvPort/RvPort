import type { NodeTypes } from '@xyflow/react'

import type { ProjectWorkflowStatus } from '@/types/project'

import { normalizeProjectPipelineStageStatus } from '../../stageStatus'
import {
  deriveWorkflowGatewayPresentation,
  deriveWorkflowPresentation,
  getWorkflowNodeHint
} from '../presentation'
import {
  WORKFLOW_GROUP_PADDING,
  WORKFLOW_STAGE_HEIGHT,
  WORKFLOW_STAGE_WIDTH,
  workflowGateways,
  workflowGroups,
  workflowStageById,
  type WorkflowStageDefinition,
  workflowStages
} from '../topology'
import type { PipelineStageNodeType } from './PipelineStageNode'
import { PipelineStageNode } from './PipelineStageNode'
import type { WorkflowGatewayNodeType } from './WorkflowGatewayNode'
import { WorkflowGatewayNode } from './WorkflowGatewayNode'
import type { WorkflowGroupNodeType } from './WorkflowGroupNode'
import { WorkflowGroupNode } from './WorkflowGroupNode'

export type WorkflowNodeType =
  PipelineStageNodeType | WorkflowGatewayNodeType | WorkflowGroupNodeType

const baseStageNodes: PipelineStageNodeType[] = workflowStages.map((stage) => ({
  id: stage.id,
  type: 'pipeline-stage',
  position: stage.position,
  zIndex: 3,
  data: {
    flowBlocked: false,
    focused: false,
    hint: '',
    order: stage.order,
    title: stage.title,
    status: stage.status
  }
}))

const groupNodes: WorkflowGroupNodeType[] = workflowGroups.flatMap((group) => {
  const members = group.stageIds
    .map((stageId) => workflowStageById.get(stageId))
    .filter((stage): stage is WorkflowStageDefinition => Boolean(stage))

  if (members.length === 0) {
    return []
  }

  const padding = { ...WORKFLOW_GROUP_PADDING, ...group.padding }

  const minX = Math.min(...members.map((stage) => stage.position.x))
  const minY = Math.min(...members.map((stage) => stage.position.y))
  const maxX = Math.max(...members.map((stage) => stage.position.x + WORKFLOW_STAGE_WIDTH))
  const maxY = Math.max(...members.map((stage) => stage.position.y + WORKFLOW_STAGE_HEIGHT))

  return [
    {
      id: `group-${group.id}`,
      type: 'workflow-group',
      position: {
        x: minX - padding.left,
        y: minY - padding.top
      },
      data: {
        title: group.title,
        tone: group.tone
      },
      draggable: false,
      selectable: false,
      focusable: false,
      // The border stays above connector lines, while stages remain one layer higher.
      zIndex: 2,
      style: {
        width: maxX - minX + padding.left + padding.right,
        height: maxY - minY + padding.top + padding.bottom
      }
    }
  ]
})

const baseGatewayNodes: WorkflowGatewayNodeType[] = workflowGateways.map((gateway) => ({
  id: gateway.id,
  type: 'workflow-gateway',
  position: gateway.position,
  zIndex: 3,
  data: {
    completed: 0,
    state: 'WAITING',
    title: '评估输入',
    total: gateway.prerequisiteIds.length
  }
}))

const defaultWorkflowNodes: WorkflowNodeType[] = [
  ...groupNodes,
  ...baseStageNodes,
  ...baseGatewayNodes
]

export function getWorkflowStageCenter(stageId?: string) {
  const stage = workflowStageById.get(stageId ?? '')

  if (!stage) {
    return null
  }

  return {
    x: stage.position.x + WORKFLOW_STAGE_WIDTH / 2,
    y: stage.position.y + WORKFLOW_STAGE_HEIGHT / 2
  }
}

export function getWorkflowGroupCenter(groupId?: string) {
  const group = workflowGroups.find((item) => item.id === groupId)

  if (!group) {
    return null
  }

  const members = group.stageIds
    .map((stageId) => workflowStageById.get(stageId))
    .filter((stage): stage is WorkflowStageDefinition => Boolean(stage))

  if (members.length === 0) {
    return null
  }

  const minX = Math.min(...members.map((stage) => stage.position.x))
  const minY = Math.min(...members.map((stage) => stage.position.y))
  const maxX = Math.max(...members.map((stage) => stage.position.x + WORKFLOW_STAGE_WIDTH))
  const maxY = Math.max(...members.map((stage) => stage.position.y + WORKFLOW_STAGE_HEIGHT))

  return {
    x: (minX + maxX) / 2,
    y: (minY + maxY) / 2
  }
}

export function createWorkflowNodes(
  workflowStatus?: ProjectWorkflowStatus,
  previousNodes?: WorkflowNodeType[]
): WorkflowNodeType[] {
  if (!workflowStatus) {
    return previousNodes === defaultWorkflowNodes ? previousNodes : defaultWorkflowNodes
  }

  const presentationByStageId = deriveWorkflowPresentation(workflowStatus)
  const presentationByGatewayId = deriveWorkflowGatewayPresentation(workflowStatus)
  const reasonCodeByStageId = new Map(
    workflowStatus.stages.map((stage) => [stage.id, stage.reasonCode])
  )
  const previousStageNodes = new Map(
    previousNodes
      ?.filter((node): node is PipelineStageNodeType => node.type === 'pipeline-stage')
      .map((node) => [node.id, node])
  )
  let changed = previousNodes === undefined

  const nextStageNodes = baseStageNodes.map((node) => {
    const presentation = presentationByStageId.get(node.id)
    const nextStatus = normalizeProjectPipelineStageStatus(presentation?.status)
    const nextFlowBlocked = presentation?.flowBlocked ?? false
    const nextFocused = workflowStatus.currentStageId === node.id && nextStatus !== 'done'
    const analysis = workflowStatus.stages.find((stage) => stage.id === node.id)
    const partial = analysis?.analysisStatus === 'PARTIAL' || analysis?.analysisComplete === false
    const nextHint = partial
      ? `部分分析完成 · ${analysis?.message || analysis?.messageEn || '零正式风险不代表迁移安全'}`
      : presentation
        ? getWorkflowNodeHint(node.id, presentation, reasonCodeByStageId.get(node.id))
        : ''
    const previousNode = previousStageNodes.get(node.id)

    if (
      previousNode?.data.status === nextStatus &&
      previousNode.data.flowBlocked === nextFlowBlocked &&
      previousNode.data.focused === nextFocused &&
      previousNode.data.hint === nextHint
    ) {
      return previousNode
    }

    changed = true

    if (
      node.data.status === nextStatus &&
      node.data.flowBlocked === nextFlowBlocked &&
      node.data.focused === nextFocused &&
      node.data.hint === nextHint
    ) {
      return node
    }

    return {
      ...node,
      data: {
        ...node.data,
        flowBlocked: nextFlowBlocked,
        focused: nextFocused,
        hint: nextHint,
        status: nextStatus
      }
    }
  })

  const previousGatewayNodes = new Map(
    previousNodes
      ?.filter((node): node is WorkflowGatewayNodeType => node.type === 'workflow-gateway')
      .map((node) => [node.id, node])
  )
  const nextGatewayNodes = baseGatewayNodes.map((node) => {
    const presentation = presentationByGatewayId.get(node.id)
    const previousNode = previousGatewayNodes.get(node.id)

    if (
      previousNode &&
      presentation &&
      previousNode.data.completed === presentation.completed &&
      previousNode.data.state === presentation.state &&
      previousNode.data.total === presentation.total
    ) {
      return previousNode
    }

    changed = true
    return presentation ? { ...node, data: { ...node.data, ...presentation } } : node
  })

  if (!changed && previousNodes) return previousNodes
  return [...groupNodes, ...nextStageNodes, ...nextGatewayNodes]
}

export const nodeTypes = {
  'pipeline-stage': PipelineStageNode,
  'workflow-gateway': WorkflowGatewayNode,
  'workflow-group': WorkflowGroupNode
} satisfies NodeTypes
