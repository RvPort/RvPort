import { MemoryRouter } from 'react-router'
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import type { ProjectListItem } from '@/types/project'

import ProjectCard from './index'

const baseProject: ProjectListItem = {
  activeTask: null,
  attention: null,
  buildSummary: null,
  createdAt: '2026-08-01T08:00:00+08:00',
  currentBaseline: null,
  id: 'project-1',
  lastActivityAt: null,
  latestBuildStatus: null,
  latestScanAt: null,
  latestTestStatus: null,
  name: 'RISC-V 迁移项目',
  primaryAction: null,
  reportSummary: null,
  riskSummary: null,
  sourceArch: 'x86_64',
  stage: 'PROJECT_CREATED',
  status: 'ACTIVE',
  summaryAvailable: false,
  targetArch: 'riscv64',
  testSummary: null
}

function renderCard(project: ProjectListItem = baseProject) {
  return render(
    <MemoryRouter>
      <ProjectCard project={project} />
    </MemoryRouter>
  )
}

describe('ProjectCard', () => {
  it('refreshes independent card fields without retaining a previous result or navigation target', () => {
    let project = baseProject
    const view = renderCard(project)
    const updates: Array<[Partial<ProjectListItem>, string]> = [
      [{ name: '新项目' }, '新项目'],
      [{ id: 'new-project' }, '新项目'],
      [{ sourceArch: 'riscv64' }, 'RISC-V'],
      [{ targetArch: 'x86_64' }, 'x86_64'],
      [{ latestScanAt: '2026-10-28T00:00:00Z' }, '2026-10-28 08:00'],
      [{ latestBuildStatus: 'SUCCEEDED' }, '成功'],
      [{ latestTestStatus: 'RUNNING' }, '测试中'],
      [{ status: 'ARCHIVED' }, '已归档'],
      [{ stage: 'WAITING_FIX' }, '• 等待修复']
    ]
    for (const [patch, expected] of updates) {
      project = { ...project, ...patch }
      view.rerender(
        <MemoryRouter>
          <ProjectCard project={project} />
        </MemoryRouter>
      )
      expect(screen.getAllByText(expected).length).toBeGreaterThan(0)
      expect(screen.getByRole('link', { name: `${project.name} 项目` })).toHaveAttribute(
        'href',
        `/projects/${project.id}/workflow`
      )
    }
  })
  it('restores the original whole-card project link and hover action', () => {
    renderCard()

    expect(screen.getByRole('link', { name: 'RISC-V 迁移项目 项目' })).toHaveAttribute(
      'href',
      '/projects/project-1/workflow'
    )
    expect(screen.getByText('进入流水线')).toBeInTheDocument()
    expect(screen.getByText('进行中')).toBeInTheDocument()
    expect(screen.queryByText('高风险数')).not.toBeInTheDocument()
    expect(screen.getAllByText('--')).toHaveLength(3)
  })

  it('shows the project status returned by the backend', () => {
    renderCard({ ...baseProject, status: 'ARCHIVED' })

    expect(screen.getByText('已归档')).toBeInTheDocument()
  })

  it('shows the latest scan time, build status, and test status from the project fields', () => {
    renderCard({
      ...baseProject,
      latestBuildStatus: 'SUCCEEDED',
      latestScanAt: '2026-08-30T09:00:00+08:00',
      latestTestStatus: 'FAILED',
      stage: 'WAITING_FIX',
      summaryAvailable: true
    })

    expect(screen.queryByText('高风险数')).not.toBeInTheDocument()
    expect(screen.getByText('• 等待修复')).toBeInTheDocument()
    expect(screen.getByText('2026-08-30 09:00')).toBeInTheDocument()
    expect(screen.getByText('成功')).toBeInTheDocument()
    expect(screen.getByText('失败')).toBeInTheDocument()
  })

  it('replaces card identity and execution status after a list refresh', () => {
    const view = renderCard({
      ...baseProject,
      latestBuildStatus: 'RUNNING',
      latestTestStatus: 'QUEUED'
    })
    expect(screen.getByText('构建中')).toBeInTheDocument()
    expect(screen.getByText('待执行')).toBeInTheDocument()
    view.rerender(
      <MemoryRouter>
        <ProjectCard
          project={{
            ...baseProject,
            id: 'project-2',
            name: '已完成项目',
            status: 'ARCHIVED',
            latestBuildStatus: 'FAILED',
            latestTestStatus: 'SUCCEEDED'
          }}
        />
      </MemoryRouter>
    )
    expect(screen.getByRole('link', { name: '已完成项目 项目' })).toHaveAttribute(
      'href',
      '/projects/project-2/workflow'
    )
    expect(screen.getByText('已归档')).toBeInTheDocument()
    expect(screen.getByText('失败')).toBeInTheDocument()
    expect(screen.getByText('通过')).toBeInTheDocument()
    expect(screen.queryByText('构建中')).not.toBeInTheDocument()
    expect(screen.queryByText('待执行')).not.toBeInTheDocument()
  })

  it.each([
    ['TIMEOUT', '超时'],
    ['CANCELLED', '已取消']
  ] as const)('shows a %s build outcome as %s', (status, label) => {
    renderCard({ ...baseProject, latestBuildStatus: status })
    expect(screen.getByText(label)).toBeInTheDocument()
  })

  it('keeps a pending test distinct from a build that has not started', () => {
    renderCard({ ...baseProject, latestBuildStatus: 'NOT_STARTED', latestTestStatus: 'PENDING' })
    expect(screen.getByText('待执行')).toBeInTheDocument()
    expect(screen.getAllByText('--')).toHaveLength(2)
  })
})
