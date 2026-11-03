import { useMemo } from 'react'
import { Button, Flex, Popconfirm, Tag, Tooltip } from 'antd'
import type { ColumnsType } from 'antd/es/table'

import { getProjectBuildValidations } from '@/api/project/build-validation'
import BaseTable from '@/components/BaseTable'
import type { ProjectBuildValidation } from '@/types/project'
import type { ProjectTaskStatus } from '@/types/project/task'
import { formatDateTime, getTaskStatusPresentation } from '@/utils/display'

import { isTerminalBuildReadinessStatus } from '../../buildReadinessContext'
import { BitcodeStatusTag } from './BitcodeResult'

import styles from '../index.module.scss'
import tableButtons from '@/styles/tableButtons.module.scss'
const buildStatusOptions: Array<{ label: string; value: ProjectTaskStatus }> = [
  { label: '等待中', value: 'PENDING' },
  { label: '排队中', value: 'QUEUED' },
  { label: '进行中', value: 'RUNNING' },
  { label: '成功', value: 'SUCCEEDED' },
  { label: '失败', value: 'FAILED' },
  { label: '已取消', value: 'CANCELLED' },
  { label: '超时', value: 'TIMEOUT' }
]

function taskStatusTag(status: ProjectTaskStatus) {
  const { color, label } = getTaskStatusPresentation(status)
  return (
    <Tag className={styles.statusTag} color={color}>
      {label}
    </Tag>
  )
}

export default function X86BuildRecords({
  projectId,
  queryKey,
  currentBuildId,
  onSelect,
  onCancel
}: {
  projectId?: string
  queryKey: readonly unknown[]
  currentBuildId?: string
  onSelect: (record: ProjectBuildValidation) => void
  onCancel: (id: string) => void
}) {
  const columns = useMemo<ColumnsType<ProjectBuildValidation>>(
    () => [
      { title: '源码版本', dataIndex: 'sourceRevisionId', width: 160, ellipsis: true },
      {
        title: 'ID',
        dataIndex: 'id',
        width: 280,
        render: (id: string) => (
          <Flex align="center" className={styles.buildIdCell} gap={8}>
            <Tooltip title={id}>
              <span>{id}</span>
            </Tooltip>
            {id === currentBuildId ? (
              <Tooltip title="当前上下文最近一次构建">
                <Tag className={styles.currentTag} color="blue">
                  当前
                </Tag>
              </Tooltip>
            ) : null}
          </Flex>
        )
      },
      {
        title: '状态',
        dataIndex: 'status',
        width: 106,
        render: (status: ProjectTaskStatus) => taskStatusTag(status)
      },
      {
        title: 'Bitcode',
        dataIndex: 'bitcode',
        width: 116,
        render: (_, record) => <BitcodeStatusTag build={record} />
      },
      { title: '创建时间', dataIndex: 'createdAt', width: 170, render: formatDateTime },
      {
        title: '操作',
        fixed: 'right',
        width: 126,
        render: (_, record) => (
          <Flex gap={4}>
            <Button
              className={tableButtons.textButton}
              size="small"
              type="link"
              onClick={() => onSelect(record)}
            >
              详情
            </Button>
            {!isTerminalBuildReadinessStatus(record.status) ? (
              <Popconfirm title="确认取消构建？" onConfirm={() => onCancel(record.id)}>
                <Button className={tableButtons.textButton} size="small" type="link">
                  取消
                </Button>
              </Popconfirm>
            ) : null}
          </Flex>
        )
      }
    ],
    [currentBuildId, onCancel, onSelect]
  )

  return (
    <section className={styles.section}>
      <h3>构建记录</h3>
      <BaseTable<ProjectBuildValidation, { status?: ProjectTaskStatus }>
        autoHeight
        columns={columns}
        emptyDescription="暂无 x86 构建记录"
        pagination={{ initialPageSize: 20 }}
        queryKey={queryKey}
        request={(query) =>
          getProjectBuildValidations(projectId ?? '', {
            includeStale: true,
            page: query.page,
            pageSize: query.pageSize,
            sortBy: 'createdAt',
            sortDirection: 'desc',
            buildScope: 'SOURCE_ONLY',
            status: query.status
          })
        }
        rowKey="id"
        scroll={{ x: 820 }}
        size="small"
        tableLayout="fixed"
        toolbar={{
          filters: {
            fields: [
              {
                ariaLabel: '筛选构建状态',
                name: 'status',
                options: buildStatusOptions,
                placeholder: '全部状态',
                type: 'select'
              }
            ]
          }
        }}
      />
    </section>
  )
}
