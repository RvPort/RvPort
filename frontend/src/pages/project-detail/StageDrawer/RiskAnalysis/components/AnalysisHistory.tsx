import { Button, Tag, Tooltip } from 'antd'
import type { ColumnsType } from 'antd/es/table'

import { getSpecializedAnalyses } from '@/api/project/specialized-analysis'
import BaseTable from '@/components/BaseTable'
import ShortIdentifier from '@/components/ShortIdentifier'
import type {
  SpecializedAnalysis,
  SpecializedAnalysisStageId
} from '@/types/project/specialized-analysis'
import { analysisDiagnostics, isPartialAnalysis } from '@/utils/analysisResult'
import { formatDateTime } from '@/utils/display'

import { projectQueryKeys } from '../../../queryKeys'
import { statusLabels } from '../presentation'

import tableButtons from '@/styles/tableButtons.module.scss'

export default function AnalysisHistory({
  projectId,
  stageId,
  onSelect
}: {
  projectId: string
  stageId: SpecializedAnalysisStageId
  onSelect: (id: string) => void
}) {
  const columns: ColumnsType<SpecializedAnalysis> = [
    {
      title: '记录 ID',
      dataIndex: 'id',
      width: 150,
      render: (id: string) => (
        <Tooltip title={id}>
          <ShortIdentifier value={id} />
        </Tooltip>
      )
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 140,
      render: (status: SpecializedAnalysis['status'], run) => (
        <Tag>{isPartialAnalysis(run) ? '部分分析完成' : statusLabels[status]}</Tag>
      )
    },
    {
      title: '源码上下文',
      dataIndex: 'currentSourceRevision',
      width: 120,
      render: (current: boolean) => (
        <Tag color={current ? 'success' : undefined}>{current ? '当前源码' : '历史源码'}</Tag>
      )
    },
    {
      title: '结果概览',
      width: 180,
      render: (_, run) =>
        `${run.findingCount} 项结果，${analysisDiagnostics(run).count ?? '未知'} 项诊断`
    },
    { title: '创建时间', dataIndex: 'createdAt', width: 170, render: formatDateTime },
    { title: '完成时间', dataIndex: 'finishedAt', width: 170, render: formatDateTime },
    {
      title: '操作',
      fixed: 'right',
      width: 84,
      render: (_, run) => (
        <Button
          className={tableButtons.textButton}
          size="small"
          type="link"
          onClick={() => onSelect(run.id)}
        >
          查看
        </Button>
      )
    }
  ]
  return (
    <BaseTable<SpecializedAnalysis, Record<string, never>>
      autoHeight
      columns={columns}
      emptyDescription="暂无分析历史记录"
      errorDescription="分析历史记录加载失败"
      pagination={{ initialPageSize: 20 }}
      queryKey={[...projectQueryKeys.specializedAnalyses(projectId, stageId), 'history']}
      request={(query) =>
        getSpecializedAnalyses(projectId, stageId, {
          page: query.page,
          pageSize: query.pageSize,
          sortBy: 'createdAt',
          sortDirection: 'desc'
        })
      }
      rowKey="id"
      scroll={{ x: 1000 }}
      tableLayout="fixed"
    />
  )
}
