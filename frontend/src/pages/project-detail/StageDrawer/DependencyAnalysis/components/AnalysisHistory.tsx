import { useMemo } from 'react'
import { Button, Tooltip } from 'antd'
import type { ColumnsType } from 'antd/es/table'

import { getProjectDependencyAnalyses } from '@/api/project/dependency-analysis'
import BaseTable from '@/components/BaseTable'
import type { ProjectDependencyAnalysis } from '@/types/project/dependency-analysis'
import { formatDateTime } from '@/utils/display'

import { projectQueryKeys } from '../../../queryKeys'
import { riskTagLabel, summaryCount, summaryGroupEntries, taskStatusTag } from './presentation'

import styles from '../index.module.scss'
import tableButtons from '@/styles/tableButtons.module.scss'

export default function AnalysisHistory({
  projectId,
  onOpenAnalysis
}: {
  projectId?: string
  onOpenAnalysis: (analysis: ProjectDependencyAnalysis) => void
}) {
  const analysisKey = [...projectQueryKeys.dependencyAnalyses(projectId), 'history']
  const historyColumns = useMemo<ColumnsType<ProjectDependencyAnalysis>>(
    () => [
      { title: '源码版本', dataIndex: 'sourceRevisionId', width: 160, ellipsis: true },
      {
        title: '记录 ID',
        dataIndex: 'id',
        width: 220,
        ellipsis: { showTitle: false },
        render: (value) => <Tooltip title={value}>{value}</Tooltip>
      },
      { title: '状态', dataIndex: 'status', width: 108, render: taskStatusTag },
      {
        title: '结果概览',
        width: 160,
        render: (_, record) => {
          const total = summaryCount(record.summary, 'total')
          const riskDistribution = summaryGroupEntries(record.summary, 'byRisk')
          const riskText = riskDistribution
            .map(([risk, count]) => `${riskTagLabel(risk)} ${count}`)
            .join('、')
          return record.status === 'SUCCEEDED'
            ? `共 ${total} 项${riskText ? `，${riskText}` : ''}`
            : '--'
        }
      },
      { title: '创建时间', dataIndex: 'createdAt', width: 170, render: formatDateTime },
      {
        title: '操作',
        fixed: 'right',
        width: 84,
        render: (_, record) => (
          <Button
            className={tableButtons.textButton}
            size="small"
            type="link"
            onClick={() => onOpenAnalysis(record)}
          >
            查看
          </Button>
        )
      }
    ],
    [onOpenAnalysis]
  )
  return (
    <div className={styles.content}>
      <section className={styles.section}>
        <BaseTable<ProjectDependencyAnalysis, Record<string, never>>
          autoHeight
          columns={historyColumns}
          emptyDescription="暂无依赖分析记录"
          pagination={{ initialPageSize: 20 }}
          queryKey={analysisKey}
          request={(query) =>
            getProjectDependencyAnalyses(projectId ?? '', {
              page: query.page,
              pageSize: query.pageSize,
              includeStale: true,
              sortBy: 'createdAt',
              sortDirection: 'desc'
            })
          }
          rowKey="id"
          scroll={{ x: 780 }}
          size="small"
          tableLayout="fixed"
        />
      </section>
    </div>
  )
}
