import { Button, Tag, Tooltip } from 'antd'
import type { ColumnsType } from 'antd/es/table'

import { getProjectKnowledgeBaseScans } from '@/api/project/knowledge-base'
import BaseTable from '@/components/BaseTable'
import ShortIdentifier from '@/components/ShortIdentifier'
import type { ProjectKnowledgeBaseScan } from '@/types/project/knowledge-base'
import { formatDateTime, getTaskStatusPresentation } from '@/utils/display'

import { projectQueryKeys } from '../../../queryKeys'

import tableButtons from '@/styles/tableButtons.module.scss'

function statusTag(status: ProjectKnowledgeBaseScan['status']) {
  const { color, label } = getTaskStatusPresentation(status, { labels: { SUCCEEDED: '完成' } })
  return <Tag color={color}>{label}</Tag>
}

export function ScanHistory({
  projectId,
  onOpenScan
}: {
  projectId: string
  onOpenScan: (scanId: string) => void
}) {
  const columns: ColumnsType<ProjectKnowledgeBaseScan> = [
    {
      title: '扫描 ID',
      dataIndex: 'id',
      width: 150,
      render: (value: string) => (
        <Tooltip title={value}>
          <ShortIdentifier value={value} />
        </Tooltip>
      )
    },
    { title: '状态', dataIndex: 'status', width: 100, render: statusTag },
    {
      title: '源码上下文',
      dataIndex: 'currentSourceRevision',
      width: 120,
      render: (current: boolean | undefined) =>
        current ? <Tag color="success">当前源码</Tag> : <Tag>历史源码</Tag>
    },
    {
      title: '源码版本',
      dataIndex: 'sourceRevisionId',
      width: 150,
      render: (value: string) => (
        <Tooltip title={value}>
          <ShortIdentifier value={value} />
        </Tooltip>
      )
    },
    { title: '知识库版本', dataIndex: 'kbVersion', width: 130, render: (value) => value || '--' },
    { title: '开始时间', dataIndex: 'startedAt', width: 170, render: formatDateTime },
    { title: '完成时间', dataIndex: 'finishedAt', width: 170, render: formatDateTime },
    {
      title: '操作',
      fixed: 'right',
      width: 110,
      render: (_, record) => (
        <Button
          className={tableButtons.textButton}
          type="link"
          onClick={() => onOpenScan(record.id)}
        >
          查看
        </Button>
      )
    }
  ]

  return (
    <BaseTable<ProjectKnowledgeBaseScan, Record<string, never>>
      autoHeight
      columns={columns}
      emptyDescription="暂无知识库扫描历史记录"
      errorDescription="知识库扫描历史记录加载失败"
      pagination={{ initialPageSize: 20 }}
      queryKey={[...projectQueryKeys.knowledgeBaseScans(projectId), 'history']}
      request={(query) =>
        getProjectKnowledgeBaseScans(projectId, {
          includeStale: true,
          sortBy: 'startedAt',
          sortDirection: 'desc',
          page: query.page,
          pageSize: query.pageSize
        })
      }
      rowKey="id"
      scroll={{ x: 1100 }}
      tableLayout="fixed"
    />
  )
}
