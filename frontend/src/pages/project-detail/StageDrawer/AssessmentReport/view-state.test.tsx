import { fireEvent, screen } from '@testing-library/react'
import type { ComponentProps, ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'

import {
  createEvaluationReport,
  createLatestReport,
  createReportReadiness
} from '@/test/evaluation-report-fixtures'
import { renderWithQueryClient } from '@/test/test-utils'
import type {
  ProjectEvaluationReportCodeFile,
  ProjectEvaluationReportCodeModule
} from '@/types/project'

import Report from '.'
import { fileFixture, moduleFixture } from './components/report-list-fixtures'

const hook = vi.hoisted(() => ({ useAssessmentReportDrawer: vi.fn() }))
vi.mock('./useAssessmentReportDrawer', () => hook)
vi.mock('../components/StageSurface', () => ({
  default: ({
    children,
    title,
    footer,
    extra,
    onHistory,
    onClose,
    afterOpenChange
  }: {
    children: ReactNode
    title: ReactNode
    footer: ReactNode
    extra: ReactNode
    onHistory?: () => void
    onClose: () => void
    afterOpenChange: (open: boolean) => void
  }) => (
    <div>
      {title}
      {extra}
      {children}
      {footer}
      {onHistory && <button onClick={onHistory}>历史入口</button>}
      <button onClick={onClose}>关闭</button>
      <button onClick={() => afterOpenChange(false)}>关闭结束</button>
    </div>
  )
}))
vi.mock('./components/content', () => ({
  AssessmentReportResult: ({
    report,
    selectedModule,
    selectedFile,
    onSelectModule,
    onSelectFile,
    onOpenIssue,
    onDownload,
    onCreate,
    canGenerate
  }: {
    report: { id: string }
    selectedModule?: ProjectEvaluationReportCodeModule
    selectedFile?: ProjectEvaluationReportCodeFile
    onSelectModule: (module: ProjectEvaluationReportCodeModule) => void
    onSelectFile: (file: ProjectEvaluationReportCodeFile) => void
    onOpenIssue: (id: string) => void
    onDownload: (format: string) => void
    onCreate: () => void
    canGenerate: boolean
  }) => (
    <div>
      结果 {report.id} 模块 {selectedModule?.moduleKey ?? 'none'} 文件{' '}
      {selectedFile?.fileKey ?? 'none'} 可生成 {String(canGenerate)}
      <button onClick={() => onSelectModule({ ...moduleFixture, moduleName: '' })}>选择模块</button>
      <button onClick={() => onSelectFile({ ...fileFixture, relativePath: '' })}>选择文件</button>
      <button onClick={() => onOpenIssue('input-1')}>打开问题</button>
      <button onClick={() => onDownload('JSON')}>下载快照</button>
      {canGenerate && <button onClick={onCreate}>重建报告</button>}
    </div>
  )
}))
vi.mock('./components/ReportHistory', () => ({
  default: ({ onOpen }: { onOpen: (id: string) => void }) => (
    <button onClick={() => onOpen('old-report')}>选择旧报告</button>
  )
}))
vi.mock('./components/ReportIssueDetail', () => ({
  default: ({ migrationInputId }: { migrationInputId: string }) => (
    <div>问题引用 {migrationInputId}</div>
  )
}))
vi.mock('../components/TaskLogPanel', () => ({
  TaskLogPanel: ({ taskId }: { taskId: string }) => <div>只读报告日志 {taskId}</div>
}))

function fixture() {
  const readiness = createReportReadiness()
  return {
    report: createEvaluationReport(),
    canGenerate: true,
    isCreating: false,
    submitted: false,
    isLoading: false,
    isError: false,
    latestFreshness: 'CURRENT',
    generationPrerequisites: readiness.prerequisites,
    readinessQuery: { data: readiness },
    latestQuery: { data: createLatestReport() },
    selected: { isLoading: false, isError: false, refetch: vi.fn() },
    refetch: vi.fn(),
    handleCreate: vi.fn(),
    handleDownload: vi.fn()
  }
}

describe('evaluation report view coordination', () => {
  it('keeps file and issue navigation within the selected report and applies panel footer rules', async () => {
    const model = fixture()
    hook.useAssessmentReportDrawer.mockReturnValue(model)
    let props: ComponentProps<typeof Report> = {
      visible: true,
      projectId: 'project-1',
      onClose: vi.fn(),
      onStageChange: vi.fn()
    }
    const view = renderWithQueryClient(<Report {...props} />)
    fireEvent.click(screen.getByRole('button', { name: '下载快照' }))
    fireEvent.click(screen.getByRole('button', { name: '重建报告' }))
    expect(model.handleDownload).toHaveBeenCalledWith('JSON')
    expect(model.handleCreate).toHaveBeenCalledOnce()
    fireEvent.click(screen.getByRole('button', { name: '选择模块' }))
    expect(hook.useAssessmentReportDrawer).toHaveBeenLastCalledWith('project-1', false, undefined)
    expect(screen.getByText('模块详情')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '选择文件' }))
    expect(screen.getByText('文件详情')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '返回 模块详情' }))
    fireEvent.click(screen.getByRole('button', { name: '返回报告总览' }))
    fireEvent.click(screen.getByRole('button', { name: '打开问题' }))
    expect(await screen.findByText('问题引用 input-1')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '返回评估报告' }))
    props = { ...props, onStageChange: vi.fn() }
    view.rerender(<Report {...props} />)
    fireEvent.click(screen.getByRole('button', { name: '进入人工修复' }))
    expect(props.onStageChange).toHaveBeenCalledWith('manual')
    props = { ...props, presentation: 'panel' }
    view.rerender(<Report {...props} />)
    expect(screen.queryByRole('button', { name: '进入人工修复' })).not.toBeInTheDocument()
  })

  it('retries historical requests and keeps unfinished historical records read-only, then resets on close', () => {
    let model = fixture()
    hook.useAssessmentReportDrawer.mockImplementation(() => model)
    const close = vi.fn()
    const view = renderWithQueryClient(<Report visible projectId="project-1" onClose={close} />)
    fireEvent.click(screen.getByRole('button', { name: '历史入口' }))
    fireEvent.click(screen.getByRole('button', { name: '选择旧报告' }))
    expect(hook.useAssessmentReportDrawer).toHaveBeenLastCalledWith(
      'project-1',
      false,
      'old-report'
    )
    model = { ...model, selected: { ...model.selected, isLoading: true } }
    view.rerender(<Report visible projectId="project-1" onClose={close} />)
    expect(screen.getByText('正在读取报告记录')).toBeInTheDocument()
    model = { ...model, selected: { ...model.selected, isLoading: false, isError: true } }
    view.rerender(<Report visible projectId="project-1" onClose={close} />)
    fireEvent.click(screen.getByRole('button', { name: '重新加载' }))
    expect(model.selected.refetch).toHaveBeenCalledOnce()
    model = {
      ...model,
      selected: { ...model.selected, isError: false },
      report: createEvaluationReport({ id: 'old-report', taskId: 'old-task', status: 'RUNNING' })
    }
    view.rerender(<Report visible projectId="project-1" onClose={close} />)
    expect(screen.getByText('只读报告日志 old-task')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '重建报告' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '关闭' }))
    expect(close).toHaveBeenCalledOnce()
    expect(hook.useAssessmentReportDrawer).toHaveBeenLastCalledWith('project-1', false, undefined)
    model = { ...model, report: createEvaluationReport() }
    fireEvent.click(screen.getByRole('button', { name: '关闭结束' }))
    expect(hook.useAssessmentReportDrawer).toHaveBeenLastCalledWith('project-1', true, undefined)
    expect(screen.getByRole('button', { name: '重建报告' })).toBeInTheDocument()
  })
})
