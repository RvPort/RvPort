import { describe, expect, it } from 'vitest'

import { createWorkflowNodes } from './nodes'
import { workflowEdges, workflowGateways, workflowGroups, workflowStages } from './topology'

describe('current MVP workflow topology', () => {
  it('contains only current MVP stages, groups, and edges', () => {
    expect(workflowStages.map((stage) => stage.id)).toEqual([
      'project',
      'env',
      'assets',
      'x86-build',
      'rv-dependency-analysis',
      'kb',
      'memory-order-analysis',
      'floating-point-analysis',
      'report',
      'ai-migration',
      'manual',
      'build',
      'test',
      'optimize',
      'archive'
    ])
    expect(workflowGroups.map((group) => ({ id: group.id, stageIds: group.stageIds }))).toEqual([
      { id: 'source-data', stageIds: ['env', 'assets'] },
      {
        id: 'source-scan',
        stageIds: [
          'x86-build',
          'rv-dependency-analysis',
          'kb',
          'memory-order-analysis',
          'floating-point-analysis'
        ]
      },
      { id: 'assessment', stageIds: ['report', 'ai-migration', 'manual'] },
      { id: 'optimization-test', stageIds: ['build', 'test', 'optimize', 'archive'] }
    ])
    expect(workflowEdges.map((edge) => edge.id)).toEqual([
      'project-env',
      'project-assets',
      'env-x86-build',
      'assets-x86-build',
      'x86-build-kb',
      'kb-report',
      'x86-build-rv-dependency-analysis',
      'rv-dependency-analysis-report',
      'x86-build-memory-order-analysis',
      'x86-build-floating-point-analysis',
      'report-inputs-report',
      'report-ai-migration',
      'report-manual',
      'report-build',
      'ai-migration-assets',
      'manual-assets',
      'build-test',
      'optimize-archive',
      'test-optimize',
      'optimize-assets'
    ])
    expect(workflowGateways).toEqual([
      expect.objectContaining({
        id: 'report-inputs',
        prerequisiteIds: ['kb', 'rv-dependency-analysis'],
        targetId: 'report'
      })
    ])
    expect(workflowStages.some((stage) => stage.id === 'quickfix')).toBe(false)
    expect(workflowEdges.some((edge) => edge.id.includes('quickfix'))).toBe(false)
    expect(workflowStages.some((stage) => stage.id === 'sbom')).toBe(false)
    expect(workflowStages.some((stage) => stage.id === 'benchmark')).toBe(false)
  })

  it('keeps the current workflow focused on report findings and handling', () => {
    const byId = new Map(workflowStages.map((stage) => [stage.id, stage]))

    expect(byId.get('project')).toMatchObject({ title: '项目基础配置', prerequisiteIds: [] })
    expect(workflowStages.every((stage) => !('subtitle' in stage))).toBe(true)
    expect(byId.get('kb')?.prerequisiteIds).toEqual(['x86-build'])
    expect(byId.get('x86-build')?.order).toBeLessThan(byId.get('kb')?.order ?? 0)
    expect(byId.get('env')?.position.x).toBe(byId.get('assets')?.position.x)
    expect(byId.get('x86-build')?.position.x).toBeGreaterThan(byId.get('env')?.position.x ?? 0)
    expect(byId.get('kb')?.position.x).toBe(byId.get('rv-dependency-analysis')?.position.x)
    expect(byId.get('kb')?.position.x).toBeGreaterThan(byId.get('x86-build')?.position.x ?? 0)
    expect(byId.get('rv-dependency-analysis')?.position.y).toBeLessThan(
      byId.get('x86-build')?.position.y ?? 0
    )
    expect(byId.get('rv-dependency-analysis')?.position.y).toBeLessThan(
      byId.get('kb')?.position.y ?? 0
    )
    expect(byId.get('build')?.title).toBe('构建验证')
    expect(byId.get('build')?.position.x).toBeLessThan(2000)
    expect(byId.get('manual')?.position.x).toBe(byId.get('report')?.position.x)
    // AI 迁移与人工修复并列：同在评估报告下方的一列，都只依赖报告，都回流到源码管理。
    expect(byId.get('ai-migration')).toMatchObject({
      prerequisiteIds: ['report'],
      position: { x: byId.get('report')?.position.x }
    })
    expect(byId.get('ai-migration')?.order).toBeLessThan(byId.get('manual')?.order ?? 0)
    expect(byId.get('manual')?.position.y).toBe(
      (byId.get('ai-migration')?.position.y ?? 0) +
        (byId.get('kb')?.position.y ?? 0) -
        (byId.get('rv-dependency-analysis')?.position.y ?? 0)
    )
    expect(workflowEdges).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          source: 'report',
          sourceHandle: 'branch-source',
          target: 'ai-migration',
          targetHandle: 'branch-target'
        }),
        expect.objectContaining({ source: 'ai-migration', target: 'assets', feedback: true })
      ])
    )
    expect(workflowEdges).not.toEqual(
      expect.arrayContaining([expect.objectContaining({ source: 'kb', target: 'ai-migration' })])
    )
    expect(byId.get('optimize')?.position.x).toBe((byId.get('test')?.position.x ?? 0) + 240)
    expect(byId.get('archive')?.position.x).toBe((byId.get('optimize')?.position.x ?? 0) + 240)
    expect(byId.get('optimize')?.position.y).toBe(byId.get('test')?.position.y)
    expect(byId.get('archive')?.position.y).toBe(byId.get('test')?.position.y)
    expect(workflowEdges).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ source: 'kb', target: 'report-inputs' }),
        expect.objectContaining({ source: 'rv-dependency-analysis', target: 'report-inputs' }),
        expect.objectContaining({ source: 'report-inputs', target: 'report' }),
        expect.objectContaining({ source: 'x86-build', target: 'kb' }),
        expect.objectContaining({ source: 'assets', target: 'x86-build' }),
        expect.objectContaining({ source: 'env', target: 'x86-build' }),
        expect.objectContaining({
          source: 'report',
          sourceHandle: 'branch-source',
          target: 'manual',
          targetHandle: 'branch-target'
        }),
        expect.objectContaining({ source: 'report', target: 'build' })
      ])
    )
    expect(workflowEdges).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ source: 'optimize', target: 'archive' }),
        expect.objectContaining({
          source: 'test',
          target: 'optimize'
        }),
        expect.objectContaining({ source: 'optimize', target: 'assets', feedback: true })
      ])
    )
    expect(workflowEdges).not.toEqual(
      expect.arrayContaining([
        expect.objectContaining({ source: 'report', target: 'build-readiness-review' }),
        expect.objectContaining({
          source: 'rv-dependency-analysis',
          target: 'build-readiness-review'
        }),
        expect.objectContaining({ source: 'diff', target: 'build-readiness-review' }),
        expect.objectContaining({ source: 'manual', target: 'diff' })
      ])
    )
  })

  it('places four non-overlapping analyses after the standalone build column', () => {
    const analyses = workflowStages.filter((stage) =>
      ['kb', 'rv-dependency-analysis', 'memory-order-analysis', 'floating-point-analysis'].includes(
        stage.id
      )
    )
    expect(analyses).toHaveLength(4)
    expect(new Set(analyses.map((stage) => stage.position.x)).size).toBe(1)
    const sorted = analyses.toSorted((a, b) => a.position.y - b.position.y)
    expect(sorted.map((stage) => stage.id)).toEqual([
      'rv-dependency-analysis',
      'kb',
      'memory-order-analysis',
      'floating-point-analysis'
    ])
    expect(analyses.map((stage) => stage.id)).toEqual(sorted.map((stage) => stage.id))
    expect(workflowGroups.find((group) => group.id === 'source-scan')?.stageIds).toEqual([
      'x86-build',
      ...sorted.map((stage) => stage.id)
    ])
    expect(analyses.reduce((sum, stage) => sum + stage.position.y, 0) / analyses.length).toBe(
      workflowStages.find((stage) => stage.id === 'x86-build')?.position.y
    )
    for (const [index, stage] of sorted.entries()) {
      expect(stage.prerequisiteIds).toEqual(['x86-build'])
      if (index > 0) expect(stage.position.y - sorted[index - 1].position.y).toBe(130)
    }
    expect(
      workflowEdges.some(
        (edge) =>
          ['memory-order-analysis', 'floating-point-analysis'].includes(edge.source) &&
          edge.target === 'report-inputs'
      )
    ).toBe(false)
  })

  it('keeps visible group bounds separate', () => {
    const groups = createWorkflowNodes()
      .filter((node) => node.type === 'workflow-group')
      .map((node) => ({
        bottom: node.position.y + Number(node.style?.height),
        id: node.id,
        left: node.position.x,
        right: node.position.x + Number(node.style?.width),
        top: node.position.y
      }))

    for (const [index, group] of groups.entries()) {
      for (const other of groups.slice(index + 1)) {
        const overlaps =
          group.left < other.right &&
          group.right > other.left &&
          group.top < other.bottom &&
          group.bottom > other.top

        expect(overlaps, `${group.id} overlaps ${other.id}`).toBe(false)
      }
    }
  })

  it('keeps display prerequisites acyclic and ordered before their targets', () => {
    const stageIndex = new Map(workflowStages.map((stage, index) => [stage.id, index]))

    for (const [index, stage] of workflowStages.entries()) {
      for (const prerequisiteId of stage.prerequisiteIds) {
        expect(
          stageIndex.get(prerequisiteId),
          `${prerequisiteId} must precede ${stage.id}`
        ).toBeLessThan(index)
      }
    }
  })
})
