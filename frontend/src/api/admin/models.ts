import type { PageResponse } from '@/types'
import type {
  AgentRuntime,
  ModelProfile,
  ModelProfileAgentProbeRun,
  ModelProfileConnectivityTestResult,
  ModelProfileListParams,
  ModelProfilePayload,
  ModelProfileStage,
  ModelStageAssignment
} from '@/types/admin/models'

import request, { LONG_RUNNING_REQUEST_TIMEOUT_MS } from '../request'

export function getModelProfiles(params: ModelProfileListParams) {
  return request.get<PageResponse<ModelProfile>>('/me/model-profiles', { params })
}

export function createModelProfile(data: ModelProfilePayload) {
  return request.post<ModelProfile>('/me/model-profiles', data)
}

export function updateModelProfile(profileId: string, data: ModelProfilePayload) {
  return request.patch<ModelProfile>(`/me/model-profiles/${profileId}`, data)
}

export function deleteModelProfile(profileId: string) {
  return request.delete<{ deleted: boolean }>(`/me/model-profiles/${profileId}`)
}

export function testModelProfileConnectivity(profileId: string) {
  return request.post<ModelProfileConnectivityTestResult>(
    `/me/model-profiles/${profileId}:test-connectivity`,
    undefined,
    { timeout: LONG_RUNNING_REQUEST_TIMEOUT_MS }
  )
}

export function testModelProfileAgents(profileId: string, agentRuntimeIds: string[]) {
  return request.post<ModelProfileAgentProbeRun>(`/me/model-profiles/${profileId}:test-agents`, {
    agentRuntimeIds
  })
}

export function getModelProfileAgentTestRun(runId: string) {
  return request.get<ModelProfileAgentProbeRun>(`/me/model-profile-agent-test-runs/${runId}`)
}

export function getAgentRuntimes() {
  return request.get<AgentRuntime[]>('/me/agent-runtimes')
}

export function getModelStageAssignments() {
  return request.get<ModelStageAssignment[]>('/me/model-stage-assignments')
}

export function updateModelStageAssignments(
  stage: ModelProfileStage,
  modelProfileId: string,
  agentRuntimeId: string
) {
  return request.patch<ModelStageAssignment[]>('/me/model-stage-assignments', {
    assignments: [{ stage, modelProfileId, agentRuntimeId }]
  })
}
