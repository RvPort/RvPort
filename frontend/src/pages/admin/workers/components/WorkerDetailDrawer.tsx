import { ReloadOutlined, SettingOutlined } from '@ant-design/icons'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Button, Descriptions, Flex, Popconfirm, Progress, Table, Tag } from 'antd'
import type { ColumnsType } from 'antd/es/table'

import { cancelAdminWorkerTask, getAdminWorker } from '@/api/admin/workers'
import BaseDrawer from '@/components/BaseDrawer'
import ContentState from '@/components/ContentState'
import { useSessionMutation } from '@/hooks/useSessionMutation'
import type { WorkerInstance, WorkerTask } from '@/types/admin/workers'
import { formatDateTime, getTaskStatusPresentation } from '@/utils/display'
import { feedback } from '@/utils/feedback'

import { adminQueryKeys } from '../../queryKeys'

import styles from '../index.module.scss'
import tableButtons from '@/styles/tableButtons.module.scss'

interface WorkerDetailDrawerProps {
  workerInstanceId: string | null
  onClose: () => void
  onConfigure: (worker: WorkerInstance) => void
}

export default function WorkerDetailDrawer({
  workerInstanceId,
  onClose,
  onConfigure
}: WorkerDetailDrawerProps) {
  const queryClient = useQueryClient()
  const detailQuery = useQuery({
    queryKey: adminQueryKeys.worker(workerInstanceId),
    queryFn: () => getAdminWorker(workerInstanceId ?? ''),
    enabled: Boolean(workerInstanceId),
    staleTime: 0
  })
  const cancelTaskMutation = useSessionMutation({
    sessionKey: `worker-task:${workerInstanceId}`,
    active: Boolean(workerInstanceId),
    mutationFn: cancelAdminWorkerTask,
    onSuccess: async () => {
      feedback.success('任务取消请求已提交')
      await Promise.all([
        detailQuery.refetch(),
        queryClient.invalidateQueries({ queryKey: adminQueryKeys.workers() })
      ])
    }
  })
  const detail = detailQuery.data
  const worker = detail?.worker
  const taskColumns: ColumnsType<WorkerTask> = [
    {
      dataIndex: 'taskId',
      title: '任务 ID',
      width: 260,
      render: (value: string) => <span className={styles.fullValue}>{value}</span>
    },
    { dataIndex: 'projectName', title: '项目', width: 180 },
    { dataIndex: 'type', title: '任务类型', width: 190 },
    {
      dataIndex: 'status',
      title: '状态',
      width: 100,
      render: (status: WorkerTask['status']) => {
        const presentation = getTaskStatusPresentation(status)
        return <Tag color={presentation.color}>{presentation.label}</Tag>
      }
    },
    {
      dataIndex: 'progress',
      title: '进度',
      width: 140,
      render: (value: number) => <Progress percent={value} size="small" />
    },
    { dataIndex: 'startedAt', title: '开始时间', width: 170, render: formatDateTime },
    {
      dataIndex: 'message',
      title: '当前信息',
      width: 260,
      render: (value: string | null) => value || '--'
    },
    {
      key: 'actions',
      title: '操作',
      fixed: 'right',
      width: 88,
      render: (_, task) => (
        <Popconfirm
          cancelText="暂不取消"
          description="取消后，Worker 将停止执行该任务。"
          okButtonProps={{ danger: true }}
          okText="确认取消"
          title="确认取消该任务？"
          onConfirm={() => cancelTaskMutation.mutate(task.taskId)}
        >
          <Button
            className={tableButtons.textButton}
            danger
            loading={cancelTaskMutation.isPending && cancelTaskMutation.variables === task.taskId}
            disabled={cancelTaskMutation.isPending && cancelTaskMutation.variables !== task.taskId}
            size="small"
            type="link"
          >
            取消
          </Button>
        </Popconfirm>
      )
    }
  ]

  return (
    <BaseDrawer
      extra={
        <Button
          aria-label="刷新 Worker 详情"
          icon={<ReloadOutlined />}
          loading={detailQuery.isFetching}
          type="text"
          onClick={() => void detailQuery.refetch()}
        />
      }
      footer={
        worker ? (
          <Button icon={<SettingOutlined />} type="primary" onClick={() => onConfigure(worker)}>
            设置并发
          </Button>
        ) : undefined
      }
      size={880}
      title="Worker 详情"
      visible={Boolean(workerInstanceId)}
      onClose={onClose}
    >
      {detailQuery.isLoading ? (
        <div className={styles.drawerState}>
          <ContentState kind="loading" description="正在加载 Worker 详情" compact />
        </div>
      ) : null}
      {detailQuery.isError ? (
        <div className={styles.drawerState}>
          <ContentState kind="error" description="Worker 详情加载失败">
            <Button type="primary" onClick={() => void detailQuery.refetch()}>
              重试
            </Button>
          </ContentState>
        </div>
      ) : null}
      {worker ? (
        <div className={styles.drawerContent}>
          <section>
            <h3>实例信息</h3>
            <Descriptions
              bordered
              column={1}
              items={[
                {
                  key: 'instanceId',
                  label: '实例 ID',
                  children: <span className={styles.fullValue}>{worker.instanceId}</span>
                },
                { key: 'hostname', label: '主机名', children: worker.hostname },
                { key: 'processId', label: '进程 ID', children: worker.processId },
                { key: 'appVersion', label: '应用版本', children: worker.appVersion },
                {
                  key: 'status',
                  label: '状态',
                  children: (
                    <Flex gap={6} wrap>
                      <Tag color={worker.status === 'ONLINE' ? 'success' : 'default'}>
                        {worker.status === 'ONLINE' ? '在线' : '离线'}
                      </Tag>
                      {worker.concurrencyLimit === 0 ? <Tag color="warning">已暂停接单</Tag> : null}
                    </Flex>
                  )
                },
                {
                  key: 'concurrency',
                  label: '消费并发',
                  children: `期望 ${worker.concurrencyLimit} / 实际 ${worker.activeConcurrency}`
                },
                { key: 'activeTaskCount', label: '运行任务数', children: worker.activeTaskCount },
                { key: 'startedAt', label: '启动时间', children: formatDateTime(worker.startedAt) },
                {
                  key: 'lastHeartbeatAt',
                  label: '最近心跳',
                  children: formatDateTime(worker.lastHeartbeatAt)
                },
                { key: 'createdAt', label: '注册时间', children: formatDateTime(worker.createdAt) },
                { key: 'updatedAt', label: '更新时间', children: formatDateTime(worker.updatedAt) }
              ]}
              size="small"
            />
          </section>
          <section>
            <h3>当前运行任务</h3>
            <Table<WorkerTask>
              columns={taskColumns}
              dataSource={detail.runningTasks}
              locale={{ emptyText: <ContentState description="当前没有运行中的任务" /> }}
              pagination={false}
              rowKey="taskId"
              scroll={{ x: 1390 }}
              size="small"
            />
          </section>
        </div>
      ) : null}
    </BaseDrawer>
  )
}
