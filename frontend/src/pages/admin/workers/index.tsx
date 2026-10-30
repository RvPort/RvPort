import { useRef, useState } from 'react'
import { EyeOutlined, ReloadOutlined, SettingOutlined } from '@ant-design/icons'
import { useQueryClient } from '@tanstack/react-query'
import { Button, Flex, Tag, Tooltip } from 'antd'
import type { ColumnsType } from 'antd/es/table'

import { getAdminWorkers, updateAdminWorkerConcurrency } from '@/api/admin/workers'
import type { BaseTableHandle } from '@/components/BaseTable'
import BaseTable from '@/components/BaseTable'
import { useSessionMutation } from '@/hooks/useSessionMutation'
import type {
  AdminWorkerListParams,
  WorkerInstance,
  WorkerInstanceStatus,
  WorkerSortField
} from '@/types/admin/workers'
import { formatDateTime } from '@/utils/display'
import { feedback } from '@/utils/feedback'

import { adminQueryKeys } from '../queryKeys'
import ConcurrencyModal from './components/ConcurrencyModal'
import WorkerDetailDrawer from './components/WorkerDetailDrawer'

import styles from './index.module.scss'
import tableButtons from '@/styles/tableButtons.module.scss'

interface WorkerFilters {
  hasRunningTasks?: 'true' | 'false'
  hostname?: string
  instanceId?: string
  status?: WorkerInstanceStatus
}

const statusLabels: Record<WorkerInstanceStatus, string> = {
  ONLINE: '在线',
  OFFLINE: '离线'
}

export default function AdminWorkersPage() {
  const queryClient = useQueryClient()
  const tableRef = useRef<BaseTableHandle>(null)
  const [detailWorkerId, setDetailWorkerId] = useState<string | null>(null)
  const [configuredWorker, setConfiguredWorker] = useState<WorkerInstance | null>(null)
  const updateConcurrencyMutation = useSessionMutation({
    sessionKey: configuredWorker?.instanceId ?? 'worker',
    active: Boolean(configuredWorker),
    mutationFn: ({
      concurrencyLimit,
      workerInstanceId
    }: {
      concurrencyLimit: number
      workerInstanceId: string
    }) => updateAdminWorkerConcurrency(workerInstanceId, concurrencyLimit),
    onSuccess: async (worker) => {
      feedback.success(
        worker.concurrencyLimit === 0 ? 'Worker 已暂停接收新任务' : 'Worker 并发已更新'
      )
      setConfiguredWorker(null)
      await Promise.all([
        tableRef.current?.reload(),
        queryClient.invalidateQueries({ queryKey: adminQueryKeys.worker(worker.instanceId) })
      ])
    }
  })
  const columns: ColumnsType<WorkerInstance> = [
    {
      dataIndex: 'instanceId',
      title: '实例 ID',
      width: 320,
      sorter: true,
      render: (value: string) => (
        <Button
          className={`${tableButtons.textButton} ${styles.instanceButton}`}
          type="link"
          onClick={() => setDetailWorkerId(value)}
        >
          {value}
        </Button>
      )
    },
    { dataIndex: 'hostname', title: '主机名', width: 180, sorter: true },
    {
      dataIndex: 'status',
      title: '状态',
      width: 100,
      sorter: true,
      render: (status: WorkerInstanceStatus, worker) => (
        <Flex gap={4} wrap>
          <Tag color={status === 'ONLINE' ? 'success' : 'default'}>{statusLabels[status]}</Tag>
          {worker.concurrencyLimit === 0 ? <Tag color="warning">已暂停接单</Tag> : null}
        </Flex>
      )
    },
    {
      dataIndex: 'concurrencyLimit',
      title: '期望并发',
      width: 100,
      align: 'right',
      sorter: true
    },
    {
      dataIndex: 'activeConcurrency',
      title: '实际并发',
      width: 100,
      align: 'right',
      sorter: true
    },
    {
      dataIndex: 'activeTaskCount',
      title: '运行任务',
      width: 100,
      align: 'right',
      sorter: true
    },
    { dataIndex: 'appVersion', title: '版本', width: 140 },
    {
      dataIndex: 'lastHeartbeatAt',
      title: '最近心跳',
      width: 170,
      sorter: true,
      render: formatDateTime
    },
    {
      key: 'actions',
      title: '操作',
      fixed: 'right',
      width: 104,
      render: (_, worker) => (
        <Flex gap={4}>
          <Tooltip title="查看 Worker 详情">
            <Button
              aria-label="查看 Worker 详情"
              icon={<EyeOutlined />}
              size="small"
              type="text"
              onClick={() => setDetailWorkerId(worker.instanceId)}
            />
          </Tooltip>
          <Tooltip title="设置 Worker 并发">
            <Button
              aria-label="设置 Worker 并发"
              icon={<SettingOutlined />}
              size="small"
              type="text"
              onClick={() => setConfiguredWorker(worker)}
            />
          </Tooltip>
        </Flex>
      )
    }
  ]

  return (
    <section className={styles.page}>
      <header className={styles.pageHeader}>
        <div>
          <h2>Worker 管理</h2>
          <p>查看自动接入的 Worker、当前任务负载，并设置单实例任务消费并发。</p>
        </div>
      </header>
      <div className={styles.tableArea}>
        <BaseTable<WorkerInstance, WorkerFilters, WorkerSortField>
          ref={tableRef}
          className={styles.table}
          columns={columns}
          queryKey={adminQueryKeys.workers()}
          request={(query) => {
            const params: AdminWorkerListParams = {
              hasRunningTasks:
                query.hasRunningTasks === undefined ? undefined : query.hasRunningTasks === 'true',
              hostname: query.hostname,
              instanceId: query.instanceId,
              page: query.page,
              pageSize: query.pageSize,
              sortBy: query.sortBy,
              sortDirection:
                query.sortOrder === 'ascend'
                  ? 'asc'
                  : query.sortOrder === 'descend'
                    ? 'desc'
                    : undefined,
              status: query.status
            }
            return getAdminWorkers(params)
          }}
          rowKey="instanceId"
          scroll={{ x: 1340 }}
          toolbar={{
            actions: (
              <Button icon={<ReloadOutlined />} onClick={() => void tableRef.current?.reload()}>
                刷新
              </Button>
            ),
            filters: {
              fields: [
                { name: 'instanceId', placeholder: '搜索实例 ID', type: 'search' },
                { name: 'hostname', placeholder: '搜索主机名', type: 'input' },
                {
                  name: 'status',
                  options: [
                    { label: '在线', value: 'ONLINE' },
                    { label: '离线', value: 'OFFLINE' }
                  ],
                  placeholder: '状态',
                  type: 'select'
                },
                {
                  name: 'hasRunningTasks',
                  options: [
                    { label: '有运行任务', value: 'true' },
                    { label: '无运行任务', value: 'false' }
                  ],
                  placeholder: '任务负载',
                  type: 'select'
                }
              ]
            }
          }}
        />
      </div>
      <WorkerDetailDrawer
        workerInstanceId={detailWorkerId}
        onClose={() => setDetailWorkerId(null)}
        onConfigure={setConfiguredWorker}
      />
      <ConcurrencyModal
        loading={updateConcurrencyMutation.isPending}
        visible={Boolean(configuredWorker)}
        worker={configuredWorker}
        onCancel={() => setConfiguredWorker(null)}
        onSubmit={async (concurrencyLimit) => {
          if (!configuredWorker) return
          await updateConcurrencyMutation.mutateAsync({
            concurrencyLimit,
            workerInstanceId: configuredWorker.instanceId
          })
        }}
      />
    </section>
  )
}
