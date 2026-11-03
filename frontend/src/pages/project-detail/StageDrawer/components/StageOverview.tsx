import { Fragment, type ReactNode, useEffect, useState } from 'react'
import { Alert, Button, Typography } from 'antd'

import ContentState from '@/components/ContentState'
import type { ProjectDomainLatestExecution } from '@/types/project'
import { formatDateTime } from '@/utils/display'

import { isStageActive, resolveOverviewState } from '../stagePresentation'
import StageStateNotice, {
  type StageStateAction,
  type StageStatePrerequisite
} from './StageStateNotice'
import { TaskLogPanel } from './TaskLogPanel'

import styles from './StageOverview.module.scss'

export interface StageOverviewProps {
  label: string
  request: { loading: boolean; error: boolean; hasData: boolean; reload: () => void }
  gate: { ready: boolean; prerequisites: StageStatePrerequisite[]; blockedDescription?: string }
  result: {
    execution?: ProjectDomainLatestExecution | null
    status?: string | null
    freshness?: string
    hasResult: boolean
    record?: {
      taskId: string
      sourceRevisionId?: string
      status: string
      startedAt?: string | null
      finishedAt?: string | null
      summary?: object
      diagnosticCount?: number | null
    } | null
  }
  operation?: {
    submitting?: boolean
    start?: StageStateAction
    runningActions?: StageStateAction[]
  }
  children?: ReactNode
}

function SubmissionNotice({ reload }: { reload: () => void }) {
  const [delayed, setDelayed] = useState(false)
  useEffect(() => {
    const timer = window.setTimeout(() => setDelayed(true), 10_000)
    return () => window.clearTimeout(timer)
  }, [])
  return (
    <StageStateNotice
      title="已提交，正在同步状态"
      description={
        delayed
          ? '任务已提交，状态同步较慢。可以重新加载以确认进展。'
          : '服务端正在同步任务信息，请稍候。'
      }
      variant="processing"
      secondaryActions={[{ label: '重新加载', onClick: reload, type: 'link' }]}
    />
  )
}

export default function StageOverview({
  label,
  request,
  gate,
  result,
  operation,
  children
}: StageOverviewProps) {
  const { ready, prerequisites, blockedDescription } = gate
  const { execution, status = execution?.status, freshness, hasResult, record } = result
  const { submitting, start, runningActions } = operation ?? {}
  const state = resolveOverviewState({
    ...request,
    ready,
    status,
    freshness,
    submitting,
    hasResult
  })
  const action = start && { ...start, disabled: start.disabled || request.error }
  const active = isStageActive(status)
  const [logTaskId, setLogTaskId] = useState<string>()
  const taskId = execution?.taskId ?? record?.taskId
  const logVisible = Boolean(taskId && logTaskId === taskId)
  const task = taskId ? (
    <div className={styles.task}>
      <dl className={styles.metadata}>
        <div>
          <dt>任务编号</dt>
          <dd>
            <Typography.Text type="secondary" copyable={{ text: taskId }}>
              {taskId}
            </Typography.Text>
          </dd>
        </div>
        {record?.sourceRevisionId ? (
          <div>
            <dt>源码版本</dt>
            <dd>
              <Typography.Text type="secondary" copyable={{ text: record.sourceRevisionId }}>
                {record.sourceRevisionId}
              </Typography.Text>
            </dd>
          </div>
        ) : null}
      </dl>
      <Button
        type="link"
        aria-expanded={logVisible}
        onClick={() => setLogTaskId(logVisible ? undefined : taskId)}
      >
        {logVisible ? '收起日志' : '查看日志'}
      </Button>
      {logVisible ? (
        <TaskLogPanel
          className={styles.logs}
          editorClassName=""
          taskId={taskId}
          status={status ?? record?.status ?? ''}
          title="任务日志"
          ariaLabel={`${label}任务日志`}
          onTaskCompleted={request.reload}
        />
      ) : null}
    </div>
  ) : null
  let content: ReactNode
  switch (state) {
    case 'loading':
      content = <ContentState kind="loading" description="正在读取当前状态" />
      break
    case 'request-error':
      content = (
        <StageStateNotice
          title={`${label}加载失败`}
          description="无法读取当前条件或执行结果，请重新加载。"
          variant="request-error"
          primaryAction={{ label: '重新加载', onClick: request.reload }}
        />
      )
      break
    case 'blocked':
      content = (
        <StageStateNotice
          title={`暂不能执行${label}`}
          description={blockedDescription || '请完成下方未满足的前置条件。'}
          prerequisites={prerequisites}
          variant="prerequisite"
          footer={active ? task : undefined}
          details={
            active
              ? `已有${freshness === 'STALE' ? '旧版本' : ''}任务仍在执行，状态以任务记录为准。`
              : undefined
          }
        />
      )
      break
    case 'submitted':
      content = <SubmissionNotice reload={request.reload} />
      break
    case 'running':
      content = (
        <StageStateNotice
          title={`${label}${status === 'RUNNING' ? '正在执行' : '等待执行'}`}
          description={execution?.message || '任务正在后台执行，完成后将展示结果。'}
          details={
            freshness === 'STALE'
              ? '此任务属于旧版本，不代表当前版本的结果。'
              : `创建时间：${formatDateTime(execution?.createdAt)}`
          }
          variant="processing"
          secondaryActions={runningActions}
          footer={task}
        />
      )
      break
    case 'ready':
      content = (
        <StageStateNotice
          title={`已满足${label}条件`}
          description={
            freshness === 'STALE'
              ? '当前版本暂无有效结果，旧结果可从历史记录查看。'
              : '当前尚无执行结果，可以开始执行。'
          }
          prerequisites={prerequisites}
          primaryAction={action}
          variant="ready"
        />
      )
      break
    case 'failed':
      content = (
        <StageStateNotice
          title={`${label}${status === 'CANCELLED' ? '已取消' : status === 'TIMEOUT' ? '已超时' : '执行失败'}`}
          description={
            execution?.errorMessage ||
            (record?.summary &&
            'error' in record.summary &&
            typeof record.summary.error === 'string'
              ? record.summary.error
              : undefined) ||
            '本次执行未完成，请查看详情或任务日志。'
          }
          details={
            <Fragment>
              <span>完成时间：{formatDateTime(execution?.finishedAt ?? record?.finishedAt)}</span>
              {record?.diagnosticCount ? (
                <span>{record.diagnosticCount} 项分析诊断，请查看日志。</span>
              ) : null}
            </Fragment>
          }
          primaryAction={action && { ...action, label: `重新执行${label}` }}
          variant="execution-error"
          footer={task}
        />
      )
      break
    case 'syncing':
      content = (
        <StageStateNotice
          title="正在读取执行结果"
          description="任务状态已更新，结果内容尚未返回。"
          variant="processing"
          secondaryActions={[{ label: '重新加载', onClick: request.reload, type: 'link' }]}
        />
      )
      break
    default:
      content = children
  }
  return (
    <Fragment>
      {request.error && request.hasData ? (
        <Alert
          showIcon
          type="warning"
          title="刷新失败，正在显示上次读取的内容；暂不能新执行。"
          action={
            <Button type="link" onClick={request.reload}>
              重新加载
            </Button>
          }
        />
      ) : null}
      {content}
    </Fragment>
  )
}
