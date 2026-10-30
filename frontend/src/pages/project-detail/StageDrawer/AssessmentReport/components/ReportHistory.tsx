import { Button, Tag } from 'antd'

import { getProjectEvaluationReports } from '@/api/project/assessment-report'
import BaseTable from '@/components/BaseTable'
import type { ProjectEvaluationReport } from '@/types/project'
import { formatDateTime, getTaskStatusPresentation } from '@/utils/display'

import { projectQueryKeys } from '../../../queryKeys'

import tableButtons from '@/styles/tableButtons.module.scss'

export default function ReportHistory({
  projectId,
  onOpen
}: {
  projectId: string
  onOpen: (id: string) => void
}) {
  return (
    <BaseTable<ProjectEvaluationReport, Record<string, never>>
      autoHeight
      rowKey="id"
      queryKey={[...projectQueryKeys.evaluationReports(projectId), 'history']}
      pagination={{ initialPageSize: 20 }}
      emptyDescription="暂无评估报告记录"
      errorDescription="评估报告记录加载失败"
      request={(query) =>
        getProjectEvaluationReports(projectId, {
          page: query.page,
          pageSize: query.pageSize,
          sortBy: 'createdAt',
          sortDirection: 'desc'
        })
      }
      columns={[
        { title: '报告 ID', dataIndex: 'id', ellipsis: true },
        { title: '源码版本', dataIndex: 'sourceRevisionId', ellipsis: true },
        {
          title: '状态',
          dataIndex: 'status',
          render: (status) => {
            const { color, label } = getTaskStatusPresentation(status)
            return <Tag color={color}>{label}</Tag>
          }
        },
        { title: '创建时间', dataIndex: 'createdAt', render: formatDateTime },
        {
          title: '操作',
          render: (_, row) => (
            <Button className={tableButtons.textButton} type="link" onClick={() => onOpen(row.id)}>
              查看
            </Button>
          )
        }
      ]}
    />
  )
}
