import { DiffOutlined, DownloadOutlined } from '@ant-design/icons'
import { Button, Flex, Tag, Tooltip } from 'antd'

import { type AiMigrationRun, getAiMigrationArtifactDownloadUrl } from '@/api/project/ai-migration'
import ContentState from '@/components/ContentState'

import {
  describeAiMigrationRun,
  hasAiMigrationArtifact,
  presentAiMigrationStatus
} from '../presentation'

import styles from '../index.module.scss'

function formatTime(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  return date.toLocaleString('zh-CN', { hour12: false })
}

export function RunList({
  isLoading,
  runs,
  onViewDiff
}: {
  isLoading: boolean
  runs: AiMigrationRun[]
  onViewDiff: (runId: string) => void
}) {
  if (isLoading) {
    return <ContentState compact description="正在加载迁移运行" kind="loading" />
  }
  if (runs.length === 0) {
    return <ContentState compact description="还没有发起过 AI 迁移" />
  }
  return (
    <div className={styles.runList}>
      {runs.map((run) => {
        const status = presentAiMigrationStatus(run.status)
        const canViewDiff = hasAiMigrationArtifact(run, 'MIGRATION_DIFF')
        const time = run.createdAt ? formatTime(run.createdAt) : ''
        return (
          <div className={styles.run} key={run.id}>
            <Flex className={styles.runHead} gap={8} justify="space-between" wrap>
              <Flex align="center" gap={8}>
                <Tag color={status.color}>{status.label}</Tag>
                <span className={styles.runTime}>{time}</span>
              </Flex>
              <div className={styles.runActions}>
                {canViewDiff ? (
                  <Button icon={<DiffOutlined />} size="small" onClick={() => onViewDiff(run.id)}>
                    查看补丁
                  </Button>
                ) : null}
                {canViewDiff ? (
                  <Tooltip title="下载补丁文件（.patch）">
                    <Button
                      aria-label="下载补丁文件"
                      href={getAiMigrationArtifactDownloadUrl(run.id, 'diff')}
                      icon={<DownloadOutlined />}
                      size="small"
                    />
                  </Tooltip>
                ) : null}
                {hasAiMigrationArtifact(run, 'MIGRATION_RESULT') ? (
                  <Tooltip title="下载逐条目结论（.json）">
                    <Button
                      aria-label="下载逐条目结论"
                      href={getAiMigrationArtifactDownloadUrl(run.id, 'result')}
                      icon={<DownloadOutlined />}
                      size="small"
                    />
                  </Tooltip>
                ) : null}
              </div>
            </Flex>
            <p className={styles.runSummary}>{describeAiMigrationRun(run)}</p>
            <span className={styles.runMeta}>
              基于扫描 <code>{run.scanRunId.slice(0, 8)}</code>
              {run.status === 'RUNNING' || run.status === 'PENDING' ? '，完成后自动刷新' : ''}
            </span>
          </div>
        )
      })}
    </div>
  )
}
