import { render, screen } from '@testing-library/react'
import type { ComponentProps } from 'react'
import { describe, expect, it, vi } from 'vitest'

import type {
  KnowledgeBaseAiExtensionRun,
  KnowledgeBaseAiExtensionStatus
} from '@/types/project/knowledge-base'

import { AiSuggestionStatus } from './AiSuggestionStatus'

function run(status: KnowledgeBaseAiExtensionStatus): KnowledgeBaseAiExtensionRun {
  return {
    agentOutputArtifactId: null,
    agentRuntimeId: 'runtime-1',
    createdAt: '2026-09-11T06:00:00Z',
    finishedAt: null,
    id: 'ai-run-1',
    inputArtifactId: null,
    kbScanRunId: 'scan-1',
    modelProfileId: 'model-1',
    outputArtifactId: null,
    projectId: 'project-1',
    sourceRevisionId: 'revision-1',
    startedAt: null,
    status,
    summary: { validResultCount: 3 },
    taskId: 'task-1',
    updatedAt: '2026-09-11T06:00:00Z'
  }
}

function renderStatus(overrides: Partial<ComponentProps<typeof AiSuggestionStatus>> = {}) {
  render(
    <AiSuggestionStatus
      creating={false}
      error={false}
      loading={false}
      readiness={{
        findingCount: 3,
        kbScanRunId: 'scan-1',
        model: {},
        modelConfigured: true,
        ready: true,
        reason: null
      }}
      onGenerate={vi.fn()}
      onRetry={vi.fn()}
      {...overrides}
    />
  )
}

describe('AiSuggestionStatus', () => {
  it.each([
    ['QUEUED', '排队中', '生成中'],
    ['RUNNING', '生成中', '生成中'],
    ['SUCCEEDED', '已生成', '重新生成'],
    ['PARTIAL_SUCCESS', '部分完成', '重新生成'],
    ['FAILED', '生成失败', '重新生成'],
    ['TIMEOUT', '已超时', '重新生成']
  ] as const)('renders the %s run state', (status, label, action) => {
    renderStatus({ run: run(status) })

    expect(screen.getAllByText(label).length).toBeGreaterThan(0)
    expect(screen.getByRole('button', { name: action })).toBeInTheDocument()
  })

  it('offers a retry when state loading fails', () => {
    renderStatus({ error: true })

    expect(screen.getByText('状态加载失败')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '重新读取' })).toBeInTheDocument()
  })
})

it.each([
  ['SUCCEEDED', '本次 AI 建议已经生成。'],
  ['PARTIAL_SUCCESS', '部分 Finding 已生成 AI 建议。']
] as const)('does not invent a result count for %s legacy AI runs', (status, description) => {
  renderStatus({ run: { ...run(status), summary: {} } })
  expect(screen.getByText(description)).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '重新生成' })).toBeEnabled()
})
it('explains a future readiness reason without enabling generation', () => {
  renderStatus({
    readiness: {
      ready: false,
      reason: 'NEW_SERVER_REASON',
      findingCount: 3,
      kbScanRunId: 'scan-1',
      model: {},
      modelConfigured: false
    }
  })
  expect(screen.getByText('当前暂不能生成 AI 建议')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '生成 AI 建议' })).toBeDisabled()
})
