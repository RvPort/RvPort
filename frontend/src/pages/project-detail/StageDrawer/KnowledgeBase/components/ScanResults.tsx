import { LinkOutlined } from '@ant-design/icons'
import { Button, Flex, Popconfirm, Tag, Tooltip } from 'antd'
import type { ColumnsType } from 'antd/es/table'

import { getProjectKnowledgeBaseFindings } from '@/api/project/knowledge-base'
import BaseTable from '@/components/BaseTable'
import EllipsisText from '@/components/EllipsisText'
import ShortIdentifier from '@/components/ShortIdentifier'
import type {
  ProjectKnowledgeBaseFinding,
  ProjectKnowledgeBaseScan
} from '@/types/project/knowledge-base'
import { formatDateTime } from '@/utils/display'

import { projectQueryKeys } from '../../../queryKeys'
import {
  knowledgeBaseSeverityOptions,
  renderFindingStatusTag,
  renderSeverityTag
} from '../../components/FindingDetail/tags'
import { MigrationInputActions } from '../../components/MigrationInputActions'
import { getExecutionStatusText } from '../../utils'
import { backendPendingText, getKnowledgeBaseOverview } from '../presentation'
import type { FindingFilters } from '../types'
import { AiSuggestionStatus, type AiSuggestionStatusProps } from './AiSuggestionStatus'

import styles from '../index.module.scss'
import tableButtons from '@/styles/tableButtons.module.scss'

export function ScanResults({
  aiSuggestion,
  projectId,
  scan,
  onOpenFinding,
  onRescan,
  onShowHistory,
  onShowScanInfo,
  readOnly = false
}: {
  aiSuggestion?: AiSuggestionStatusProps
  projectId: string
  scan: ProjectKnowledgeBaseScan
  onOpenFinding: (findingId: string) => void
  onRescan?: () => void
  onShowHistory?: () => void
  onShowScanInfo: () => void
  readOnly?: boolean
}) {
  const overview = getKnowledgeBaseOverview(scan.summary)
  const columns: ColumnsType<ProjectKnowledgeBaseFinding> = [
    {
      title: '文件',
      dataIndex: 'filePath',
      fixed: 'left',
      width: 168,
      render: (value: string | null, record) => (
        <Tooltip title={value || backendPendingText}>
          <Button
            className={`${tableButtons.textButton} ${styles.tableLink}`}
            type="link"
            onClick={() => onOpenFinding(record.id)}
          >
            {value || backendPendingText}
          </Button>
        </Tooltip>
      )
    },
    {
      title: '风险',
      dataIndex: 'severity',
      fixed: 'left',
      width: 88,
      render: renderSeverityTag
    },
    { title: '行号', dataIndex: 'lineStart', width: 80 },
    {
      title: '说明',
      dataIndex: 'description',
      width: 240,
      render: (value: string) => <EllipsisText>{value}</EllipsisText>
    },
    {
      title: '建议',
      dataIndex: 'suggestion',
      width: 260,
      render: (value: string, record) => (
        <div className={styles.findingSuggestionCell}>
          {record.aiExtension ? <span className={styles.aiSuggestionBadge}>AI 补充</span> : null}
          <EllipsisText>{value}</EllipsisText>
        </div>
      )
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 112,
      render: renderFindingStatusTag
    }
  ]
  if (!readOnly) {
    columns.push({
      title: '操作',
      fixed: 'right',
      width: 120,
      render: (_, record) => (
        <MigrationInputActions
          initialStatus={record.migrationInputStatus ?? undefined}
          invalidateQueryKeys={[
            projectQueryKeys.knowledgeBaseFinding(record.id),
            projectQueryKeys.knowledgeBaseFindings(scan.id)
          ]}
          migrationInputRefId={record.migrationInputRefId}
          projectId={projectId}
          sourceRefId={record.id}
          sourceRevisionId={scan.sourceRevisionId}
          sourceType="KB_FINDING"
        />
      )
    })
  }

  return (
    <div className={styles.content}>
      <section>
        <Flex align="center" justify="space-between">
          <h3>概览</h3>
          <Flex gap={8}>
            {readOnly ? <Tag>只读记录</Tag> : null}
            {onRescan ? (
              <Popconfirm
                cancelText="取消"
                description="重新扫描会生成新的扫描结果。"
                okText="继续"
                title="确认重新扫描？"
                onConfirm={onRescan}
              >
                <Button>重新扫描</Button>
              </Popconfirm>
            ) : null}
            {onShowHistory ? (
              <Button type="link" onClick={onShowHistory}>
                查看历史记录
              </Button>
            ) : null}
          </Flex>
        </Flex>
        <div className={styles.scanOverview}>
          <div className={styles.scanRunSummary}>
            <div className={styles.scanRunStatus}>
              <span className={styles.scanStatusDot} aria-hidden="true" />
              <div>
                <span>扫描状态</span>
                <strong>{getExecutionStatusText(scan.status)}</strong>
              </div>
            </div>
            <div className={styles.scanRunMetaItem}>
              <span>扫描 ID</span>
              <Tooltip title="查看扫描信息">
                <Button
                  aria-label="查看扫描信息"
                  className={styles.scanLink}
                  type="link"
                  onClick={onShowScanInfo}
                >
                  <ShortIdentifier value={scan.id} />
                  <LinkOutlined />
                </Button>
              </Tooltip>
            </div>
            <div className={styles.scanRunMetaItem}>
              <span>完成时间</span>
              <strong>{formatDateTime(scan.finishedAt)}</strong>
            </div>
          </div>
          <div className={styles.scanOverviewContent}>
            <div className={styles.scanScalePanel}>
              <span className={styles.scanOverviewLabel}>扫描规模</span>
              <div className={styles.scanScaleSummary}>
                <div data-tone="findings">
                  <span>发现项</span>
                  <strong>{overview.findingCount}</strong>
                </div>
                <div data-tone="files">
                  <span>扫描文件</span>
                  <strong>{overview.files}</strong>
                </div>
              </div>
            </div>
            <div className={styles.scanComposition}>
              <span className={styles.scanOverviewLabel}>结果构成</span>
              <div className={styles.scanAttributeSummary}>
                <span data-active={overview.issueCount > 0} data-tone="issue">
                  问题 <strong>{overview.issueCount}</strong>
                </span>
                <span data-active={overview.hintCount > 0} data-tone="hint">
                  提示 <strong>{overview.hintCount}</strong>
                </span>
              </div>
            </div>
            <div className={styles.scanActionPanel}>
              <span className={styles.scanOverviewLabel}>处理关注</span>
              <div className={styles.scanAttributeSummary}>
                <span data-active={overview.blockingCount > 0} data-tone="blocking">
                  阻塞项 <strong>{overview.blockingCount}</strong>
                </span>
              </div>
            </div>
            <div className={styles.scanPathPanel}>
              <span className={styles.scanOverviewLabel}>目标路径判断</span>
              <div className={styles.scanAttributeSummary}>
                <span
                  data-active={
                    overview.portabilityGapCount !== null && overview.portabilityGapCount > 0
                  }
                  data-tone="gap"
                >
                  缺少可移植替代{' '}
                  <strong
                    title={
                      overview.portabilityGapCount === null ? '当前扫描未完成此项分析' : undefined
                    }
                  >
                    {overview.portabilityGapCount ?? '--'}
                  </strong>
                </span>
                <span data-active={overview.inactiveOrGuardedCount > 0} data-tone="inactive">
                  当前目标未选中 <strong>{overview.inactiveOrGuardedCount}</strong>
                </span>
              </div>
            </div>
          </div>
          {overview.status && overview.status !== 'complete' ? (
            <p className={styles.scanCompletenessNotice}>
              部分输入未完成，当前数据仅反映已分析范围。
            </p>
          ) : null}
          {aiSuggestion ? <AiSuggestionStatus {...aiSuggestion} /> : null}
        </div>
      </section>
      <section className={styles.resultsSection}>
        <h3>扫描结果</h3>
        <BaseTable<ProjectKnowledgeBaseFinding, FindingFilters>
          autoHeight
          columns={columns}
          emptyDescription="暂无知识库扫描结果"
          errorDescription="扫描结果加载失败"
          pagination={{ initialPageSize: 10 }}
          queryKey={projectQueryKeys.knowledgeBaseFindings(scan.id)}
          request={(query) =>
            getProjectKnowledgeBaseFindings(scan.id, {
              filePath: query.filePath,
              page: query.page,
              pageSize: query.pageSize,
              severity: query.severity,
              status: query.status
            })
          }
          rowKey="id"
          scroll={{ x: 1200 }}
          tableLayout="fixed"
          toolbar={{
            filters: {
              fields: [
                { name: 'filePath', placeholder: '搜索文件路径', type: 'search' },
                {
                  name: 'severity',
                  options: knowledgeBaseSeverityOptions,
                  placeholder: '风险',
                  type: 'select'
                },
                {
                  name: 'status',
                  options: [
                    { label: '已发现', value: 'DETECTED' },
                    { label: '已抑制', value: 'SUPPRESSED' }
                  ],
                  placeholder: '状态',
                  type: 'select'
                }
              ]
            }
          }}
        />
      </section>
    </div>
  )
}
