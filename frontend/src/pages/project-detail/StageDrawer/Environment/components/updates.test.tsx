import { useState } from 'react'
import { act, fireEvent, screen } from '@testing-library/react'
import type { ColumnsType } from 'antd/es/table'
import { expect, it, vi } from 'vitest'

import { createEnvironment, renderWithQueryClient } from '@/test/test-utils'
import type { ProjectEnvironment, ProjectRuntimeEnvironments } from '@/types/project'

const state = vi.hoisted(() => ({
  row: {} as ProjectEnvironment,
  setRow: vi.fn() as (row: ProjectEnvironment) => void
}))
vi.mock('@/components/BaseTable', () => ({
  default: function MockTable({
    columns,
    toolbar
  }: {
    columns: ColumnsType<ProjectEnvironment>
    toolbar: { actions: React.ReactNode }
  }) {
    const [row, setRow] = useState(state.row)
    state.setRow = setRow
    return (
      <div>
        {toolbar.actions}
        {columns.map((column, index) => (
          <div key={index}>
            {'render' in column && column.render
              ? (column.render(
                  'dataIndex' in column
                    ? row[column.dataIndex as keyof ProjectEnvironment]
                    : undefined,
                  row,
                  0
                ) as React.ReactNode)
              : null}
          </div>
        ))}
      </div>
    )
  }
}))
import List from './List'

it('keeps independent list actions current as metadata, busy state and runtime role change', async () => {
  state.row = createEnvironment({ enabled: true, role: 'TARGET', os: null, basicInfo: null })
  let actions = {
    checkingEnvironmentId: null as string | null,
    settingRuntimeEnvironmentId: null as string | null,
    onCheck: vi.fn(async () => {}),
    onCreate: vi.fn(),
    onEdit: vi.fn(),
    onHistory: vi.fn(),
    onRefresh: vi.fn(async () => {}),
    onRunScript: vi.fn(),
    onSetCurrent: vi.fn(async () => {})
  }
  let runtime: ProjectRuntimeEnvironments | undefined
  const draw = () => <List projectId="project" actions={actions} runtimeEnvironments={runtime} />
  const view = renderWithQueryClient(draw())
  expect(screen.getByRole('button', { name: '设为当前目标环境' })).toBeEnabled()
  for (const [key, label] of [
    ['onEdit', '修改环境'],
    ['onHistory', '执行记录'],
    ['onRunScript', '运行脚本']
  ] as const) {
    const old = actions[key]
    actions = { ...actions, [key]: vi.fn() }
    view.rerender(draw())
    fireEvent.click(screen.getByRole('button', { name: label }))
    expect(actions[key]).toHaveBeenCalledWith(state.row)
    expect(old).not.toHaveBeenCalled()
  }
  for (const [key, label] of [
    ['onCreate', /新建环境/],
    ['onRefresh', '刷新环境列表']
  ] as const) {
    const old = actions[key]
    actions = { ...actions, [key]: vi.fn() }
    view.rerender(draw())
    fireEvent.click(screen.getByRole('button', { name: label }))
    expect(actions[key]).toHaveBeenCalledOnce()
    expect(old).not.toHaveBeenCalled()
  }
  actions = { ...actions, onCheck: vi.fn(async () => {}) }
  view.rerender(draw())
  fireEvent.click(screen.getByRole('button', { name: '环境检查' }))
  fireEvent.click(await screen.findByRole('button', { name: '开始检查' }))
  expect(actions.onCheck).toHaveBeenCalledWith(state.row)
  actions = { ...actions, onSetCurrent: vi.fn(async () => {}) }
  view.rerender(draw())
  fireEvent.click(screen.getByRole('button', { name: '设为当前目标环境' }))
  fireEvent.click(await screen.findByRole('button', { name: '确认设置' }))
  expect(actions.onSetCurrent).toHaveBeenCalledWith(state.row)
  actions = { ...actions, checkingEnvironmentId: state.row.id }
  view.rerender(draw())
  expect(
    screen.getByRole('button', { name: /环境检查/ }).querySelector('.anticon-loading')
  ).not.toBeNull()
  actions = { ...actions, checkingEnvironmentId: null, settingRuntimeEnvironmentId: state.row.id }
  view.rerender(draw())
  expect(
    screen.getByRole('button', { name: /设为当前目标环境/ }).querySelector('.anticon-loading')
  ).not.toBeNull()
  runtime = {
    missing: [],
    projectId: 'project',
    ready: true,
    sourceEnvironment: null,
    targetEnvironment: {
      architecture: state.row.architecture,
      connectionStatus: 'SUCCESS',
      enabled: true,
      configVersion: 1,
      id: state.row.id,
      name: state.row.name,
      role: 'TARGET'
    },
    updatedAt: null,
    updatedBy: null,
    version: 1
  }
  view.rerender(draw())
  expect(screen.getByLabelText('当前目标环境')).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: /设为当前/ })).not.toBeInTheDocument()
  state.row = { ...state.row, role: 'JUMP_HOST' }
  act(() => state.setRow(state.row))
  view.rerender(draw())
  expect(screen.queryByLabelText('当前目标环境')).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: /设为当前/ })).not.toBeInTheDocument()
  state.row = { ...state.row, role: 'TARGET', enabled: false }
  act(() => state.setRow(state.row))
  runtime = undefined
  view.rerender(draw())
  expect(screen.getByRole('button', { name: '运行脚本' })).toBeDisabled()
  expect(screen.getByRole('button', { name: /设为当前目标环境/ })).toBeDisabled()
  expect(screen.getByText('已禁用')).toBeInTheDocument()
})
