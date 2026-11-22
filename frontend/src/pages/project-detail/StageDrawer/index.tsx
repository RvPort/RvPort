import { lazy, Suspense, useEffect, useState, useSyncExternalStore } from 'react'
import type { ComponentType, LazyExoticComponent } from 'react'

import { getAuthGeneration, subscribeAuthSession } from '@/store/authSession'

import { StagePresentationContext } from './components/StagePresentationContext'
import StageViewLoading from './components/StageViewLoading'
import Default from './Default'
import { getStageDrawerWidth } from './drawerWidth'
import { loadEnvironmentStage } from './stageLoaders'
import type { StageDrawerProps } from './types'

import styles from './loading.module.scss'

type StageDrawerComponent =
  ComponentType<StageDrawerProps> | LazyExoticComponent<ComponentType<StageDrawerProps>>

const stageDrawerById: Record<string, StageDrawerComponent> = {
  'ai-migration': lazy(() => import('./AiMigration')),
  assets: lazy(() => import('./Assets')),
  archive: lazy(() => import('./Archive')),
  build: lazy(() => import('./Build')),
  env: lazy(loadEnvironmentStage),
  kb: lazy(() => import('./KnowledgeBase')),
  'memory-order-analysis': lazy(() => import('./RiskAnalysis')),
  'floating-point-analysis': lazy(() => import('./RiskAnalysis')),
  manual: lazy(() => import('./ManualRepair')),
  optimize: lazy(() => import('./OptimizationCycle')),
  project: lazy(() => import('./Project')),
  report: lazy(() => import('./AssessmentReport')),
  'rv-dependency-analysis': lazy(() => import('./DependencyAnalysis')),
  test: lazy(() => import('./AutomatedTest')),
  'x86-build': lazy(() => import('./X86Build'))
}

function StageDrawerLoading({
  stage,
  visible,
  onClose
}: Pick<StageDrawerProps, 'stage' | 'visible' | 'onClose'>) {
  useEffect(() => {
    if (!visible) return
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [onClose, visible])

  if (!visible) return null

  return (
    <div className={styles.loadingLayer}>
      <button
        aria-label="点击遮罩关闭阶段抽屉"
        className={styles.loadingMask}
        type="button"
        onClick={onClose}
      />
      <aside
        aria-busy="true"
        aria-label={`正在打开${stage?.title ?? '阶段'}`}
        className={styles.loadingDrawer}
        style={{ width: getStageDrawerWidth(stage?.id) }}
      >
        <header className={styles.loadingHeader}>
          <div>
            <strong>{stage?.title ?? '项目阶段'}</strong>
            {stage?.subtitle ? <span>{stage.subtitle}</span> : null}
          </div>
          <button aria-label="关闭阶段抽屉" type="button" onClick={onClose}>
            <span aria-hidden="true">×</span>
          </button>
        </header>
        <div className={styles.loadingBody} role="status">
          <div aria-hidden="true" className={styles.loadingPipeline}>
            <span />
            <span />
            <span />
          </div>
          <div>
            <strong>正在打开阶段内容</strong>
            <span>加载当前状态与操作</span>
          </div>
          <div aria-hidden="true" className={styles.loadingSkeleton}>
            <span />
            <span />
          </div>
        </div>
      </aside>
    </div>
  )
}

export default function StageDrawer({
  projectId,
  visible,
  presentation = 'drawer',
  stage,
  onClose,
  onStageChange,
  onDirtyChange
}: StageDrawerProps) {
  const generation = useSyncExternalStore(
    subscribeAuthSession,
    getAuthGeneration,
    getAuthGeneration
  )
  const [retainedStage, setRetainedStage] = useState(stage)
  if (stage && stage !== retainedStage) setRetainedStage(stage)
  const renderedStage = stage ?? retainedStage
  const Stage = renderedStage ? (stageDrawerById[renderedStage.id] ?? Default) : Default

  return (
    <Suspense
      fallback={
        presentation === 'panel' ? (
          <StageViewLoading
            description={`正在加载${renderedStage?.title ?? '阶段内容'}`}
            variant="detail"
          />
        ) : (
          <StageDrawerLoading stage={renderedStage} visible={visible} onClose={onClose} />
        )
      }
    >
      <StagePresentationContext.Provider value={presentation}>
        <Stage
          key={`${generation}:${projectId}:${renderedStage?.id}`}
          projectId={projectId}
          visible={visible}
          presentation={presentation}
          stage={renderedStage}
          onClose={onClose}
          onStageChange={onStageChange}
          onDirtyChange={onDirtyChange}
        />
      </StagePresentationContext.Provider>
    </Suspense>
  )
}
