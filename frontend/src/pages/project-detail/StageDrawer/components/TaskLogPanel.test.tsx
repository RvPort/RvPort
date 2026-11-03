import { fireEvent, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { getProjectTaskLogDownloadUrl } from '@/api/project/build-validation'
import { renderWithQueryClient } from '@/test/test-utils'

const { getProjectTaskLogContent, retry, useTaskLogStream } = vi.hoisted(() => ({
  getProjectTaskLogContent: vi.fn(),
  retry: vi.fn(),
  useTaskLogStream: vi.fn()
}))

vi.mock('@/api/project/build-validation', () => ({
  getProjectTaskLogContent,
  getProjectTaskLogDownloadUrl: vi.fn(() => '/download')
}))
vi.mock('../hooks/useTaskLogStream', () => ({ useTaskLogStream }))
vi.mock('@/components/MonacoEditor', () => ({
  default: ({
    ariaLabel,
    value,
    hasEarlierContent,
    onLoadEarlierContent,
    lineNumberOffset
  }: {
    ariaLabel: string
    value: string
    hasEarlierContent?: boolean
    onLoadEarlierContent?: () => void
    lineNumberOffset?: number
  }) => (
    <div>
      <pre aria-label={ariaLabel} data-offset={lineNumberOffset}>
        {value}
      </pre>
      {hasEarlierContent && <button onClick={onLoadEarlierContent}>读取更早日志</button>}
    </div>
  )
}))

import { TaskLogPanel } from './TaskLogPanel'

function liveResult(overrides: Record<string, unknown> = {}) {
  return {
    buffers: { merged: '', stdout: '', stderr: '' },
    completed: false,
    connectionStatus: 'connected',
    cursor: 0,
    error: null,
    isFetching: false,
    retry,
    truncated: false,
    ...overrides
  }
}

describe('TaskLogPanel', () => {
  it('loads earlier authoritative pages and binds downloads and tab requests to the selected task', async () => {
    getProjectTaskLogContent.mockImplementation(async (_id, params) => ({
      logs: {
        [params.type]: {
          type: params.type,
          logId: `log-${params.type}`,
          offset: params.offset,
          lines: 100,
          returnedLines: 2,
          totalLines: 4,
          hasMore: params.offset === 0,
          content: params.offset === 0 ? 'new-1\nnew-2' : 'old-1\nold-2'
        }
      }
    }))
    const open = vi.spyOn(window, 'open').mockImplementation(() => null)
    const props = {
      ariaLabel: '审计日志',
      title: '审计日志',
      className: 'panel',
      editorClassName: 'editor',
      taskId: 'task-1',
      status: 'SUCCEEDED'
    }
    const view = renderWithQueryClient(<TaskLogPanel {...props} />)
    await waitFor(() => expect(screen.getByLabelText('审计日志')).toHaveTextContent('new-1'))
    expect(screen.getByLabelText('审计日志')).toHaveAttribute('data-offset', '3')
    fireEvent.click(screen.getByRole('button', { name: '读取更早日志' }))
    await waitFor(() =>
      expect(screen.getByLabelText('审计日志').textContent).toBe('old-1\nold-2\nnew-1\nnew-2')
    )
    expect(screen.queryByRole('button', { name: '读取更早日志' })).not.toBeInTheDocument()
    expect(getProjectTaskLogContent).toHaveBeenLastCalledWith('task-1', {
      lines: 100,
      offset: 2,
      type: 'merged'
    })
    fireEvent.click(screen.getByRole('button', { name: '下载完整审计日志' }))
    expect(getProjectTaskLogDownloadUrl).toHaveBeenLastCalledWith('task-1', 'log-merged')
    expect(open).toHaveBeenCalledWith('/download', '_blank', 'noopener,noreferrer')
    await userEvent.click(screen.getByRole('tab', { name: '标准输出' }))
    await waitFor(() =>
      expect(getProjectTaskLogContent).toHaveBeenLastCalledWith('task-1', {
        lines: 100,
        offset: 0,
        type: 'stdout'
      })
    )
    view.rerender(<TaskLogPanel {...props} taskId="task-2" title="新日志" />)
    await waitFor(() =>
      expect(screen.getByRole('button', { name: '下载完整新日志' })).toBeInTheDocument()
    )
    fireEvent.click(screen.getByRole('button', { name: '下载完整新日志' }))
    expect(getProjectTaskLogDownloadUrl).toHaveBeenLastCalledWith('task-2', 'log-stdout')
    open.mockRestore()
  })

  it.each([false, true])(
    'recovers a final-log read error while preserving liveContent=%s',
    async (hasLive) => {
      useTaskLogStream.mockReturnValue(
        liveResult({
          completed: true,
          buffers: { merged: hasLive ? 'live evidence' : '', stdout: '', stderr: '' }
        })
      )
      getProjectTaskLogContent.mockRejectedValueOnce(new Error('offline')).mockResolvedValue({
        logs: {
          merged: {
            logId: 'final',
            hasMore: false,
            offset: 0,
            returnedLines: 1,
            totalLines: 1,
            content: 'final evidence'
          }
        }
      })
      renderWithQueryClient(
        <TaskLogPanel
          ariaLabel="执行日志"
          title="执行日志"
          className=""
          editorClassName=""
          taskId="task-1"
          status="RUNNING"
        />
      )
      expect(
        await screen.findByText(
          hasLive ? '完整日志加载失败，下方保留的是实时日志，内容可能不完整。' : '执行日志加载失败'
        )
      ).toBeInTheDocument()
      if (hasLive) expect(screen.getByLabelText('执行日志')).toHaveTextContent('live evidence')
      await userEvent.click(screen.getByRole('button', { name: /重\s*试/ }))
      await waitFor(() =>
        expect(screen.getByLabelText('执行日志')).toHaveTextContent('final evidence')
      )
    }
  )

  it('shows connection, truncation and empty-output states without applying merged fallback to stderr', async () => {
    useTaskLogStream.mockReturnValue(
      liveResult({ connectionStatus: 'connecting', isFetching: true })
    )
    const props = {
      ariaLabel: '实时日志',
      title: '实时日志',
      className: '',
      editorClassName: '',
      taskId: 'task-1',
      status: 'RUNNING',
      fallbackContent: '  结果摘要  '
    }
    const view = renderWithQueryClient(<TaskLogPanel {...props} />)
    expect(screen.getByText('正在连接实时日志')).toBeInTheDocument()
    expect(screen.getByText('实时日志加载中')).toBeInTheDocument()
    useTaskLogStream.mockReturnValue(
      liveResult({ connectionStatus: 'reconnecting', truncated: true })
    )
    view.rerender(<TaskLogPanel {...props} />)
    expect(screen.getByText('实时日志连接中断，正在重连')).toBeInTheDocument()
    expect(screen.getByText(/部分较早实时日志已省略/)).toBeInTheDocument()
    expect(screen.getByLabelText('实时日志')).toHaveTextContent('结果摘要')
    await userEvent.click(screen.getByRole('tab', { name: '标准错误' }))
    expect(screen.getByText('等待任务输出日志')).toBeInTheDocument()
    getProjectTaskLogContent.mockResolvedValue({ logs: {} })
    view.rerender(<TaskLogPanel {...props} status="SUCCEEDED" />)
    expect(await screen.findByText('暂未生成此类日志')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '下载完整实时日志' })).not.toBeInTheDocument()
  })
  beforeEach(() => {
    vi.clearAllMocks()
    useTaskLogStream.mockReturnValue(liveResult())
  })

  it('uses local increment buffers while the task is running', async () => {
    useTaskLogStream.mockReturnValue(
      liveResult({
        buffers: {
          merged: '[STDOUT] build\n[STDERR] warning\n',
          stdout: 'build\n',
          stderr: 'warning\n'
        },
        cursor: 8
      })
    )

    renderWithQueryClient(
      <TaskLogPanel
        ariaLabel="构建日志"
        className="panel"
        editorClassName="editor"
        status="RUNNING"
        taskId="task-1"
        title="构建日志"
      />
    )

    expect(screen.getByLabelText('构建日志')).toHaveTextContent('[STDOUT] build')
    await userEvent.click(screen.getByRole('tab', { name: '标准错误' }))
    expect(screen.getByLabelText('构建日志')).toHaveTextContent('warning')
    expect(getProjectTaskLogContent).not.toHaveBeenCalled()
    expect(screen.queryByRole('button', { name: '刷新构建日志' })).not.toBeInTheDocument()
    expect(useTaskLogStream).toHaveBeenCalledWith(
      expect.objectContaining({ enabled: true, taskId: 'task-1' })
    )
  })

  it('loads authoritative paged content directly for a terminal task', async () => {
    getProjectTaskLogContent.mockResolvedValue({
      logs: {
        merged: {
          type: 'merged',
          logId: 'log-1',
          offset: 0,
          lines: 100,
          returnedLines: 1,
          totalLines: 1,
          hasMore: false,
          content: 'finished'
        }
      }
    })

    renderWithQueryClient(
      <TaskLogPanel
        ariaLabel="构建日志"
        className="panel"
        editorClassName="editor"
        status="SUCCEEDED"
        taskId="task-1"
        title="构建日志"
      />
    )

    await waitFor(() => expect(screen.getByLabelText('构建日志')).toHaveTextContent('finished'))
    expect(getProjectTaskLogContent).toHaveBeenCalledWith('task-1', {
      lines: 100,
      offset: 0,
      type: 'merged'
    })
    expect(useTaskLogStream).toHaveBeenCalledWith(
      expect.objectContaining({ enabled: false, taskId: 'task-1' })
    )
    expect(screen.getByRole('button', { name: '下载完整构建日志' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '刷新构建日志' })).toBeInTheDocument()
  })

  it('keeps existing output visible and exposes retry after a live read error', async () => {
    useTaskLogStream.mockReturnValue(
      liveResult({
        buffers: { merged: 'kept output', stdout: 'kept output', stderr: '' },
        error: new Error('offline')
      })
    )

    renderWithQueryClient(
      <TaskLogPanel
        ariaLabel="测试日志"
        className="panel"
        editorClassName="editor"
        status="RUNNING"
        taskId="task-1"
        title="测试日志"
      />
    )

    expect(screen.getByLabelText('测试日志')).toHaveTextContent('kept output')
    await userEvent.click(screen.getByRole('button', { name: '立即重试' }))
    expect(retry).toHaveBeenCalledOnce()
  })
})
