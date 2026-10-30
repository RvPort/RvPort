import { useState } from 'react'
import type { QueryKey } from '@tanstack/react-query'
import { useQueryClient } from '@tanstack/react-query'
import { Button, Flex, Popconfirm, Space, Tag } from 'antd'

import {
  getProjectMigrationInputs,
  importProjectMigrationInputs,
  updateProjectMigrationInput
} from '@/api/project/migration-input'
import { readAllPages } from '@/api/readAllPages'
import { useSessionMutation } from '@/hooks/useSessionMutation'
import { SessionChangedError } from '@/store/authSession'
import type {
  ProjectMigrationInputItem,
  ProjectMigrationInputSourceType,
  ProjectMigrationInputStatus
} from '@/types/project/migration-input'
import { feedback } from '@/utils/feedback'

import { projectQueryKeys } from '../../queryKeys'

import styles from './MigrationInputActions.module.scss'
import tableButtons from '@/styles/tableButtons.module.scss'

type ReportInputActionStatus = ProjectMigrationInputStatus
const emptyQueryKeys: QueryKey[] = []

function syncMigrationInputStatusCache(
  current: unknown,
  sourceRefId: string,
  migrationInputRefId: string,
  status: ProjectMigrationInputStatus
): unknown {
  if (!current || typeof current !== 'object') return current

  const patchRecord = (value: unknown) => {
    if (!value || typeof value !== 'object') return value
    const record = value as Record<string, unknown>
    return record.id === sourceRefId
      ? {
          ...record,
          migrationInputRefId,
          migrationInputStatus: status,
          ...(record.reportInclusion && typeof record.reportInclusion === 'object'
            ? {
                reportInclusion: {
                  ...record.reportInclusion,
                  migrationInputRefId,
                  migrationInputStatus: status,
                  confirmedForNextReport: status === 'CONFIRMED'
                }
              }
            : {})
        }
      : value
  }
  const record = current as Record<string, unknown>
  return Array.isArray(record.items)
    ? { ...record, items: record.items.map(patchRecord) }
    : patchRecord(current)
}

export function MigrationInputStatusTag({
  status
}: {
  status?: ProjectMigrationInputStatus | null
}) {
  if (status === 'CONFIRMED') return <Tag color="success">已纳入报告</Tag>
  if (status === 'EXCLUDED') return <Tag>已排除</Tag>
  return <Tag color="processing">未纳入报告</Tag>
}

export function MigrationInputActions({
  initialStatus,
  migrationInputRefId,
  projectId,
  presentation = 'row',
  sourceRefId,
  sourceRevisionId,
  sourceType,
  invalidateQueryKeys = emptyQueryKeys
}: {
  initialStatus?: ProjectMigrationInputStatus
  invalidateQueryKeys?: QueryKey[]
  migrationInputRefId?: string | null
  projectId: string
  presentation?: 'footer' | 'row'
  sourceRefId: string
  sourceRevisionId: string
  sourceType: ProjectMigrationInputSourceType
}) {
  const queryClient = useQueryClient()
  const [statusOverride, setStatusOverride] = useState<{
    owner: symbol
    initialStatus: ReportInputActionStatus | undefined
    value: ReportInputActionStatus
  }>()
  const mutation = useSessionMutation({
    sessionKey: `migration-input:${projectId}:${sourceRevisionId}:${sourceType}:${sourceRefId}`,
    active: true,
    mutationFn: async (
      nextStatus: Extract<ReportInputActionStatus, 'CONFIRMED' | 'EXCLUDED'>,
      isCurrent
    ) => {
      let inputId = migrationInputRefId
      if (!inputId) {
        const imported = await importProjectMigrationInputs(projectId, sourceRevisionId, {
          sourceRefIds: [sourceRefId],
          sourceType
        })
        if (!isCurrent()) throw new SessionChangedError()
        inputId = imported.items.find((item) => item.sourceRefId === sourceRefId)?.id
        if (!inputId) {
          const records = await readAllPages(
            (page, pageSize) =>
              getProjectMigrationInputs(projectId, sourceRevisionId, {
                page,
                pageSize,
                sourceType
              }),
            { shouldContinue: isCurrent }
          )
          if (!isCurrent()) throw new SessionChangedError()
          inputId = records.items.find((item) => item.sourceRefId === sourceRefId)?.id
        }
      }
      if (!inputId) throw new Error('迁移问题记录不存在')
      if (!isCurrent()) throw new SessionChangedError()
      return updateProjectMigrationInput(inputId, { status: nextStatus })
    },
    onError: () => feedback.error('报告问题状态更新失败'),
    onSuccess: async (input: ProjectMigrationInputItem, _, isCurrent): Promise<void> => {
      const nextStatus = input.status ?? initialStatus
      if (!nextStatus) return
      setStatusOverride({ owner: mutation.token, initialStatus, value: nextStatus })
      invalidateQueryKeys.forEach((invalidateQueryKey) => {
        queryClient.setQueriesData({ queryKey: invalidateQueryKey }, (current) =>
          syncMigrationInputStatusCache(current, sourceRefId, input.id, nextStatus)
        )
      })
      await Promise.all([
        queryClient.invalidateQueries({
          queryKey: projectQueryKeys.readiness(projectId, 'report')
        }),
        queryClient.invalidateQueries({ queryKey: projectQueryKeys.optimizationCycles(projectId) }),
        ...invalidateQueryKeys.map((invalidateQueryKey) =>
          queryClient.invalidateQueries({ queryKey: invalidateQueryKey })
        )
      ])
      if (!isCurrent()) return
      feedback.success(
        nextStatus === 'EXCLUDED' ? '已排除，不会进入评估报告' : '已确认纳入评估报告'
      )
    }
  })
  const pendingStatus = mutation.isPending ? mutation.variables : undefined
  const status =
    statusOverride?.owner === mutation.token && statusOverride.initialStatus === initialStatus
      ? statusOverride.value
      : initialStatus
  const isFooter = presentation === 'footer'

  if (status === 'CONFIRMED') {
    return (
      <Space size={0}>
        <Popconfirm
          cancelText="取消"
          description="排除后，该问题不会进入后续评估报告。"
          okText="确认排除"
          title="确认排除该问题？"
          onConfirm={() => mutation.mutate('EXCLUDED')}
        >
          <Button
            className={isFooter ? undefined : tableButtons.textButton}
            danger
            disabled={mutation.isPending}
            loading={pendingStatus === 'EXCLUDED'}
            size={isFooter ? 'middle' : 'small'}
            type={isFooter ? 'default' : 'link'}
          >
            排除
          </Button>
        </Popconfirm>
      </Space>
    )
  }

  if (status === 'EXCLUDED') {
    return (
      <Space size={0}>
        <Button
          className={isFooter ? undefined : tableButtons.textButton}
          disabled={mutation.isPending}
          loading={pendingStatus === 'CONFIRMED'}
          size={isFooter ? 'middle' : 'small'}
          type={isFooter ? 'primary' : 'link'}
          onClick={() => mutation.mutate('CONFIRMED')}
        >
          纳入报告
        </Button>
      </Space>
    )
  }

  return (
    <Space size={0}>
      <Button
        className={isFooter ? undefined : tableButtons.textButton}
        disabled={mutation.isPending}
        loading={pendingStatus === 'CONFIRMED'}
        size={isFooter ? 'middle' : 'small'}
        type={isFooter ? 'primary' : 'link'}
        onClick={() => mutation.mutate('CONFIRMED')}
      >
        纳入报告
      </Button>
    </Space>
  )
}

export function MigrationInputDetailFooter({
  status,
  pendingVerification,
  ...actionProps
}: Omit<React.ComponentProps<typeof MigrationInputActions>, 'initialStatus' | 'presentation'> & {
  status?: ProjectMigrationInputStatus | null
  pendingVerification?: boolean
}) {
  return (
    <Flex align="center" className={styles.detailFooter} justify="flex-end">
      <div className={styles.detailFooterStatus}>
        <span>报告状态</span>
        <MigrationInputStatusTag status={status} />
        {pendingVerification ? (
          <Tag color="warning">
            {status === 'CONFIRMED' ? '已纳入，仍待核实' : '待核实项，默认待确认'}
          </Tag>
        ) : null}
      </div>
      <MigrationInputActions
        {...actionProps}
        initialStatus={status ?? undefined}
        presentation="footer"
      />
    </Flex>
  )
}
