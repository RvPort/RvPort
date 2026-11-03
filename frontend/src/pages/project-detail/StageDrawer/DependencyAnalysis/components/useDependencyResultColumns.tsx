import { useMemo } from 'react'
import { Button, Tooltip } from 'antd'
import type { ColumnsType } from 'antd/es/table'

import type {
  ProjectDependencyAnalysis,
  ProjectDependencyAnalysisResult
} from '@/types/project/dependency-analysis'

import { projectQueryKeys } from '../../../queryKeys'
import {
  dependencyRiskTag,
  dependencyTargetMatchStatusTag,
  dependencyTypeLabel,
  packageLabel
} from '../../components/DependencyResultDetail/presentation'
import { MigrationInputActions } from '../../components/MigrationInputActions'
import { tooltipTextCell } from './presentation'

import styles from '../index.module.scss'
import tableButtons from '@/styles/tableButtons.module.scss'

export function useDependencyResultColumns(
  analysis: ProjectDependencyAnalysis,
  projectId: string,
  canModify: boolean,
  onOpenResult: (id: string) => void
) {
  const columns = useMemo<ColumnsType<ProjectDependencyAnalysisResult>>(() => {
    const resultColumns: ColumnsType<ProjectDependencyAnalysisResult> = [
      {
        title: '依赖',
        dataIndex: 'dependencyName',
        fixed: 'left',
        width: 180,
        ellipsis: { showTitle: false },
        render: (value, record) => (
          <Tooltip title={value}>
            <Button
              className={`${tableButtons.textButton} ${styles.dependencyLink}`}
              type="link"
              onClick={() => onOpenResult(record.id)}
            >
              {value}
            </Button>
          </Tooltip>
        )
      },
      {
        title: '风险',
        dataIndex: 'riskLevel',
        fixed: 'left',
        width: 88,
        render: dependencyRiskTag
      },
      {
        title: '匹配状态',
        dataIndex: 'targetMatchStatus',
        width: 148,
        render: dependencyTargetMatchStatusTag
      },
      {
        title: '类型',
        dataIndex: 'dependencyType',
        width: 104,
        render: dependencyTypeLabel
      },
      {
        title: '源提供包',
        width: 180,
        ellipsis: { showTitle: false },
        render: (_, record) =>
          tooltipTextCell(packageLabel(record.sourceProviderPackage, record.sourceProviderVersion))
      },
      {
        title: '目标提供包',
        width: 180,
        ellipsis: { showTitle: false },
        render: (_, record) =>
          tooltipTextCell(packageLabel(record.targetProviderPackage, record.targetProviderVersion))
      },
      {
        title: '建议',
        dataIndex: 'recommendation',
        width: 240,
        ellipsis: { showTitle: false },
        render: tooltipTextCell
      }
    ]
    if (canModify) {
      resultColumns.push({
        title: '操作',
        fixed: 'right',
        width: 120,
        render: (_, record) => (
          <MigrationInputActions
            initialStatus={record.migrationInputStatus ?? undefined}
            invalidateQueryKeys={[
              projectQueryKeys.dependencyAnalysisResult(record.id),
              projectQueryKeys.dependencyAnalysisResults(analysis.id)
            ]}
            migrationInputRefId={record.migrationInputRefId}
            projectId={projectId}
            sourceRefId={record.id}
            sourceRevisionId={analysis.sourceRevisionId}
            sourceType="DEPENDENCY_RESULT"
          />
        )
      })
    }
    return resultColumns
  }, [analysis.id, analysis.sourceRevisionId, canModify, onOpenResult, projectId])
  return columns
}
