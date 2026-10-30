import type {
  ProjectPanelId,
  ProjectPipelineStageStatus,
  ProjectWorkflowStageStatus,
  ProjectWorkflowStatus
} from '@/types/project'

import { normalizeProjectPipelineStageStatus } from '../stageStatus'
import { deriveWorkflowPresentation, getWorkflowAssistText } from './presentation'
import { workflowStageById } from './topology'

export function getCurrentWorkflowStageId(
  recommendedStageId: ProjectPanelId | null,
  stages: ProjectWorkflowStageStatus[]
) {
  if (recommendedStageId !== 'kb') return recommendedStageId

  const x86Status = stages.find((stage) => stage.id === 'x86-build')?.status
  const kbStatus = stages.find((stage) => stage.id === 'kb')?.status

  // The backend still lists KB before x86 build. Keep the display order when both need attention.
  if (x86Status && x86Status !== 'DONE' && x86Status === kbStatus) return 'x86-build'

  return recommendedStageId
}

export function getWorkflowStageTitle(stageId?: string) {
  return workflowStageById.get(stageId ?? '')?.title
}

export function getWorkflowStageData(stageId?: string, workflowStatus?: ProjectWorkflowStatus) {
  const stage = workflowStageById.get(stageId ?? '')

  if (!stage) return null

  const presentation = deriveWorkflowPresentation(workflowStatus).get(stage.id)
  const status = presentation?.status ?? (stage.status.toUpperCase() as ProjectPipelineStageStatus)
  const assistStatus = presentation?.assistStatus ?? null
  const fact = workflowStatus?.stages.find((item) => item.id === stage.id)
  const analysisWarning =
    fact?.analysisStatus === 'PARTIAL' || fact?.analysisComplete === false
      ? '部分分析完成'
      : undefined

  return {
    analysisWarning,
    assistStatus,
    assistText: assistStatus
      ? getWorkflowAssistText(assistStatus, presentation?.dependencyIds ?? [])
      : undefined,
    id: stage.id,
    order: stage.order,
    subtitle: '',
    title: stage.title,
    status: normalizeProjectPipelineStageStatus(status)
  }
}
