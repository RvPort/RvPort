import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Form } from 'antd'
import type { ComponentProps } from 'react'
import { describe, expect, it, vi } from 'vitest'

import type { ProjectSourceArtifact, UpdateProjectSourceFromGitRequest } from '@/types/project'

import type { UploadValues } from '../types'
import { AssetsOverview } from './content'

type OverviewProps = Omit<ComponentProps<typeof AssetsOverview>, 'gitForm' | 'uploadForm'>
function LiveOverview(props: OverviewProps) {
  const [gitForm] = Form.useForm<UpdateProjectSourceFromGitRequest>()
  const [uploadForm] = Form.useForm<UploadValues>()
  return <AssetsOverview {...props} gitForm={gitForm} uploadForm={uploadForm} />
}

const artifact: ProjectSourceArtifact = {
  active: true,
  branch: 'main',
  id: 'source-1',
  ref: '8b3d2a1c85f36b0479fd2868cfc405646b73064f',
  repositoryUrl: 'https://example.com/rvport.git',
  type: 'GIT',
  updatedAt: '2026-08-27T08:00:00Z',
  version: 'source-version-8b3d2a1c85f36b0479fd2868cfc405646b73064f',
  versionNote: ''
}

function OverviewHarness({
  currentArtifact,
  onStartUpdate = vi.fn()
}: {
  currentArtifact?: ProjectSourceArtifact
  onStartUpdate?: () => void
}) {
  const [uploadForm] = Form.useForm<UploadValues>()
  const [gitForm] = Form.useForm<UpdateProjectSourceFromGitRequest>()

  return (
    <AssetsOverview
      artifact={currentArtifact}
      gitForm={gitForm}
      isEditing={false}
      isSaving={false}
      sourceMode="git"
      sourceModeLocked
      uploadForm={uploadForm}
      onCancel={vi.fn()}
      onGitSubmit={async () => undefined}
      onSourceModeChange={vi.fn()}
      onStartUpdate={onStartUpdate}
      onUploadSubmit={async () => undefined}
    />
  )
}

function EditableGitHarness({
  onSubmit,
  isSaving = false
}: {
  onSubmit: () => Promise<void>
  isSaving?: boolean
}) {
  const [uploadForm] = Form.useForm<UploadValues>()
  const [gitForm] = Form.useForm<UpdateProjectSourceFromGitRequest>()

  return (
    <AssetsOverview
      artifact={artifact}
      gitForm={gitForm}
      isEditing
      isSaving={isSaving}
      sourceMode="git"
      sourceModeLocked={false}
      uploadForm={uploadForm}
      onCancel={vi.fn()}
      onGitSubmit={onSubmit}
      onSourceModeChange={vi.fn()}
      onStartUpdate={vi.fn()}
      onUploadSubmit={async () => undefined}
    />
  )
}

describe('AssetsOverview', () => {
  it('refreshes source metadata independently and blocks replacement only while import is pending', async () => {
    let current = artifact
    let props: OverviewProps = {
      artifact: current,
      isEditing: false,
      isSaving: false,
      sourceMode: 'git',
      sourceModeLocked: false,
      onCancel: vi.fn(),
      onGitSubmit: vi.fn(async () => undefined),
      onUploadSubmit: vi.fn(async () => undefined),
      onSourceModeChange: vi.fn(),
      onStartUpdate: vi.fn()
    }
    const view = render(<LiveOverview {...props} />)
    const changes: Array<[Partial<ProjectSourceArtifact>, string]> = [
      [{ version: 'v2' }, 'v2'],
      [{ branch: 'dev' }, 'dev'],
      [{ ref: 'commit-next' }, 'commit-next'],
      [{ repositoryUrl: 'https://example.com/next.git' }, 'https://example.com/next.git'],
      [{ updatedAt: '2026-10-28T00:00:00Z' }, '2026-10-28 08:00'],
      [{ type: 'ARCHIVE', fileName: 'source.zip' }, 'source.zip']
    ]
    for (const [patch, expected] of changes) {
      current = { ...current, ...patch }
      props = { ...props, artifact: current }
      view.rerender(<LiveOverview {...props} />)
      expect(screen.getByText(expected)).toBeVisible()
      expect(screen.getByText(current.version)).toBeVisible()
    }
    for (const status of [
      'QUEUED',
      'RUNNING',
      'FAILED',
      'CANCELLED',
      'TIMEOUT',
      'SUCCEEDED'
    ] as const) {
      props = {
        ...props,
        sourceTask: {
          id: 'import-1',
          type: 'SOURCE_IMPORT',
          status,
          message: '同步失败详情',
          progress: 20
        }
      }
      view.rerender(<LiveOverview {...props} />)
      const update = screen.getByRole('button', { name: '更新当前源码' })
      if (status === 'QUEUED' || status === 'RUNNING') {
        expect(update).toBeDisabled()
        expect(screen.getByText('Git 同步正在执行，请等待任务完成。')).toBeVisible()
      } else {
        expect(update).toBeEnabled()
        if (status !== 'SUCCEEDED') expect(screen.getByText('同步失败详情')).toBeVisible()
      }
    }
    const replacement = vi.fn()
    view.rerender(<LiveOverview {...props} onStartUpdate={replacement} />)
    await userEvent.click(screen.getByRole('button', { name: '更新当前源码' }))
    expect(replacement).toHaveBeenCalledOnce()
    expect(props.onStartUpdate).not.toHaveBeenCalled()
    view.rerender(
      <LiveOverview
        {...props}
        artifact={{
          ...current,
          type: 'GIT',
          repositoryUrl: undefined,
          branch: undefined,
          ref: undefined
        }}
      />
    )
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    expect(screen.getAllByText('-')).toHaveLength(3)
  })

  it('handles upload rejection, clears its error when switching mode and uses current submit handlers', async () => {
    const submit = vi.fn().mockRejectedValue('server unavailable')
    let props: OverviewProps = {
      isEditing: true,
      isSaving: false,
      sourceMode: 'upload',
      sourceModeLocked: false,
      onCancel: vi.fn(),
      onGitSubmit: vi.fn(async () => undefined),
      onUploadSubmit: submit,
      onSourceModeChange: vi.fn(),
      onStartUpdate: vi.fn()
    }
    const view = render(<LiveOverview {...props} />)
    const fileInput = document.querySelector('input[type=file]') as HTMLInputElement
    await userEvent.upload(
      fileInput,
      new File(['source'], 'SOURCE.ZIP', { type: 'application/zip' })
    )
    await userEvent.click(screen.getByRole('button', { name: /上\s*传/ }))
    expect(await screen.findByText('源码提交失败，请稍后重试')).toBeVisible()
    expect(submit).toHaveBeenCalledWith(
      expect.objectContaining({ file: [expect.objectContaining({ name: 'SOURCE.ZIP' })] })
    )
    await userEvent.click(screen.getByText('从 Git 更新代码'))
    expect(props.onSourceModeChange).toHaveBeenCalledWith('git')
    expect(screen.queryByText('源码提交失败，请稍后重试')).not.toBeInTheDocument()
    props = { ...props, sourceMode: 'git', sourceModeLocked: true }
    view.rerender(<LiveOverview {...props} />)
    expect(screen.getByText('从 Git 更新代码')).toBeVisible()
    expect(screen.queryByRole('radio')).not.toBeInTheDocument()
    await userEvent.type(screen.getByLabelText('仓库地址'), 'https://')
    await userEvent.type(screen.getByLabelText('Ref'), 'dev')
    await userEvent.click(screen.getByRole('button', { name: /更\s*新/ }))
    expect(await screen.findByText('仓库地址格式不正确')).toBeVisible()
    expect(props.onGitSubmit).not.toHaveBeenCalled()
  })
  it('locks source inputs, source mode and cancel while submitting, and unlocks when settled', () => {
    const onSubmit = vi.fn(async () => undefined)
    const { rerender } = render(<EditableGitHarness isSaving onSubmit={onSubmit} />)
    expect(screen.getByLabelText('仓库地址')).toBeDisabled()
    expect(screen.getByLabelText('Ref')).toBeDisabled()
    expect(screen.getByRole('button', { name: /取\s*消/ })).toBeDisabled()
    for (const option of screen.getAllByRole('radio')) expect(option).toBeDisabled()
    rerender(<EditableGitHarness onSubmit={onSubmit} />)
    expect(screen.getByLabelText('仓库地址')).toBeEnabled()
    expect(screen.getByRole('button', { name: /取\s*消/ })).toBeEnabled()
  })
  it('presents the active Git source as the source used by subsequent stages', () => {
    render(<OverviewHarness currentArtifact={artifact} />)

    expect(screen.getByText('当前有效源码')).toBeInTheDocument()
    expect(screen.getByText('后续扫描与构建将使用此版本。')).toBeInTheDocument()
    expect(screen.getByText('当前版本')).toBeInTheDocument()
    expect(screen.getByText(artifact.version)).toBeVisible()
    expect(screen.getByRole('link', { name: artifact.repositoryUrl! })).toHaveAttribute(
      'href',
      artifact.repositoryUrl
    )
    expect(screen.getByText('Git 分支')).toBeInTheDocument()
    expect(screen.getByText('Git Commit')).toBeInTheDocument()
    expect(screen.getByText(artifact.ref!)).toBeVisible()
  })

  it('labels the update action as replacing the current source', async () => {
    const onStartUpdate = vi.fn()
    const user = userEvent.setup()

    render(<OverviewHarness currentArtifact={artifact} onStartUpdate={onStartUpdate} />)

    await user.click(screen.getByRole('button', { name: '更新当前源码' }))
    expect(onStartUpdate).toHaveBeenCalledOnce()
  })

  it('rejects credentials embedded in an HTTP repository URL before submitting', async () => {
    const onSubmit = vi.fn(async () => undefined)
    const user = userEvent.setup()
    render(<EditableGitHarness onSubmit={onSubmit} />)

    await user.type(screen.getByLabelText('仓库地址'), 'https://user:token@example.com/repo.git')
    await user.type(screen.getByLabelText('Ref'), 'main')
    await user.click(screen.getByRole('button', { name: /更\s*新/ }))

    await waitFor(() => {
      expect(
        screen.getByText('仓库地址中不能包含用户名、密码或 Token，请填写在下方凭据字段中')
      ).toBeVisible()
    })
    expect(onSubmit).not.toHaveBeenCalled()
  })

  it('contains a rejected submit promise after the request layer reports it', async () => {
    const onSubmit = vi.fn().mockRejectedValue(new Error('request failed'))
    const user = userEvent.setup()
    render(<EditableGitHarness onSubmit={onSubmit} />)

    await user.type(screen.getByLabelText('仓库地址'), 'https://example.com/repo.git')
    await user.type(screen.getByLabelText('Ref'), 'main')
    await user.click(screen.getByRole('button', { name: /更\s*新/ }))

    expect(onSubmit).toHaveBeenCalledOnce()
    expect(await screen.findByText('request failed')).toBeVisible()
  })
})
