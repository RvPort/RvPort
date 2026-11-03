import { act, fireEvent, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { advanceAuthSession } from '@/store/authSession'
import { renderWithQueryClient } from '@/test/test-utils'
import { feedback } from '@/utils/feedback'

import { MigrationInputActions, MigrationInputDetailFooter } from './MigrationInputActions'

const { updateProjectMigrationInput, importProjectMigrationInputs, getProjectMigrationInputs } =
  vi.hoisted(() => ({
    importProjectMigrationInputs: vi.fn(),
    getProjectMigrationInputs: vi.fn(),
    updateProjectMigrationInput: vi.fn()
  }))

vi.mock('@/api/project/migration-input', () => ({
  getProjectMigrationInputs,
  importProjectMigrationInputs,
  updateProjectMigrationInput
}))

const defaultProps = {
  migrationInputRefId: 'migration-input-1',
  projectId: 'project-1',
  sourceRefId: 'result-1',
  sourceRevisionId: 'source-1',
  sourceType: 'DEPENDENCY_RESULT' as const
}

describe('MigrationInputActions', () => {
  it('内存序候选纳入只改变报告选择，仍待核实，新 Finding 不继承确认', async () => {
    const key = ['memory-order-candidate']
    const candidate = {
      id: 'result-1',
      runId: 'run-1',
      findingKind: 'SOURCE_CANDIDATE',
      severity: 'INFO',
      confidence: null,
      detail: { conclusion: { verdict: 'source-candidate' } },
      reportInclusion: { confirmedForNextReport: false, migrationInputStatus: null }
    }
    updateProjectMigrationInput.mockResolvedValue({ id: 'migration-input-1', status: 'CONFIRMED' })
    const props = {
      ...defaultProps,
      sourceType: 'MEMORY_ORDER_FINDING' as const,
      pendingVerification: true,
      invalidateQueryKeys: [key]
    }
    const view = renderWithQueryClient(<MigrationInputDetailFooter {...props} status={null} />)
    view.queryClient.setQueryData(key, candidate)
    await userEvent.click(screen.getByRole('button', { name: '纳入报告' }))
    await screen.findByRole('button', { name: /排\s*除/ })
    expect(view.queryClient.getQueryData(key)).toMatchObject({
      findingKind: 'SOURCE_CANDIDATE',
      severity: 'INFO',
      confidence: null,
      detail: candidate.detail,
      reportInclusion: { confirmedForNextReport: true, migrationInputStatus: 'CONFIRMED' }
    })
    view.rerender(<MigrationInputDetailFooter {...props} status="CONFIRMED" />)
    expect(screen.getByText('已纳入，仍待核实')).toBeVisible()
    view.rerender(
      <MigrationInputDetailFooter {...props} sourceRefId="result-new-run" status={null} />
    )
    expect(screen.getByText('待核实项，默认待确认')).toBeVisible()
    expect(screen.queryByText('已纳入，仍待核实')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '纳入报告' })).toBeVisible()
  })
  it('patches nested report inclusion metadata while preserving unrelated and malformed cached entries', async () => {
    updateProjectMigrationInput.mockResolvedValue({ id: 'migration-input-1', status: 'CONFIRMED' })
    const key = ['source-results']
    const view = renderWithQueryClient(
      <MigrationInputActions {...defaultProps} invalidateQueryKeys={[key]} />
    )
    const untouched = { id: 'other', reportInclusion: { confirmedForNextReport: false } }
    view.queryClient.setQueryData([...key, 'list'], {
      items: [
        null,
        'legacy',
        untouched,
        {
          id: 'result-1',
          reportInclusion: {
            includedInLatestReport: true,
            latestReportId: 'report-old',
            confirmedForNextReport: false
          }
        }
      ]
    })
    view.queryClient.setQueryData([...key, 'primitive'], 'unchanged')
    view.queryClient.setQueryData([...key, 'null'], null)
    view.queryClient.setQueryData([...key, 'detail'], { id: 'result-1', reportInclusion: 'legacy' })
    await userEvent.click(screen.getByRole('button', { name: '纳入报告' }))
    await screen.findByRole('button', { name: '排除' })
    expect(view.queryClient.getQueryData([...key, 'list'])).toEqual({
      items: [
        null,
        'legacy',
        untouched,
        {
          id: 'result-1',
          migrationInputRefId: 'migration-input-1',
          migrationInputStatus: 'CONFIRMED',
          reportInclusion: {
            includedInLatestReport: true,
            latestReportId: 'report-old',
            confirmedForNextReport: true,
            migrationInputRefId: 'migration-input-1',
            migrationInputStatus: 'CONFIRMED'
          }
        }
      ]
    })
    expect(view.queryClient.getQueryData([...key, 'primitive'])).toBe('unchanged')
    expect(view.queryClient.getQueryData([...key, 'null'])).toBeNull()
    expect(view.queryClient.getQueryData([...key, 'detail'])).toMatchObject({
      reportInclusion: 'legacy',
      migrationInputStatus: 'CONFIRMED'
    })
  })

  it('reports an absent imported reference, remains retryable and respects a refreshed initial status', async () => {
    const error = vi.spyOn(feedback, 'error').mockImplementation(() => undefined)
    importProjectMigrationInputs.mockResolvedValue({ items: [] })
    getProjectMigrationInputs.mockResolvedValue({
      items: [],
      hasMore: false,
      page: 1,
      pageSize: 200,
      total: 0
    })
    const view = renderWithQueryClient(
      <MigrationInputActions {...defaultProps} migrationInputRefId={null} />
    )
    await userEvent.click(screen.getByRole('button', { name: '纳入报告' }))
    await waitFor(() => expect(error).toHaveBeenCalledWith('报告问题状态更新失败'))
    expect(updateProjectMigrationInput).not.toHaveBeenCalled()
    await waitFor(() => expect(screen.getByRole('button', { name: /纳入报告/ })).toBeEnabled())
    updateProjectMigrationInput.mockResolvedValue({ id: 'migration-input-2', status: 'CONFIRMED' })
    view.rerender(
      <MigrationInputActions {...defaultProps} migrationInputRefId="migration-input-2" />
    )
    await userEvent.click(screen.getByRole('button', { name: /纳入报告/ }))
    await screen.findByRole('button', { name: '排除' })
    view.rerender(
      <MigrationInputActions
        {...defaultProps}
        migrationInputRefId="migration-input-2"
        initialStatus="EXCLUDED"
        presentation="footer"
      />
    )
    expect(screen.getByRole('button', { name: '纳入报告' })).toHaveClass('ant-btn-primary')
    expect(screen.queryByRole('button', { name: '排除' })).not.toBeInTheDocument()
    error.mockRestore()
  })
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('refreshes report readiness and optimization even from a detail without supplied keys', async () => {
    updateProjectMigrationInput.mockResolvedValue({ id: 'migration-input-1', status: 'CONFIRMED' })
    const { queryClient } = renderWithQueryClient(<MigrationInputActions {...defaultProps} />)
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')
    await userEvent.click(screen.getByRole('button', { name: '纳入报告' }))
    await waitFor(() =>
      expect(invalidate).toHaveBeenCalledWith({
        queryKey: ['project-stage-readiness', 'project-1', 'report']
      })
    )
    expect(invalidate).toHaveBeenCalledWith({
      queryKey: ['project-optimization-cycles', 'project-1']
    })
  })

  it('only offers inclusion for an unprocessed item', () => {
    renderWithQueryClient(<MigrationInputActions {...defaultProps} initialStatus="CANDIDATE" />)

    expect(screen.getByRole('button', { name: '纳入报告' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '排除' })).not.toBeInTheDocument()
  })

  it('only offers exclusion for an included item', async () => {
    const user = userEvent.setup()
    updateProjectMigrationInput.mockResolvedValue({
      id: 'migration-input-1',
      status: 'EXCLUDED'
    })
    renderWithQueryClient(<MigrationInputActions {...defaultProps} initialStatus="CONFIRMED" />)

    expect(screen.queryByRole('button', { name: '纳入报告' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '排除' }))
    await user.click(await screen.findByRole('button', { name: '确认排除' }))

    expect(updateProjectMigrationInput).toHaveBeenCalledWith('migration-input-1', {
      status: 'EXCLUDED'
    })
  })

  it('only offers inclusion for an excluded item', async () => {
    const user = userEvent.setup()
    updateProjectMigrationInput.mockResolvedValue({
      id: 'migration-input-1',
      status: 'CONFIRMED'
    })
    renderWithQueryClient(<MigrationInputActions {...defaultProps} initialStatus="EXCLUDED" />)

    expect(screen.queryByRole('button', { name: '排除' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '纳入报告' }))

    expect(updateProjectMigrationInput).toHaveBeenCalledWith('migration-input-1', {
      status: 'CONFIRMED'
    })
  })

  it('synchronizes cached list and detail status after exclusion', async () => {
    const user = userEvent.setup()
    updateProjectMigrationInput.mockResolvedValue({
      id: 'migration-input-1',
      status: 'EXCLUDED'
    })
    const detailKey = ['dependency-analysis-result', 'result-1']
    const listKey = ['dependency-analysis-results', 'analysis-1']
    const { queryClient } = renderWithQueryClient(
      <MigrationInputActions
        {...defaultProps}
        initialStatus="CONFIRMED"
        invalidateQueryKeys={[detailKey, listKey]}
      />
    )
    queryClient.setQueryData(detailKey, {
      id: 'result-1',
      migrationInputRefId: 'migration-input-1',
      migrationInputStatus: 'CONFIRMED'
    })
    queryClient.setQueryData(listKey, {
      items: [{ id: 'result-1', migrationInputStatus: 'CONFIRMED' }]
    })

    await user.click(screen.getByRole('button', { name: '排除' }))
    await user.click(await screen.findByRole('button', { name: '确认排除' }))

    await waitFor(() => {
      expect(queryClient.getQueryData(detailKey)).toMatchObject({
        migrationInputStatus: 'EXCLUDED'
      })
      expect(queryClient.getQueryData(listKey)).toMatchObject({
        items: [expect.objectContaining({ migrationInputStatus: 'EXCLUDED' })]
      })
    })
  })

  it('uses the default button size in a detail footer', () => {
    renderWithQueryClient(<MigrationInputDetailFooter {...defaultProps} status="CONFIRMED" />)

    expect(screen.getByText('报告状态')).toBeInTheDocument()
    expect(screen.getByText('已纳入报告')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /排\s*除/ })).not.toHaveClass('ant-btn-sm')
  })
  it('does not issue the second write after auth changes between import and update', async () => {
    let finish!: (value: unknown) => void
    importProjectMigrationInputs.mockReturnValue(
      new Promise((resolve) => {
        finish = resolve
      })
    )
    renderWithQueryClient(<MigrationInputActions {...defaultProps} migrationInputRefId={null} />)
    fireEvent.click(screen.getByRole('button', { name: '纳入报告' }))
    await waitFor(() => expect(importProjectMigrationInputs).toHaveBeenCalledOnce())
    await act(async () => {
      finish({ items: [{ id: 'imported' }] })
      advanceAuthSession()
    })
    expect(updateProjectMigrationInput).not.toHaveBeenCalled()
    expect(getProjectMigrationInputs).not.toHaveBeenCalled()
  })
  it('does not carry a local status override to a different source record', async () => {
    updateProjectMigrationInput.mockResolvedValue({ id: 'migration-input-1', status: 'CONFIRMED' })
    const { rerender } = renderWithQueryClient(
      <MigrationInputActions {...defaultProps} initialStatus="CANDIDATE" />
    )
    fireEvent.click(screen.getByRole('button', { name: '纳入报告' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '排除' })).toBeInTheDocument())
    rerender(
      <MigrationInputActions
        {...defaultProps}
        sourceRefId="different-result"
        initialStatus="CANDIDATE"
      />
    )
    expect(screen.getByRole('button', { name: '纳入报告' })).toBeInTheDocument()
  })

  it('releases the visible pending state when switching records during a write', async () => {
    let finish!: (value: unknown) => void
    updateProjectMigrationInput.mockReturnValue(
      new Promise((resolve) => {
        finish = resolve
      })
    )
    const { rerender } = renderWithQueryClient(<MigrationInputActions {...defaultProps} />)
    fireEvent.click(screen.getByRole('button', { name: '纳入报告' }))
    await waitFor(() => expect(updateProjectMigrationInput).toHaveBeenCalledOnce())
    rerender(
      <MigrationInputActions
        {...defaultProps}
        sourceRefId="result-2"
        migrationInputRefId="input-2"
      />
    )
    expect(screen.getByRole('button', { name: /纳入报告/ })).toBeEnabled()
    expect(screen.getByRole('button', { name: /纳入报告/ })).not.toHaveClass('ant-btn-loading')
    await act(async () => {
      finish({ id: 'migration-input-1', status: 'CONFIRMED' })
    })
    expect(screen.getByRole('button', { name: /纳入报告/ })).toBeEnabled()
  })

  it('looks up the matching input instead of modifying the first unrelated import', async () => {
    importProjectMigrationInputs.mockResolvedValue({
      items: [{ id: 'wrong-input', sourceRefId: 'other-result' }]
    })
    getProjectMigrationInputs.mockResolvedValue({
      items: [{ id: 'matching-input', sourceRefId: 'result-1' }],
      page: 1,
      pageSize: 200,
      total: 1,
      hasMore: false
    })
    updateProjectMigrationInput.mockResolvedValue({ id: 'matching-input', status: 'CONFIRMED' })
    renderWithQueryClient(<MigrationInputActions {...defaultProps} migrationInputRefId={null} />)
    fireEvent.click(screen.getByRole('button', { name: '纳入报告' }))
    await waitFor(() =>
      expect(updateProjectMigrationInput).toHaveBeenCalledWith('matching-input', {
        status: 'CONFIRMED'
      })
    )
    expect(updateProjectMigrationInput).toHaveBeenCalledOnce()
  })

  it('discards the optimistic status when the authentication session changes', async () => {
    updateProjectMigrationInput.mockResolvedValue({ id: 'migration-input-1', status: 'CONFIRMED' })
    renderWithQueryClient(<MigrationInputActions {...defaultProps} initialStatus="CANDIDATE" />)
    fireEvent.click(screen.getByRole('button', { name: '纳入报告' }))
    await screen.findByRole('button', { name: '排除' })
    act(() => advanceAuthSession())
    expect(screen.getByRole('button', { name: '纳入报告' })).toBeEnabled()
  })
})

it('excludes a confirmed footer entry once, updates nested inclusion metadata and can include it again', async () => {
  let finish!: (value: unknown) => void
  updateProjectMigrationInput.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve
      })
  )
  const key = ['report-footer-results']
  const view = renderWithQueryClient(
    <MigrationInputDetailFooter {...defaultProps} status="CONFIRMED" invalidateQueryKeys={[key]} />
  )
  view.queryClient.setQueryData(key, {
    id: defaultProps.sourceRefId,
    reportInclusion: { confirmedForNextReport: true, includedInLatestReport: true }
  })
  const exclude = screen.getByRole('button', { name: /排\s*除/ })
  expect(exclude).toHaveClass('ant-btn-default')
  fireEvent.click(exclude)
  fireEvent.click(await screen.findByRole('button', { name: '确认排除' }))
  await waitFor(() => expect(updateProjectMigrationInput).toHaveBeenCalledOnce())
  expect(exclude).toBeDisabled()
  await act(async () => finish({ id: defaultProps.migrationInputRefId, status: 'EXCLUDED' }))
  expect(view.queryClient.getQueryData(key)).toMatchObject({
    reportInclusion: { confirmedForNextReport: false, includedInLatestReport: true }
  })
  const include = await screen.findByRole('button', { name: '纳入报告' })
  expect(include).toHaveClass('ant-btn-primary')
  updateProjectMigrationInput.mockResolvedValueOnce({
    id: defaultProps.migrationInputRefId,
    status: 'CONFIRMED'
  })
  await waitFor(() => expect(include).toBeEnabled())
  fireEvent.click(include)
  await waitFor(() => expect(screen.getByRole('button', { name: /^排\s*除$/ })).toBeInTheDocument())
  expect(updateProjectMigrationInput).toHaveBeenLastCalledWith(defaultProps.migrationInputRefId, {
    status: 'CONFIRMED'
  })
})

it('retains the original status for a legacy update response and avoids inventing status when none is available', async () => {
  updateProjectMigrationInput.mockResolvedValue({ id: defaultProps.migrationInputRefId })
  const key = ['legacy-footer-result']
  const view = renderWithQueryClient(
    <MigrationInputActions {...defaultProps} initialStatus="EXCLUDED" invalidateQueryKeys={[key]} />
  )
  view.queryClient.setQueryData(key, { id: defaultProps.sourceRefId })
  fireEvent.click(screen.getByRole('button', { name: '纳入报告' }))
  await waitFor(() =>
    expect(view.queryClient.getQueryData(key)).toMatchObject({ migrationInputStatus: 'EXCLUDED' })
  )
  view.rerender(
    <MigrationInputActions {...defaultProps} sourceRefId="different" invalidateQueryKeys={[key]} />
  )
  fireEvent.click(screen.getByRole('button', { name: /纳入报告/ }))
  await waitFor(() => expect(updateProjectMigrationInput).toHaveBeenCalledTimes(2))
  expect(view.queryClient.getQueryData(key)).toMatchObject({ migrationInputStatus: 'EXCLUDED' })
  expect(screen.queryByRole('button', { name: '排除' })).not.toBeInTheDocument()
})
