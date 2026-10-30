import type { ReactNode } from 'react'

import type { ProjectWorkflowStatus } from '@/types/project'

import { getStageStatusStyleVars, getStageStatusText } from '../stageStatus'
import { getWorkflowStageData } from './stageData'
import { workflowGroups } from './topology'

import styles from './StepNavigation.module.scss'

const navigationGroups = [
  { id: 'project', title: '项目', stageIds: ['project'] },
  ...workflowGroups
]

interface StepNavigationProps {
  viewSwitcher?: ReactNode
  workflowStatus?: ProjectWorkflowStatus
  selectedStageId: string
  onStageClick: (stageId: string) => void
}

export default function StepNavigation({
  viewSwitcher,
  workflowStatus,
  selectedStageId,
  onStageClick
}: StepNavigationProps) {
  return (
    <nav aria-label="阶段导航" className={styles.navigation}>
      <div className={styles.heading}>
        <div className={styles.headingTop}>
          <strong>迁移流程</strong>
          <div className={styles.viewControl}>{viewSwitcher}</div>
        </div>
      </div>
      <div className={styles.flow}>
        {navigationGroups.map((group) => (
          <section key={group.id} aria-label={group.title} className={styles.group}>
            <h2>{group.title}</h2>
            <ol>
              {group.stageIds.map((id) => {
                const stage = getWorkflowStageData(id, workflowStatus)
                if (!stage) return null

                return (
                  <li key={id} style={getStageStatusStyleVars(stage.status)}>
                    <button
                      aria-current={id === selectedStageId ? 'step' : undefined}
                      type="button"
                      onClick={() => onStageClick(id)}
                    >
                      <span aria-hidden="true" className={styles.node} />
                      <span className={styles.copy}>
                        <strong>{stage.title}</strong>
                        {stage.analysisWarning ? (
                          <small role="status">{stage.analysisWarning}</small>
                        ) : null}
                        {stage.assistText ? <small>{stage.assistText}</small> : null}
                      </span>
                      <span className={styles.status}>{getStageStatusText(stage.status)}</span>
                    </button>
                  </li>
                )
              })}
            </ol>
          </section>
        ))}
      </div>
    </nav>
  )
}
