import { describe, expect, it } from 'vitest'

import type {
  ModelProfile,
  ModelProfileAgentProbeResult,
  ModelStageAssignment
} from '@/types/admin/models'

import {
  getStageStatus,
  isActiveProbeStatus,
  latestAgentProbeTime,
  modelStages
} from './presentation'

const probe: ModelProfileAgentProbeResult = {
  agentCompatible: true,
  agentRuntimeId: 'agent-1',
  agentType: 'CODEX',
  basicAvailable: true,
  checks: [],
  createdAt: '2026-10-27T08:00:00Z',
  details: {},
  durationMs: 100,
  errorCode: null,
  finishedAt: '2026-10-27T08:02:00Z',
  id: 'probe-1',
  message: null,
  modelProfileId: 'profile-1',
  runId: 'run-1',
  startedAt: '2026-10-27T08:01:00Z',
  status: 'SUCCESS',
  updatedAt: '2026-10-27T08:02:00Z'
}

const profile: ModelProfile = {
  apiKeyConfigured: true,
  apiKeyMasked: '***',
  baseUrl: 'https://example.com',
  createdAt: '2026-10-27T08:00:00Z',
  enabled: true,
  executablePath: 'codex',
  id: 'profile-1',
  lastConnectivityDetails: {},
  lastConnectivityDurationMs: 100,
  lastConnectivityMessage: null,
  lastConnectivityStatus: 'SUCCESS',
  lastConnectivityTestedAt: '2026-10-27T08:00:00Z',
  agentProbeResults: [probe],
  activeAgentProbeRun: null,
  maxRuntimeSeconds: 300,
  model: 'test-model',
  modelProviderType: 'OPENAI_COMPATIBLE',
  name: 'Test Profile',
  timeoutSeconds: 30,
  updatedAt: '2026-10-27T08:00:00Z',
  wireApi: 'RESPONSES'
}

const assignment: ModelStageAssignment = {
  agentRuntime: null,
  agentRuntimeId: 'agent-1',
  modelProfile: profile,
  modelProfileId: profile.id,
  stage: 'TEST_LOG_COMPARE',
  updatedAt: '2026-10-27T08:00:00Z'
}

describe('model stage presentation', () => {
  it('explains each readiness gate before marking a stage ready', () => {
    const stage = modelStages[0]
    expect(getStageStatus(stage, undefined, undefined).text).toBe('未绑定模型 Profile')
    expect(getStageStatus(stage, { ...assignment, agentRuntimeId: null }, profile).text).toBe(
      '未绑定 Agent 运行时'
    )
    expect(
      getStageStatus(
        stage,
        { ...assignment, modelProfile: { ...profile, enabled: false } },
        profile
      ).text
    ).toBe('绑定的 Profile 已停用')
    expect(getStageStatus(stage, assignment, undefined).text).toBe(
      '模型 Provider 尚未通过连通性测试'
    )
    expect(getStageStatus(stage, assignment, { ...profile, agentProbeResults: [] }).text).toBe(
      '尚未通过 Agent 兼容性测试'
    )
    expect(
      getStageStatus(stage, assignment, {
        ...profile,
        agentProbeResults: [{ ...probe, agentCompatible: false }]
      }).text
    ).toBe('尚未通过 Agent 兼容性测试')
    expect(getStageStatus(stage, assignment, profile).text).toBe(stage.readyText)
  })

  it('recognizes only in-progress probe states', () => {
    expect(isActiveProbeStatus('PENDING')).toBe(true)
    expect(isActiveProbeStatus('RUNNING')).toBe(true)
    expect(isActiveProbeStatus('SUCCESS')).toBe(false)
    expect(isActiveProbeStatus(undefined)).toBe(false)
  })

  it('uses the latest valid finish or start time', () => {
    expect(
      latestAgentProbeTime([
        probe,
        { ...probe, id: 'probe-2', finishedAt: null, startedAt: '2026-10-27T08:03:00Z' },
        { ...probe, id: 'probe-3', finishedAt: 'invalid', startedAt: null }
      ])
    ).toBe('2026-10-27T08:03:00.000Z')
    expect(latestAgentProbeTime([{ ...probe, finishedAt: null, startedAt: null }])).toBeNull()
    expect(latestAgentProbeTime([])).toBeNull()
  })
})
