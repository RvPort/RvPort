import { describe, expect, it } from 'vitest'

import type { ProjectArchiveItem } from '@/types/project'

import {
  formatArchiveMetadataLabel,
  formatArchiveMetadataValue,
  formatBytes,
  getArchiveCandidateTypeLabel,
  getArchiveItemTitle,
  getArchiveNodeTypeLabel,
  getArchiveStatusColor,
  getArchiveStatusText,
  getSourceRevisionLabel
} from './presentation'

const item: ProjectArchiveItem = {
  id: 'item-1',
  candidateId: 'candidate-1',
  nodeType: 'AUTOMATED_TEST',
  candidateType: 'REPORT',
  candidateKey: 'test-report',
  resourceType: 'TEST_RUN',
  resourceId: 'run-1',
  title: 'Custom title',
  artifactRefs: [],
  metadata: {},
  createdAt: '2026-10-27T08:00:00Z'
}

describe('archive presentation', () => {
  it('keeps known and unknown task states readable', () => {
    expect(getArchiveStatusText('SUCCEEDED')).toBe('归档完成')
    expect(getArchiveStatusText('CUSTOM')).toBe('CUSTOM')
    expect(getArchiveStatusText(null)).toBe('缺失状态数据')
    expect(getArchiveStatusColor('SUCCEEDED')).toBe('success')
    for (const status of ['FAILED', 'CANCELLED', 'TIMEOUT'])
      expect(getArchiveStatusColor(status)).toBe('error')
    for (const status of ['RUNNING', 'QUEUED', 'PENDING'])
      expect(getArchiveStatusColor(status)).toBe('processing')
    expect(getArchiveStatusColor('CUSTOM')).toBe('default')
  })

  it('falls back through source version metadata', () => {
    expect(getSourceRevisionLabel()).toBe('未标记版本')
    expect(
      getSourceRevisionLabel({ versionLabel: 'v1', gitCommit: 'abc', gitBranch: 'main' })
    ).toBe('v1')
    expect(
      getSourceRevisionLabel({ versionLabel: null, gitCommit: 'abc', gitBranch: 'main' })
    ).toBe('abc')
    expect(getSourceRevisionLabel({ versionLabel: null, gitCommit: null, gitBranch: 'main' })).toBe(
      'main'
    )
  })

  it('formats byte sizes across units and missing data', () => {
    expect(formatBytes(null)).toBe('缺失归档包大小数据')
    expect(formatBytes(0)).toBe('0 B')
    expect(formatBytes(1024)).toBe('1.0 KB')
    expect(formatBytes(1024 * 1024)).toBe('1.0 MB')
  })

  it('names known items and preserves unknown backend values', () => {
    expect(getArchiveNodeTypeLabel('AUTOMATED_TEST')).toBe('自动化测试')
    expect(getArchiveNodeTypeLabel('CUSTOM')).toBe('CUSTOM')
    expect(getArchiveCandidateTypeLabel('REPORT')).toBe('分析报告')
    expect(getArchiveCandidateTypeLabel('CUSTOM')).toBe('CUSTOM')
    expect(getArchiveItemTitle({ ...item, metadata: { testBatchRole: 'SOURCE' } })).toBe(
      '源端自动化测试'
    )
    expect(getArchiveItemTitle({ ...item, metadata: { testBatchRole: 'TARGET' } })).toBe(
      '目标端自动化测试'
    )
    expect(getArchiveItemTitle({ ...item, nodeType: 'CUSTOM' })).toBe('Custom title')
    expect(formatArchiveMetadataLabel('durationMs')).toBe('执行耗时')
    expect(formatArchiveMetadataLabel('unknown')).toBe('unknown')
  })

  it('formats metadata values by meaning and preserves structured data', () => {
    expect(formatArchiveMetadataValue('status', null)).toBe('--')
    expect(formatArchiveMetadataValue('status', '')).toBe('--')
    expect(formatArchiveMetadataValue('enabled', true)).toBe('是')
    expect(formatArchiveMetadataValue('enabled', false)).toBe('否')
    expect(formatArchiveMetadataValue('durationMs', 999)).toBe('999 ms')
    expect(formatArchiveMetadataValue('durationMs', 1500)).toBe('1.5 秒')
    expect(formatArchiveMetadataValue('durationMs', 15000)).toBe('15 秒')
    expect(formatArchiveMetadataValue('confidence', 0.876)).toBe('88%')
    expect(formatArchiveMetadataValue('count', 4)).toBe('4')
    expect(formatArchiveMetadataValue('status', 'SUCCEEDED')).toBe('成功')
    expect(formatArchiveMetadataValue('status', 'CUSTOM')).toBe('CUSTOM')
    expect(formatArchiveMetadataValue('detail', { attempts: 2 })).toBe('{"attempts":2}')
  })
})
