import { useState } from 'react'
import { CloudSyncOutlined, ReloadOutlined } from '@ant-design/icons'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Button, Flex, Tag, Typography } from 'antd'

import { getAdminSkillStatus, updateAdminSkills } from '@/api/admin/skills'
import { useSessionMutation } from '@/hooks/useSessionMutation'
import { feedback } from '@/utils/feedback'

import { adminQueryKeys } from '../queryKeys'
import { describeUpdateResult, presentSkillStatus, shortRevision } from './presentation'

import styles from './index.module.scss'

/**
 * 检查更新需要访问技能仓库远端，耗时取决于网络，因此不自动查询：
 * 由管理员点击后才开始，避免页面一打开就长时间停在加载状态。
 */
export default function AdminSkillsPage() {
  const queryClient = useQueryClient()
  const [checking, setChecking] = useState(false)

  const statusQuery = useQuery({
    queryKey: adminQueryKeys.skills(),
    queryFn: getAdminSkillStatus,
    enabled: checking,
    retry: false
  })

  const refreshStatus = async () => {
    await queryClient.invalidateQueries({ queryKey: adminQueryKeys.skills() })
    setChecking(true)
  }

  const updateMutation = useSessionMutation({
    sessionKey: 'admin-skills-update',
    active: true,
    mutationFn: () => updateAdminSkills(),
    onSuccess: async (result) => {
      feedback.success(describeUpdateResult(result))
      await refreshStatus()
    }
  })

  const status = statusQuery.data
  const presented = presentSkillStatus(status)
  const loading = checking && statusQuery.isFetching && !status
  const unreachable = checking && statusQuery.isError

  return (
    <div className={styles.page}>
      <header className={styles.pageHeader}>
        <h2>AI 迁移技能</h2>
        <p>
          迁移代理按技能执行代码迁移。技能以 Git
          仓库管理，更新后从下一次迁移任务起生效，进行中的任务不受影响。
        </p>
      </header>

      <div className={styles.facts}>
        <div className={styles.fact}>
          <span className={styles.label}>当前版本</span>
          <Typography.Text
            className={styles.value}
            copyable={Boolean(status)}
            title={status?.currentRevision}
          >
            {shortRevision(status?.currentRevision)}
          </Typography.Text>
        </div>
        <div className={styles.fact}>
          <span className={styles.label}>远端最新版本</span>
          <Typography.Text
            className={styles.value}
            copyable={Boolean(status)}
            title={status?.remoteRevision}
          >
            {shortRevision(status?.remoteRevision)}
          </Typography.Text>
        </div>
        <div className={styles.fact}>
          <span className={styles.label}>已安装技能</span>
          <span className={styles.value}>
            {status?.skills.length ? status.skills.join('、') : '--'}
          </span>
        </div>
      </div>

      <div className={styles.status}>
        <p className={styles.summary} aria-live="polite">
          {loading
            ? '正在检查更新…'
            : unreachable
              ? '无法连接技能仓库，请检查网络或代理配置后重试'
              : status
                ? presented.summary
                : '尚未检查更新'}
        </p>
        <Flex gap={8}>
          {presented.hasUpdate ? <Tag color="processing">有可用更新</Tag> : null}
          <Button
            icon={<ReloadOutlined />}
            loading={statusQuery.isFetching}
            onClick={() => void refreshStatus()}
          >
            {status ? '重新检查' : '检查更新'}
          </Button>
          <Button
            disabled={!presented.hasUpdate || statusQuery.isFetching}
            icon={<CloudSyncOutlined />}
            loading={updateMutation.isPending}
            type="primary"
            onClick={() => updateMutation.mutate(undefined)}
          >
            更新到最新版本
          </Button>
        </Flex>
      </div>

      {presented.changedFiles.length > 0 ? (
        <section className={styles.changes}>
          <h3>更新将变更的文件</h3>
          <ul>
            {presented.changedFiles.map((file) => (
              <li key={file}>{file}</li>
            ))}
          </ul>
        </section>
      ) : null}
    </div>
  )
}
