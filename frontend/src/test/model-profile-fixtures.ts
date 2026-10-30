import type { AgentRuntime, ModelProfile, ModelProfileAgentProbeResult } from '@/types/admin/models'

export function createAgentProbeResult(
  overrides: Partial<ModelProfileAgentProbeResult> = {}
): ModelProfileAgentProbeResult {
  return {
    id: 'result-1',
    runId: 'run-1',
    modelProfileId: 'profile-1',
    agentRuntimeId: 'runtime-1',
    agentType: 'CODEX',
    agentCompatible: true,
    basicAvailable: true,
    checks: [],
    details: {},
    durationMs: 100,
    errorCode: null,
    message: null,
    status: 'SUCCESS',
    startedAt: '2026-10-28T00:00:00Z',
    finishedAt: '2026-10-28T00:00:01Z',
    createdAt: '2026-10-28T00:00:00Z',
    updatedAt: '2026-10-28T00:00:01Z',
    ...overrides
  }
}

export function createModelProfile(overrides: Partial<ModelProfile> = {}): ModelProfile {
  return {
    id: 'profile-1',
    name: '测试模型',
    baseUrl: 'https://example.invalid/v1',
    model: 'model-v1',
    enabled: true,
    apiKeyConfigured: true,
    apiKeyMasked: '***test',
    executablePath: 'codex',
    modelProviderType: 'OPENAI_COMPATIBLE',
    wireApi: 'RESPONSES',
    maxRuntimeSeconds: 300,
    timeoutSeconds: 30,
    createdAt: '2026-10-28T00:00:00Z',
    updatedAt: '2026-10-28T00:00:00Z',
    lastConnectivityDetails: {},
    lastConnectivityDurationMs: null,
    lastConnectivityMessage: null,
    lastConnectivityStatus: null,
    lastConnectivityTestedAt: null,
    agentProbeResults: [],
    activeAgentProbeRun: null,
    ...overrides
  }
}

export function createAgentRuntime(overrides: Partial<AgentRuntime> = {}): AgentRuntime {
  return {
    id: 'runtime-1',
    name: '测试运行时',
    agentType: 'CODEX',
    enabled: true,
    lastProbeAt: null,
    lastProbeMessage: null,
    lastProbeStatus: null,
    createdAt: '2026-10-28T00:00:00Z',
    updatedAt: '2026-10-28T00:00:00Z',
    ...overrides
  }
}
