import { Button } from 'antd'
import type { ColumnsType } from 'antd/es/table'

import { getProjectOptimizationCycles } from '@/api/project/optimization-cycle'
import BaseTable from '@/components/BaseTable'
import type { ProjectOptimizationCycle, ProjectOptimizationCycleStatus } from '@/types/project'
import { formatDateTime } from '@/utils/display'

import { projectQueryKeys } from '../../../queryKeys'
import { getRevisionLabel, optimizationCycleStatusOptions } from '../presentation'
import { OptimizationStatusTag } from './OptimizationStatusTag'

import styles from '../index.module.scss'
import tableButtons from '@/styles/tableButtons.module.scss'

interface CycleHistoryProps {
  projectId: string
  onOpen: (cycle: ProjectOptimizationCycle) => void
}

interface CycleFilters {
  status?: ProjectOptimizationCycleStatus
}

function columns(
  onOpen: (cycle: ProjectOptimizationCycle) => void
): ColumnsType<ProjectOptimizationCycle> {
  return [
    {
      title: '轮次',
      dataIndex: 'cycleNumber',
      width: 88,
      render: (value: number) => `第 ${value} 轮`
    },
    {
      title: '源码版本',
      dataIndex: 'sourceRevision',
      width: 190,
      render: (value: ProjectOptimizationCycle['sourceRevision']) => (
        <span className={styles.historyRevision}>{getRevisionLabel(value)}</span>
      )
    },
    {
      title: '轮次状态',
      dataIndex: 'status',
      width: 112,
      render: (value: string) => <OptimizationStatusTag status={value} />
    },
    {
      title: '构建',
      dataIndex: ['summary', 'build', 'latestStatus'],
      width: 108,
      render: (value: string | null) => <OptimizationStatusTag status={value} />
    },
    {
      title: '测试',
      dataIndex: ['summary', 'test', 'latestStatus'],
      width: 108,
      render: (value: string | null) => <OptimizationStatusTag status={value} />
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      width: 174,
      render: (value: string) => formatDateTime(value)
    },
    {
      title: '操作',
      fixed: 'right',
      width: 84,
      render: (_, record) => (
        <Button
          className={tableButtons.textButton}
          size="small"
          type="link"
          onClick={() => onOpen(record)}
        >
          查看
        </Button>
      )
    }
  ]
}

export function CycleHistory({ projectId, onOpen }: CycleHistoryProps) {
  return (
    <BaseTable<ProjectOptimizationCycle, CycleFilters>
      columns={columns(onOpen)}
      pagination={{ initialPageSize: 10, showSizeChanger: true }}
      queryKey={projectQueryKeys.optimizationCycles(projectId)}
      request={(query) =>
        getProjectOptimizationCycles(projectId, {
          page: query.page,
          pageSize: query.pageSize,
          sortBy: 'createdAt',
          sortDirection: 'desc',
          status: query.status
        })
      }
      rowKey="id"
      scroll={{ x: 772 }}
      size="small"
      tableLayout="fixed"
      toolbar={{
        filters: {
          fields: [
            {
              name: 'status',
              options: optimizationCycleStatusOptions,
              placeholder: '轮次状态',
              type: 'select'
            }
          ]
        }
      }}
    />
  )
}
