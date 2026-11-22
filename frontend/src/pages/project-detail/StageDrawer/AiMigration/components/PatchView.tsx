import { DownloadOutlined } from '@ant-design/icons'
import { Button, Flex, Tag, Tooltip } from 'antd'

import { getAiMigrationArtifactDownloadUrl } from '@/api/project/ai-migration'
import ContentState from '@/components/ContentState'
import MonacoEditor from '@/components/MonacoEditor'

import styles from '../index.module.scss'

/** 迁移补丁视图：元信息一行加只读差分正文；空补丁与加载失败各有明确说明。 */
export default function PatchView({
  error,
  isLoading,
  runId,
  truncated,
  value
}: {
  error: boolean
  isLoading: boolean
  runId: string
  truncated: boolean
  value: string
}) {
  if (isLoading) {
    return <ContentState compact description="正在加载补丁" kind="loading" />
  }
  if (error) {
    return (
      <ContentState
        compact
        description="补丁加载失败"
        details="请稍后重试，或直接下载补丁文件。"
        kind="error"
      >
        <Button href={getAiMigrationArtifactDownloadUrl(runId, 'diff')} icon={<DownloadOutlined />}>
          下载补丁文件
        </Button>
      </ContentState>
    )
  }
  if (!value.trim()) {
    return (
      <ContentState
        compact
        description="本次运行没有产生代码改动"
        details="所有条目都被判定为无需迁移或待人工确认，可以下载逐条目结论查看依据。"
      />
    )
  }
  return (
    <Flex className={styles.patchView} gap={8} vertical>
      <Flex align="center" className={styles.patchMeta} gap={8} justify="space-between" wrap>
        <span>
          统一差分格式，可在源码根目录用 <code>git apply --check</code> 校验后应用。
          {truncated ? '内容过长已截断，完整内容请下载。' : ''}
        </span>
        <Tooltip title="下载补丁文件（.patch）">
          <Button
            aria-label="下载补丁文件"
            href={getAiMigrationArtifactDownloadUrl(runId, 'diff')}
            icon={<DownloadOutlined />}
            size="small"
          >
            下载
          </Button>
        </Tooltip>
      </Flex>
      {truncated ? <Tag color="warning">内容过长，页面内仅显示前一部分</Tag> : null}
      <MonacoEditor
        ariaLabel="迁移补丁内容"
        className={styles.patch}
        language="diff"
        readOnly
        value={value}
        wordWrap="off"
      />
    </Flex>
  )
}
