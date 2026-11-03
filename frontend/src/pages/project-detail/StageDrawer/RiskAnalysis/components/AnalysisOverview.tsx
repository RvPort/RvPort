import { Fragment } from 'react'
import { Alert, Button, Progress, Space } from 'antd'

import ContentState from '@/components/ContentState'
import type { SpecializedAnalysisStageId } from '@/types/project/specialized-analysis'
import { analysisDiagnostics, isPartialAnalysis } from '@/utils/analysisResult'
import { formatDateTime } from '@/utils/display'

import StageOverview from '../../components/StageOverview'
import StageStateNotice from '../../components/StageStateNotice'
import { TaskLogPanel } from '../../components/TaskLogPanel'
import { presentPrerequisites } from '../../stagePresentation'
import type { StageDrawerProps } from '../../types'
import { analysisTaskStatus, isAnalysisActive, statusLabels } from '../presentation'
import type { useRiskAnalysis } from '../useRiskAnalysis'
import FindingList from './FindingList'
import ResultSummary from './ResultSummary'

import styles from '../index.module.scss'
export default function AnalysisOverview({
  state,
  stageId,
  projectId,
  onStageChange
}: {
  state: ReturnType<typeof useRiskAnalysis>
  stageId: SpecializedAnalysisStageId
  projectId?: string
  onStageChange?: StageDrawerProps['onStageChange']
}) {
  const { run, readiness, latest } = state
  const processing = Boolean(run && isAnalysisActive(run.status))
  const analysisLabel = stageId === 'memory-order-analysis' ? '强弱内存序分析' : '浮点精度扫描'
  const prerequisites = (readiness.data?.prerequisites ?? []).map((item) =>
    state.buildPrerequisiteRejected && item.key === 'X86_BUILD'
      ? { ...item, status: 'UNAVAILABLE' as const, reasonCode: 'X86_BUILD_ARTIFACTS_REQUIRED' }
      : item
  )
  const blocked = prerequisites.filter((item) => item.status !== 'SATISFIED')
  const stale =
    run && (!run.currentSourceRevision || (!state.runId && latest.data?.freshness === 'STALE'))
  const failed = run && ['FAILED', 'CANCELLED', 'TIMEOUT'].includes(run.status)
  const summary =
    run && isPartialAnalysis(run)
      ? run.analyzerType === 'MEMORY_ORDER'
        ? '部分范围未完成，零正式风险不代表迁移安全。'
        : '部分分析完成：部分范围未完成，请结合诊断和原始结果判断；零正式风险不代表迁移安全。'
      : failed
        ? (typeof run?.summary.error === 'string'
            ? run.summary.error
            : !state.runId
              ? latest.data?.execution?.errorMessage
              : null) || '本次执行未完成，请查看任务日志后重试。'
        : run?.analyzerType === 'MEMORY_ORDER'
          ? '结论限于本次分析的证据和模型范围，零正式风险不代表迁移安全。'
          : run
            ? `${run.findingCount === 0 && run.candidateCount === 0 ? '本次分析范围内未发现支持的风险。' : `${run.findingCount} 项分析结果，${run.candidateCount} 项源码候选，${analysisDiagnostics(run).count ?? '未知'} 项诊断。`}结果仍需结合源码与业务条件确认，零正式风险不代表迁移安全。`
            : ''
  const loading = readiness.isLoading || latest.isLoading
  const requestError = readiness.isError || latest.isError
  const prerequisiteItems = presentPrerequisites(prerequisites, onStageChange)
  const startAction = {
    label: '启动分析',
    disabled: !state.canStart,
    loading: state.create.isPending,
    onClick: () => state.create.mutate()
  }
  const reload = <Button onClick={() => void state.refresh()}>重新加载</Button>
  const content = (
    <div className={styles.content}>
      <section className={styles.overview}>
        {!loading && !requestError && !blocked.length && !run && !state.runId ? (
          <StageStateNotice
            className={styles.initialState}
            variant={state.creationError ? 'empty' : 'ready'}
            title={state.creationError ? '尚无分析记录' : '已满足分析条件'}
            description="当前尚无分析记录，可以执行第一次分析。"
            prerequisites={state.creationError ? undefined : prerequisiteItems}
            primaryAction={startAction}
          />
        ) : null}
        {run && !processing && !failed && !state.runId ? (
          <div className={styles.toolbar}>
            <h3>分析概览</h3>
            <Space>
              <Button
                disabled={!state.canStart}
                loading={state.create.isPending}
                onClick={() => state.create.mutate()}
              >
                重新分析
              </Button>
            </Space>
          </div>
        ) : null}
        {state.runId && state.selectedRun.isLoading ? (
          <ContentState kind="loading" description="正在加载分析记录" compact />
        ) : null}
        {state.runId && state.selectedRun.isError ? (
          <ContentState kind="error" description="分析记录加载失败" compact>
            {reload}
          </ContentState>
        ) : null}
        {stale ? (
          <Alert
            showIcon
            type="warning"
            title="该分析基于旧源码"
            description={run?.staleReasons.join('、') || '请针对当前源码重新分析。'}
          />
        ) : null}
        {run && processing ? (
          <StageStateNotice
            variant="processing"
            title={
              run.status === 'RUNNING' ? `${analysisLabel}正在执行` : `${analysisLabel}等待执行`
            }
            description={
              !state.runId && latest.data?.execution?.message
                ? latest.data.execution.message
                : '任务正在后台执行，完成后将展示最新分析结果。'
            }
            details={
              <div className={styles.executionDetails}>
                <span>开始时间：{formatDateTime(run.startedAt)}</span>
                {!state.runId && typeof latest.data?.execution?.progress === 'number' ? (
                  <Progress percent={latest.data.execution.progress} status="active" />
                ) : null}
              </div>
            }
            secondaryActions={[
              ...(!state.runId
                ? [
                    {
                      label: '取消分析',
                      disabled: state.cancel.isPending,
                      loading: state.cancel.isPending,
                      onClick: () => state.cancel.mutate(),
                      confirmation: {
                        title: '取消本次分析？',
                        okText: '确认取消',
                        cancelText: '继续分析'
                      }
                    },
                    {
                      label: '查看历史记录',
                      type: 'link' as const,
                      onClick: state.openHistory
                    }
                  ]
                : []),
              {
                label: state.showLogs ? '收起日志' : '查看日志',
                type: 'link',
                onClick: () => state.setShowLogs(!state.showLogs)
              }
            ]}
          />
        ) : null}
        {run && !processing ? (
          failed ? (
            <StageStateNotice
              title={statusLabels[run.status]}
              variant="execution-error"
              description={summary}
              details={
                <Fragment>
                  <span>完成时间：{formatDateTime(run.finishedAt)}</span>
                  {(run.diagnosticCount ?? 0) > 0 ? (
                    <p>{run.diagnosticCount} 项分析诊断，请查看日志。</p>
                  ) : null}
                </Fragment>
              }
              primaryAction={
                !state.runId && state.canStart ? { ...startAction, label: '重新分析' } : undefined
              }
              secondaryActions={[
                {
                  label: state.showLogs ? '收起日志' : '查看日志',
                  type: 'link',
                  onClick: () => state.setShowLogs(!state.showLogs)
                },
                ...(!state.runId
                  ? [{ label: '查看历史记录', type: 'link' as const, onClick: state.openHistory }]
                  : [])
              ]}
            />
          ) : (
            <ResultSummary
              run={run}
              projectId={projectId}
              stageId={stageId}
              summary={summary}
              showLogs={state.showLogs}
              onToggleLogs={() => state.setShowLogs(!state.showLogs)}
              onOpenDiagnostics={state.openDiagnostics}
            />
          )
        ) : null}
      </section>
      {run && state.showLogs ? (
        <TaskLogPanel
          key={run.taskId}
          taskId={run.taskId}
          status={analysisTaskStatus(run.status)}
          title="任务日志"
          ariaLabel="专项分析任务日志"
          className={styles.logPanel}
          editorClassName={styles.logEditor}
          onTaskCompleted={() => void state.refresh()}
        />
      ) : null}
      {run && projectId && !processing && !failed ? (
        <section>
          <div className={styles.listHeading}>
            <h3>结果列表</h3>
          </div>
          <FindingList
            run={run}
            projectId={projectId}
            stageId={stageId}
            search={state.search}
            severity={state.severity}
            onFiltersChange={(search, severity) => {
              state.setSearch(search)
              state.setSeverity(severity)
            }}
            onSelect={state.setFindingId}
          />
        </section>
      ) : null}
    </div>
  )
  if (state.runId) return content
  return (
    <Fragment>
      {state.creationError && !state.buildPrerequisiteRejected ? (
        <Alert showIcon type="error" title="启动分析失败" description={state.creationError} />
      ) : null}
      <StageOverview
        label={analysisLabel}

        request={{
          loading,
          error: requestError,
          hasData: Boolean(readiness.data && latest.data),
          reload: () => void state.refresh()
        }}

        gate={{
          blockedDescription: state.buildPrerequisiteRejected ? state.creationError : undefined,
          ready: !blocked.length && Boolean(prerequisites.length),
          prerequisites: prerequisiteItems
        }}
        result={{
          record: run,
          execution: latest.data?.execution,
          status: latest.data?.execution?.status ?? run?.status,
          freshness: latest.data?.freshness,
          hasResult: Boolean(run)
        }}
        operation={{
          runningActions: [
            {
              label: '取消分析',
              loading: state.cancel.isPending,
              onClick: () => state.cancel.mutate(),
              confirmation: { title: '取消本次分析？', okText: '确认取消', cancelText: '继续分析' }
            }
          ],
          submitting: state.create.isPending || state.submitted,
          start: startAction
        }}
      >
        {content}
      </StageOverview>
    </Fragment>
  )
}
