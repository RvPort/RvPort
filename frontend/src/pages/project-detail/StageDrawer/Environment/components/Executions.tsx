import { Button, Flex, Tag } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import dayjs from 'dayjs'

import { getProjectEnvironmentScriptExecutions } from '@/api/project/environments'
import BaseTable from '@/components/BaseTable'
import ShortIdentifier from '@/components/ShortIdentifier'
import type { ProjectEnvironment, ProjectEnvironmentScriptExecution } from '@/types/project'
import { formatDateTime } from '@/utils/display'

import { projectQueryKeys } from '../../../queryKeys'
import { environmentScriptStatusConfig } from '../constants'

import styles from '../index.module.scss'
import tableButtons from '@/styles/tableButtons.module.scss'

interface ExecutionsProps {
  environment: ProjectEnvironment
  onViewLog: (execution: ProjectEnvironmentScriptExecution) => void
  projectId: string
}

type ExecutionFilters = Record<string, never>

function formatDuration(startedAt: string | null, finishedAt: string | null) {
  if (!startedAt) return '等待执行'
  if (!finishedAt) return '执行中'

  const durationSeconds = Math.max(0, dayjs(finishedAt).diff(dayjs(startedAt), 'second'))
  if (durationSeconds < 60) return `${durationSeconds} 秒`

  const minutes = Math.floor(durationSeconds / 60)
  const seconds = durationSeconds % 60
  return seconds ? `${minutes} 分 ${seconds} 秒` : `${minutes} 分`
}

export default function Executions({ environment, onViewLog, projectId }: ExecutionsProps) {
  const columns: ColumnsType<ProjectEnvironmentScriptExecution> = [
    {
      title: '执行 ID',
      width: 168,
      render: (_, execution) => <ShortIdentifier value={execution.id} />
    },
    {
      dataIndex: 'status',
      title: '状态',
      width: 112,
      render: (status: ProjectEnvironmentScriptExecution['status'], execution) => {
        const config = environmentScriptStatusConfig[status]
        return (
          <Flex align="flex-start" vertical gap={4}>
            <Tag color={config.color}>{config.text}</Tag>
            {status === 'RUNNING' ? <span>{execution.progress ?? 0}%</span> : null}
          </Flex>
        )
      }
    },
    {
      dataIndex: 'message',
      title: '执行摘要',
      width: 288,
      render: (value: string | null) => (
        <span className={styles.executionMessage}>{value ?? '--'}</span>
      )
    },
    {
      dataIndex: 'exitCode',
      title: '退出码',
      width: 88,
      render: (value: number | null) => value ?? '--'
    },
    {
      dataIndex: 'logEventCount',
      title: '日志',
      width: 80,
      render: (value: number) => `${value} 条`
    },
    {
      dataIndex: 'submittedAt',
      title: '提交时间',
      width: 168,
      render: (value: string) => formatDateTime(value)
    },
    {
      key: 'duration',
      title: '耗时',
      width: 104,
      render: (_, execution) => formatDuration(execution.startedAt, execution.finishedAt)
    },
    {
      key: 'actions',
      title: '操作',
      fixed: 'right',
      width: 104,
      render: (_, execution) => (
        <Button
          className={tableButtons.textButton}
          size="small"
          type="link"
          onClick={() => onViewLog(execution)}
        >
          查看日志
        </Button>
      )
    }
  ]

  return (
    <section className={styles.executions}>
      <h3>{environment.name} 的执行记录</h3>
      <BaseTable<ProjectEnvironmentScriptExecution, ExecutionFilters>
        columns={columns}
        emptyDescription="暂无执行记录"
        emptyPresentation="standalone"
        errorDescription="执行记录加载失败"
        pagination={{ initialPageSize: 20 }}
        queryKey={projectQueryKeys.environmentScriptExecutions(projectId, environment.id)}
        request={(query) =>
          getProjectEnvironmentScriptExecutions(projectId, {
            environmentId: environment.id,
            page: query.page,
            pageSize: query.pageSize
          })
        }
        rowKey="id"
        scroll={{ x: 1120 }}
        size="small"
        tableLayout="fixed"
      />
    </section>
  )
}
