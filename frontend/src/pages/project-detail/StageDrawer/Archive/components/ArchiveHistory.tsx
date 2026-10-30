import { Button } from 'antd'
import type { ColumnsType } from 'antd/es/table'

import { getProjectArchives } from '@/api/project/archive'
import BaseTable from '@/components/BaseTable'
import EllipsisText from '@/components/EllipsisText'
import type { ProjectArchive, ProjectArchiveStatus } from '@/types/project'
import { formatDateTime } from '@/utils/display'

import { projectQueryKeys } from '../../../queryKeys'
import { archiveStatusOptions, formatBytes, getSourceRevisionLabel } from '../presentation'
import { ArchiveStatusTag } from './ArchiveStatusTag'

import styles from '../index.module.scss'
import tableButtons from '@/styles/tableButtons.module.scss'

interface ArchiveFilters {
  status?: ProjectArchiveStatus
}

export function ArchiveHistory({
  projectId,
  onOpen
}: {
  projectId: string
  onOpen: (archiveId: string) => void
}) {
  const columns: ColumnsType<ProjectArchive> = [
    {
      title: '归档版本',
      key: 'identity',
      width: 240,
      render: (_value, record) => (
        <EllipsisText>{getSourceRevisionLabel(record.sourceRevision)}</EllipsisText>
      )
    },
    {
      title: '交付内容',
      key: 'contents',
      width: 180,
      render: (_value, record) => {
        const summaryCount = record.summary.itemCount
        const count = typeof summaryCount === 'number' ? summaryCount : record.items.length
        const summaryFormats = Array.isArray(record.summary.formats)
          ? record.summary.formats.filter((value): value is string => typeof value === 'string')
          : []
        return (
          <div className={styles.historyContents}>
            <strong>{count} 项</strong>
            <span>{summaryFormats.length ? summaryFormats.join(' / ') : '格式信息未记录'}</span>
          </div>
        )
      }
    },
    {
      title: '归档包',
      dataIndex: 'sizeBytes',
      width: 112,
      render: (value: number | null) => formatBytes(value)
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 112,
      render: (value: string) => <ArchiveStatusTag status={value} />
    },
    {
      title: '生成时间',
      dataIndex: 'updatedAt',
      width: 176,
      render: (value: string) => formatDateTime(value)
    },
    {
      title: '操作',
      key: 'action',
      width: 96,
      render: (_value, record) => (
        <Button
          className={tableButtons.textButton}
          size="small"
          type="link"
          onClick={() => onOpen(record.id)}
        >
          查看详情
        </Button>
      )
    }
  ]

  return (
    <BaseTable<ProjectArchive, ArchiveFilters>
      columns={columns}
      pagination={{ initialPageSize: 10, showSizeChanger: true }}
      queryKey={projectQueryKeys.archives(projectId)}
      request={(query) =>
        getProjectArchives(projectId, {
          page: query.page,
          pageSize: query.pageSize,
          status: query.status,
          sortBy: 'createdAt',
          sortDirection: 'desc'
        })
      }
      rowKey="id"
      scroll={{ x: 916 }}
      size="small"
      tableLayout="fixed"
      toolbar={{
        filters: {
          fields: [
            {
              name: 'status',
              options: archiveStatusOptions,
              placeholder: '归档状态',
              type: 'select'
            }
          ]
        }
      }}
    />
  )
}
