import { RocketOutlined } from '@ant-design/icons'
import { Alert, Button, Flex } from 'antd'

import RecordViewTitle from '../components/RecordViewTitle'
import StageSurface from '../components/StageSurface'
import { getStageDrawerWidth } from '../drawerWidth'
import type { StageDrawerProps } from '../types'
import PatchView from './components/PatchView'
import { RunList } from './components/RunList'
import { useAiMigrationDrawer } from './useAiMigrationDrawer'

import styles from './index.module.scss'

export default function AiMigration({ projectId, visible, stage, onClose }: StageDrawerProps) {
  const drawer = useAiMigrationDrawer(projectId, visible)
  const patchView = drawer.view.type === 'diff' ? drawer.view : null

  return (
    <StageSurface
      afterOpenChange={(open) => {
        if (!open) drawer.showRuns()
      }}
      extra={
        patchView ? undefined : (
          <Flex align="center" gap={4}>
            <Button
              aria-label="刷新运行状态"
              loading={drawer.runsQuery.isFetching && !drawer.runsQuery.isLoading}
              onClick={() => void drawer.runsQuery.refetch()}
              type="text"
            >
              刷新
            </Button>
            <Button
              disabled={!drawer.scanReady || drawer.isCreating}
              icon={<RocketOutlined />}
              loading={drawer.isCreating}
              type="primary"
              onClick={drawer.createMigration}
            >
              发起迁移
            </Button>
          </Flex>
        )
      }
      size={getStageDrawerWidth(stage?.id)}
      title={
        patchView ? (
          <RecordViewTitle backLabel="返回迁移运行" title="迁移补丁" onBack={drawer.showRuns} />
        ) : (
          (stage?.title ?? 'AI 迁移')
        )
      }
      visible={visible}
      onClose={onClose}
    >
      <div className={styles.content}>
        {patchView ? (
          <PatchView
            error={drawer.diffQuery.isError}
            isLoading={drawer.diffQuery.isLoading}
            runId={patchView.runId}
            truncated={drawer.diffQuery.data?.truncated ?? false}
            value={drawer.diffQuery.data?.content ?? ''}
          />
        ) : (
          <>
            <div className={styles.intro}>
              <p>
                以知识库扫描发现的架构相关代码为输入，由迁移代理在隔离容器中逐条分析并改写为 RISC-V
                实现，结果以补丁形式给出，不会直接改动你的源码。
              </p>
              {!drawer.isScanLoading && !drawer.scanReady ? (
                <Alert
                  showIcon
                  type="info"
                  message="需要一次有效的知识库扫描"
                  description="AI 迁移以当前源码的扫描结果为输入，请先完成知识库扫描。"
                />
              ) : null}
            </div>
            <RunList
              isLoading={drawer.runsQuery.isLoading}
              runs={drawer.runs}
              onViewDiff={drawer.showDiff}
            />
          </>
        )}
      </div>
    </StageSurface>
  )
}
