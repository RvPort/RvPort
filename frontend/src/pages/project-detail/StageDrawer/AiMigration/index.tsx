import { useMemo } from 'react'
import { ReloadOutlined, RocketOutlined } from '@ant-design/icons'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, App, Button, Space, Table, Tag, Typography } from 'antd'

import {
  type AiMigrationRun,
  createProjectAiMigration,
  getProjectAiMigrations} from '@/api/project/ai-migration'
import { getProjectKnowledgeBaseScanLatest } from '@/api/project/knowledge-base'

import StageSurface from '../components/StageSurface'
import { getStageDrawerWidth } from '../drawerWidth'
import type { StageDrawerProps } from '../types'

const STATUS_TAG: Record<string, { color: string; text: string }> = {
  PENDING: { color: 'default', text: '排队中' },
  RUNNING: { color: 'processing', text: '迁移中' },
  SUCCEEDED: { color: 'success', text: '已完成' },
  FAILED: { color: 'error', text: '失败' },
  CANCELLED: { color: 'warning', text: '已取消' },
  TIMEOUT: { color: 'error', text: '超时' }
}

function statusTag(status: string) {
  const preset = STATUS_TAG[status] ?? { color: 'default', text: status }
  return <Tag color={preset.color}>{preset.text}</Tag>
}

export default function AiMigration({
  projectId,
  visible,
  stage,
  onClose
}: StageDrawerProps) {
  const { message } = App.useApp()
  const queryClient = useQueryClient()

  const latestScanQuery = useQuery({
    queryKey: ['project', projectId, 'kb-scan', 'latest'],
    queryFn: () => getProjectKnowledgeBaseScanLatest(projectId ?? ''),
    enabled: visible && Boolean(projectId)
  })

  const runsQuery = useQuery({
    queryKey: ['project', projectId, 'ai-migrations'],
    queryFn: () => getProjectAiMigrations(projectId ?? '', { page: 0, pageSize: 20 }),
    enabled: visible && Boolean(projectId)
  })

  const createMutation = useMutation({
    mutationFn: () => createProjectAiMigration(projectId ?? ''),
    onSuccess: async () => {
      message.success('AI 迁移任务已创建')
      await queryClient.invalidateQueries({ queryKey: ['project', projectId, 'ai-migrations'] })
    },
    onError: (error: { response?: { data?: { message?: string } } }) => {
      message.error(error.response?.data?.message ?? '创建失败，请检查阶段模型绑定与扫描状态')
    }
  })

  const scan = latestScanQuery.data?.result ?? null
  const scanReady = scan?.status === 'SUCCEEDED'
  const runs = useMemo(() => runsQuery.data?.items ?? [], [runsQuery.data])

  const columns = [
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (status: string) => statusTag(status)
    },
    {
      title: '条目',
      key: 'items',
      width: 180,
      render: (_: unknown, record: AiMigrationRun) =>
        record.status === 'PENDING'
          ? '—'
          : `${record.doneCount} 完成 / ${record.falsePositiveCount} 误报 / ${record.pendingCount} 待处理`
    },
    {
      title: '扫描',
      dataIndex: 'scanRunId',
      key: 'scanRunId',
      width: 120,
      render: (id: string) => (
        <Typography.Text copyable={{ text: id }} style={{ fontSize: 12 }}>
          {id.slice(0, 8)}…
        </Typography.Text>
      )
    }
  ]

  return (
    <StageSurface
      title="AI 迁移 · 基于知识库扫描条目的自动化代码迁移"
      size={getStageDrawerWidth(stage?.id)}
      visible={visible}
      onClose={onClose}
      extra={
        <Space>
          <Button
            icon={<ReloadOutlined />}
            onClick={() => runsQuery.refetch()}
            loading={runsQuery.isFetching}
          >
            刷新
          </Button>
          <Button
            type="primary"
            icon={<RocketOutlined />}
            disabled={!scanReady || !projectId}
            loading={createMutation.isPending}
            onClick={() => createMutation.mutate()}
            title={scanReady ? undefined : '需要当前有效的知识库扫描'}
          >
            发起 AI 迁移
          </Button>
        </Space>
      }
    >
      {!latestScanQuery.isLoading && !scanReady && (
        <Alert
          type="info"
          showIcon
          message="尚无有效的知识库扫描"
          description="AI 迁移以知识库扫描条目为输入，请先完成知识库扫描。"
          style={{ marginBottom: 16 }}
        />
      )}
      <Table<AiMigrationRun>
        rowKey="id"
        size="small"
        columns={columns}
        dataSource={runs}
        loading={runsQuery.isLoading}
        pagination={{ pageSize: 10, hideOnSinglePage: true }}
        locale={{ emptyText: '暂无迁移运行' }}
      />
    </StageSurface>
  )
}
