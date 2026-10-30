import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, it, vi } from 'vitest'

import {
  createModelProfile,
  getAgentRuntimes,
  getModelProfiles,
  getModelStageAssignments
} from '@/api/admin/models'
import { renderWithStrictQueryClient as renderWithQueryClient } from '@/test/strict-mode-utils'
import type { ModelProfile } from '@/types/admin/models'

import AdminModelsPage from './index'

vi.mock('@/api/admin/models', () => ({
  createModelProfile: vi.fn(),
  deleteModelProfile: vi.fn(),
  getAgentRuntimes: vi.fn(),
  getModelProfileAgentTestRun: vi.fn(),
  getModelProfiles: vi.fn(),
  getModelStageAssignments: vi.fn(),
  testModelProfileAgents: vi.fn(),
  testModelProfileConnectivity: vi.fn(),
  updateModelProfile: vi.fn(),
  updateModelStageAssignments: vi.fn()
}))

it('keeps model creation owned by its editor and refreshes the table and bindings after saving', async () => {
  const user = userEvent.setup()
  vi.mocked(getModelProfiles).mockResolvedValue({
    items: [],
    total: 0,
    page: 1,
    pageSize: 20,
    hasMore: false
  })
  vi.mocked(getModelStageAssignments).mockResolvedValue([])
  vi.mocked(getAgentRuntimes).mockResolvedValue([])
  let finish!: (value: ModelProfile) => void
  vi.mocked(createModelProfile).mockImplementation(
    () =>
      new Promise((resolve) => {
        finish = resolve
      })
  )
  renderWithQueryClient(<AdminModelsPage />)
  await screen.findByRole('heading', { name: '知识库 AI 扩展' })
  await user.click(screen.getByRole('button', { name: /新增 Profile/ }))
  const dialog = await screen.findByRole('dialog')
  await user.type(within(dialog).getByLabelText('Profile 名称'), 'isolated-profile')
  await user.type(within(dialog).getByLabelText('模型 API 地址'), 'https://example.invalid/v1')
  await user.type(within(dialog).getByLabelText('模型名称'), 'test-model')
  await user.type(within(dialog).getByLabelText('API Key'), 'fake-key')
  await user.click(within(dialog).getByRole('button', { name: '保存配置' }))
  await waitFor(() => expect(createModelProfile).toHaveBeenCalledOnce())
  expect(within(dialog).getByLabelText('Profile 名称')).toBeDisabled()
  expect(within(dialog).getByRole('button', { name: /取\s*消/ })).toBeDisabled()
  finish({ id: 'created' } as ModelProfile)
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  await waitFor(() => expect(getModelStageAssignments).toHaveBeenCalledTimes(2))
  expect(vi.mocked(getModelProfiles).mock.calls.length).toBeGreaterThanOrEqual(4)
})
