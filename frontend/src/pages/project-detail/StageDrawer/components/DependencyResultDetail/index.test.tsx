import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import type { ProjectDependencyAnalysisResult } from '@/types/project/dependency-analysis'

import { DependencyResultDetailContent } from '.'

vi.mock('@/components/CodeMirrorEditor', () => ({
  default: ({
    value,
    ariaLabel,
    readOnly
  }: {
    value: string
    ariaLabel: string
    readOnly: boolean
  }) => <textarea aria-label={ariaLabel} readOnly={readOnly} value={value} />
}))

function result(
  overrides: Partial<ProjectDependencyAnalysisResult> = {}
): ProjectDependencyAnalysisResult {
  return {
    id: 'dependency-1',
    dependencyName: 'libsource',
    dependencyType: 'LINK_LIBRARY',
    dependencyVersion: '1.0',
    sourceProviderPackage: 'libsource-dev',
    sourceProviderVersion: '1.0',
    sourceDetectedVersion: '1.0.1',
    targetProviderPackage: 'libtarget-dev',
    targetProviderVersion: '2.0',
    targetDetectedVersion: '2.0.1',
    providerVersionStatus: 'SAME_MAJOR',
    versionEvidenceConfidence: 'HIGH',
    targetMatchStatus: 'TARGET_INSTALLED',
    riskLevel: 'LOW',
    locationLabel: 'CMakeLists.txt:12',
    recommendation: '使用目标提供包',
    confidence: '0.95',
    evidence: { package: 'libsource', matches: ['libtarget'] },
    createdAt: '2026-10-28T00:00:00Z',
    ...overrides
  }
}

describe('dependency analysis detail', () => {
  it('renders provider versions, risk, location and structured evidence as read-only data', () => {
    const item = result()
    render(<DependencyResultDetailContent result={item} />)
    expect(screen.getByText('libsource-dev@1.0')).toBeInTheDocument()
    expect(screen.getByText('libtarget-dev@2.0')).toBeInTheDocument()
    expect(screen.getByText('目标已安装')).toBeInTheDocument()
    expect(screen.getByText('CMakeLists.txt:12')).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: '依赖分析证据' })).toHaveValue(
      JSON.stringify(item.evidence, null, 2)
    )
    expect(screen.getByRole('textbox', { name: '依赖分析证据' })).toHaveAttribute('readonly')
  })

  it('replaces all previous dependency metadata and evidence when another result is selected', () => {
    const view = render(<DependencyResultDetailContent result={result()} />)
    const next = result({
      id: 'dependency-2',
      dependencyName: 'runtime-next',
      dependencyType: 'RUNTIME_LIBRARY',
      dependencyVersion: null,
      sourceProviderPackage: 'runtime-source',
      sourceProviderVersion: null,
      sourceDetectedVersion: '3.0',
      targetProviderPackage: 'runtime-target',
      targetProviderVersion: '4.0',
      targetDetectedVersion: '4.1',
      providerVersionStatus: 'DIFFERENT',
      versionEvidenceConfidence: 'MEDIUM',
      targetMatchStatus: 'VERSION_MISMATCH',
      riskLevel: 'CRITICAL',
      locationLabel: '/usr/lib/runtime.so',
      recommendation: '重新验证 ABI',
      confidence: '0.55',
      evidence: { missingSymbols: ['runtime_next'] }
    })
    view.rerender(<DependencyResultDetailContent result={next} />)
    for (const old of [
      'libsource',
      'libsource-dev@1.0',
      'libtarget-dev@2.0',
      'CMakeLists.txt:12',
      '使用目标提供包',
      '0.95'
    ])
      expect(screen.queryByText(old)).not.toBeInTheDocument()
    for (const value of [
      'runtime-next',
      '运行时库',
      '版本不匹配',
      'runtime-source',
      'runtime-target@4.0',
      '3.0',
      '4.1',
      'DIFFERENT',
      'MEDIUM',
      '/usr/lib/runtime.so',
      '重新验证 ABI',
      '0.55'
    ])
      expect(screen.getByText(value)).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: '依赖分析证据' })).toHaveValue(
      JSON.stringify(next.evidence, null, 2)
    )
  })

  it('clears missing metadata and preserves unknown backend classifications without inventing a match', () => {
    const view = render(<DependencyResultDetailContent result={result()} />)
    view.rerender(
      <DependencyResultDetailContent
        result={result({
          dependencyType: 'FUTURE_DEPENDENCY',
          targetMatchStatus: 'FUTURE_MATCH',
          riskLevel: 'FUTURE_RISK',
          sourceProviderPackage: null,
          sourceProviderVersion: null,
          targetProviderPackage: null,
          targetProviderVersion: null,
          sourceDetectedVersion: null,
          targetDetectedVersion: null,
          providerVersionStatus: null,
          versionEvidenceConfidence: null,
          confidence: '',
          locationLabel: null,
          recommendation: null,
          evidence: {}
        })}
      />
    )
    expect(screen.getByText('FUTURE_DEPENDENCY')).toBeInTheDocument()
    expect(screen.getByText('FUTURE_MATCH')).toBeInTheDocument()
    expect(screen.getByText('FUTURE_RISK')).toBeInTheDocument()
    expect(screen.queryByText('目标已安装')).not.toBeInTheDocument()
    expect(screen.queryByText('使用目标提供包')).not.toBeInTheDocument()
    expect(screen.getAllByText('--').length).toBeGreaterThan(5)
    expect(screen.getByRole('textbox', { name: '依赖分析证据' })).toHaveValue('{}')
  })

  it('applies incremental metadata refreshes while retaining the dependency identity and evidence', () => {
    let snapshot = result()
    const view = render(<DependencyResultDetailContent result={snapshot} />)
    const updates: Array<[Partial<ProjectDependencyAnalysisResult>, string]> = [
      [{ dependencyName: 'libupdated' }, 'libupdated'],
      [{ sourceProviderVersion: '1.2' }, 'libsource-dev@1.2'],
      [{ targetProviderVersion: '2.2' }, 'libtarget-dev@2.2'],
      [{ riskLevel: 'HIGH' }, '高'],
      [{ dependencyType: 'RUNTIME_LIBRARY' }, '运行时库'],
      [{ targetMatchStatus: 'SOURCE_BUILD_REQUIRED' }, '需从源码构建'],
      [{ sourceProviderPackage: 'source-new' }, 'source-new@1.2'],
      [{ targetProviderPackage: 'target-new' }, 'target-new@2.2'],
      [{ sourceDetectedVersion: '1.2.1' }, '1.2.1'],
      [{ targetDetectedVersion: '2.2.1' }, '2.2.1'],
      [{ providerVersionStatus: 'RECHECKED' }, 'RECHECKED'],
      [{ versionEvidenceConfidence: 'MEDIUM' }, 'MEDIUM'],
      [{ confidence: '0.75' }, '0.75'],
      [{ locationLabel: 'src/link.cmake:25' }, 'src/link.cmake:25'],
      [{ recommendation: '验证重新构建的目标库' }, '验证重新构建的目标库']
    ]
    for (const [patch, expected] of updates) {
      snapshot = { ...snapshot, ...patch }
      view.rerender(<DependencyResultDetailContent result={snapshot} />)
      expect(screen.getByText(expected)).toBeInTheDocument()
      expect(screen.getByText(snapshot.dependencyName)).toBeInTheDocument()
      expect(screen.getByRole('textbox', { name: '依赖分析证据' })).toHaveValue(
        JSON.stringify(snapshot.evidence, null, 2)
      )
    }
    snapshot = { ...snapshot, evidence: { verified: true } }
    view.rerender(<DependencyResultDetailContent result={snapshot} />)
    expect(screen.getByText('验证重新构建的目标库')).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: '依赖分析证据' })).toHaveValue(
      '{\n  "verified": true\n}'
    )
  })
})
