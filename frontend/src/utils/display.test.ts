import { describe, expect, it } from 'vitest'

import {
  formatDateTime,
  formatDateTimeRange,
  formatDisplayValue,
  formatFileSize,
  getTaskStatusPresentation,
  parseEnvironmentVariables,
  toLabelOptions
} from './display'

describe('display utilities', () => {
  it('uses a shared placeholder for absent values', () => {
    expect(formatDisplayValue(null)).toBe('--')
    expect(formatDisplayValue(undefined)).toBe('--')
    expect(formatDisplayValue('ready', (value) => value.toUpperCase())).toBe('READY')
  })

  it('formats valid timestamps and hides invalid timestamps', () => {
    expect(formatDateTime('2026-07-13T08:00:00+08:00')).toBe('2026-07-13 08:00')
    expect(formatDateTime('not-a-date')).toBe('--')
  })

  it('formats task time ranges consistently', () => {
    expect(
      formatDateTimeRange({
        finishedAt: '2026-07-13T09:30:00+08:00',
        startedAt: '2026-07-13T08:00:00+08:00'
      })
    ).toBe('2026-07-13 08:00 - 09:30')
    expect(formatDateTimeRange({ finishedAt: null, startedAt: '2026-07-13T08:00:00+08:00' })).toBe(
      '2026-07-13 08:00'
    )
  })

  it('parses environment variables and presents task statuses consistently', () => {
    expect(parseEnvironmentVariables('A=1\nIGNORED\n B = two ')).toEqual({ A: '1', B: 'two' })
    expect(getTaskStatusPresentation('SUCCEEDED')).toEqual({ color: 'success', label: '成功' })
    expect(getTaskStatusPresentation('FAILED')).toEqual({ color: 'error', label: '失败' })
    expect(getTaskStatusPresentation('CANCELLED')).toEqual({ color: 'default', label: '已取消' })
    expect(getTaskStatusPresentation('TIMEOUT')).toEqual({ color: 'warning', label: '超时' })
    expect(getTaskStatusPresentation(undefined, { fallbackLabel: '未运行' })).toEqual({
      color: undefined,
      label: '未运行'
    })
    expect(getTaskStatusPresentation('SUCCEEDED', { labels: { SUCCEEDED: '完成' } })).toEqual({
      color: 'success',
      label: '完成'
    })
  })

  it('converts label records into select options', () => {
    expect(toLabelOptions({ ACTIVE: '启用', DISABLED: '停用' })).toEqual([
      { label: '启用', value: 'ACTIVE' },
      { label: '停用', value: 'DISABLED' }
    ])
  })

  it('uses Intl number formatting for file sizes', () => {
    expect(formatFileSize(1024)).toBe('1 KB')
    expect(formatFileSize(1.5 * 1024 ** 2)).toBe('1.5 MB')
    expect(formatFileSize(-1)).toBe('--')
  })
})

it('keeps cross-day task ranges and unknown statuses unambiguous', () => {
  expect(formatDateTimeRange({ startedAt: null, finishedAt: '2026-07-14T01:00:00+08:00' })).toBe(
    '2026-07-14 01:00'
  )
  expect(
    formatDateTimeRange({
      startedAt: '2026-07-13T23:30:00+08:00',
      finishedAt: '2026-07-14T01:00:00+08:00'
    })
  ).toBe('2026-07-13 23:30 至 2026-07-14 01:00')
  expect(getTaskStatusPresentation('FUTURE_STATUS')).toEqual({
    color: 'processing',
    label: 'FUTURE_STATUS'
  })
  expect(formatFileSize(Number.NaN)).toBe('--')
  expect(formatFileSize(Number.POSITIVE_INFINITY)).toBe('--')
  expect(formatFileSize(2 ** 30)).toBe('1 GB')
})
