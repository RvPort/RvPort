import type {
  ProjectKnowledgeBaseFindingDetail,
  ProjectKnowledgeBaseScan
} from '@/types/project/knowledge-base'

export function createKnowledgeScan(
  overrides: Partial<ProjectKnowledgeBaseScan> = {}
): ProjectKnowledgeBaseScan {
  return {
    id: 'scan-1',
    taskId: 'task-1',
    sourceRevisionId: 'source-1',
    kbVersion: '1',
    scope: { mode: 'ALL', paths: [] },
    status: 'SUCCEEDED',
    summary: {
      summary: {
        files: 2,
        findings: 1,
        issues: 1,
        hints: 0,
        blocking: 1,
        portabilityGaps: 0,
        inactiveOrGuarded: 0,
        status: 'complete'
      }
    },
    startedAt: '2026-10-28T00:00:00Z',
    finishedAt: '2026-10-28T00:01:00Z',
    ...overrides
  }
}

export function createKnowledgeFinding(
  overrides: Partial<ProjectKnowledgeBaseFindingDetail> = {}
): ProjectKnowledgeBaseFindingDetail {
  return {
    id: 'finding-1',
    category: 'x86_intrinsic',
    description: '使用了 x86 intrinsic',
    evidence: {},
    filePath: 'src/main.cpp',
    lineStart: 8,
    lineEnd: 8,
    ruleId: 'rule-1',
    severity: 'high',
    status: 'DETECTED',
    suggestion: '替换为可移植实现',
    symbol: '_mm_add_ps',
    ...overrides
  }
}
