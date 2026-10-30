import type { ColumnsType } from 'antd/es/table'
import { expect, it } from 'vitest'

import { sortColumns } from './sortColumns'

it('applies sorting recursively without mutating caller-owned grouped columns', () => {
  const fixed = { key: 'status', dataIndex: 'status', title: '状态' }
  const columns: ColumnsType<{ name: string; status: string }> = [
    { title: '基本信息', children: [{ dataIndex: 'name', title: '名称', sorter: true }, fixed] }
  ]
  const sorted = sortColumns(columns, 'name', 'descend')!
  expect(sorted).toEqual([
    {
      title: '基本信息',
      children: [{ dataIndex: 'name', title: '名称', sorter: true, sortOrder: 'descend' }, fixed]
    }
  ])
  expect(columns).not.toEqual(sorted)
  expect('children' in columns[0] && 'sortOrder' in columns[0].children[0]).toBe(false)
  const group = sorted[0]
  expect('children' in group && group.children[1]).toBe(fixed)
})
it('clears sorting when the order or selected column is absent and accepts missing columns', () => {
  expect(sortColumns(undefined)).toBeUndefined()
  expect(sortColumns([{ dataIndex: 'name', sorter: true }], 'name')).toEqual([
    { dataIndex: 'name', sorter: true, sortOrder: null }
  ])
  expect(sortColumns([{ dataIndex: 'name', sorter: true }], 'other', 'ascend')).toEqual([
    { dataIndex: 'name', sorter: true, sortOrder: null }
  ])
})
