import { Fragment } from 'react'
import { ReloadOutlined } from '@ant-design/icons'
import { Alert, Button, Tooltip } from 'antd'

import ContentState from '@/components/ContentState'
import type { SpecializedAnalysisStageId } from '@/types/project/specialized-analysis'

import { projectQueryKeys } from '../../queryKeys'
import { MigrationInputDetailFooter } from '../components/MigrationInputActions'
import RecordViewTitle from '../components/RecordViewTitle'
import StageSurface from '../components/StageSurface'
import { getStageDrawerWidth } from '../drawerWidth'
import type { StageDrawerProps } from '../types'
import AnalysisDiagnostics from './components/AnalysisDiagnostics'
import AnalysisHistory from './components/AnalysisHistory'
import AnalysisOverview from './components/AnalysisOverview'
import FindingDetail from './components/FindingDetail'
import { presentFinding } from './presentation'
import { useRiskAnalysis } from './useRiskAnalysis'

import stageStyles from '../index.module.scss'

function AnalysisContent({ stage, projectId, visible, onClose, onStageChange }: StageDrawerProps) {
  const stageId = stage!.id as SpecializedAnalysisStageId
  const state = useRiskAnalysis(projectId, stageId, visible)
  const { run, readiness, finding } = state
  const detail = finding.data ? presentFinding(finding.data) : undefined
  const sourceIsCurrent =
    run?.currentSourceRevision &&
    run.sourceRevisionId === readiness.data?.context.sourceRevisionId &&
    !readiness.isFetching &&
    !readiness.isError &&
    !finding.isError &&
    finding.data?.runId === run?.id
  return (
    <StageSurface
      onHistory={
        !state.runId && !state.findingId && !state.showHistory && !state.diagnosticRunId
          ? state.openHistory
          : undefined
      }
      extra={
        !state.findingId && !state.diagnosticRunId ? (
          <Tooltip title="刷新分析信息">
            <Button
              aria-label="刷新分析信息"
              icon={<ReloadOutlined />}
              loading={state.latest.isFetching || readiness.isFetching}
              onClick={() => void state.refresh()}
              type="text"
            />
          </Tooltip>
        ) : undefined
      }
      visible={visible}
      size={getStageDrawerWidth(stageId)}
      onClose={onClose}
      panelTitleVisible={Boolean(
        state.diagnosticRunId || state.findingId || state.showHistory || state.runId
      )}
      panelScrollKey={
        state.diagnosticRunId
          ? `diagnostics:${state.diagnosticRunId}`
          : state.showHistory
            ? 'history'
            : (state.findingId ?? run?.id ?? 'latest')
      }
      title={
        state.diagnosticRunId ? (
          <RecordViewTitle
            backLabel="返回分析概览"
            title="分析诊断明细"
            onBack={state.closeDiagnostics}
          />
        ) : state.findingId ? (
          <RecordViewTitle
            backLabel="返回风险列表"
            title={detail?.title ?? '风险详情'}
            onBack={() => state.setFindingId(undefined)}
          />
        ) : state.showHistory ? (
          <RecordViewTitle
            backLabel="返回最新结果"
            title="分析记录"
            onBack={() => state.selectRun(undefined)}
          />
        ) : state.runId ? (
          <RecordViewTitle
            backLabel="返回历史记录"
            title="历史分析详情"
            onBack={state.openHistory}
          />
        ) : (
          <span className={stageStyles.drawerTitle}>
            <span className={stageStyles.drawerTitleText}>{stage?.title}</span>
          </span>
        )
      }
    >
      {state.diagnosticRunId ? (
        state.selectedRun.isLoading ? (
          <ContentState kind="loading" description="正在加载分析诊断" />
        ) : state.selectedRun.isError || !run ? (
          <ContentState kind="error" description="分析诊断加载失败">
            <Button onClick={() => void state.selectedRun.refetch()}>重新加载</Button>
          </ContentState>
        ) : (
          <AnalysisDiagnostics run={run} projectId={projectId} stageId={stageId} />
        )
      ) : state.showHistory && projectId ? (
        <AnalysisHistory projectId={projectId} stageId={stageId} onSelect={state.selectRun} />
      ) : state.findingId ? (
        <Fragment>
          {finding.isLoading ? (
            <ContentState kind="loading" description="正在加载风险详情" />
          ) : finding.isError || !detail ? (
            <ContentState kind="error" description="风险详情加载失败">
              <Button onClick={() => void finding.refetch()}>重新加载</Button>
            </ContentState>
          ) : (
            <FindingDetail
              finding={detail}
              source={
                finding.data?.codeContext || finding.data?.codeContextUnavailableReason
                  ? {
                      data: finding.data.codeContext ?? undefined,
                      expectedSourceRevisionId:
                        run && finding.data.runId === run.id ? run.sourceRevisionId : undefined,
                      unavailableReason: finding.data.codeContextUnavailableReason ?? undefined
                    }
                  : undefined
              }
              reportActions={
                finding.data &&
                run &&
                projectId &&
                !state.runId &&
                sourceIsCurrent &&
                ['COMPLETE', 'COMPLETE_WITH_RISK', 'PARTIAL'].includes(run.status) ? (
                  <MigrationInputDetailFooter
                    key={finding.data.id}
                    projectId={projectId}
                    sourceRevisionId={run.sourceRevisionId}
                    sourceRefId={finding.data.id}
                    sourceType={
                      stageId === 'memory-order-analysis'
                        ? 'MEMORY_ORDER_FINDING'
                        : 'FLOATING_POINT_FINDING'
                    }
                    migrationInputRefId={finding.data.reportInclusion.migrationInputRefId}
                    status={finding.data.reportInclusion.migrationInputStatus}
                    pendingVerification={detail.memoryOrder && detail.findingKind !== 'FINDING'}
                    invalidateQueryKeys={[
                      projectQueryKeys.specializedAnalyses(projectId, stageId),
                      projectQueryKeys.evaluationReports(projectId)
                    ]}
                  />
                ) : null
              }
            />
          )}

          {finding.data && (state.runId || !sourceIsCurrent) ? (
            <Alert type="warning" showIcon title="历史源码结果仅供查看，不能纳入当前源码报告。" />
          ) : null}
        </Fragment>
      ) : (
        <AnalysisOverview
          state={state}
          stageId={stageId}
          projectId={projectId}
          onStageChange={onStageChange}
        />
      )}
    </StageSurface>
  )
}
export default function RiskAnalysis(props: StageDrawerProps) {
  return props.visible && props.stage ? (
    <AnalysisContent key={`${props.projectId}:${props.stage.id}`} {...props} />
  ) : null
}
