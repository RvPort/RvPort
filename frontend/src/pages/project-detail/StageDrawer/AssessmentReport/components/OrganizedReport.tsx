import { CheckCircleOutlined } from '@ant-design/icons'
import { Tag } from 'antd'

import { useTableViewState } from '@/components/BaseTable/TableViewStateContext'
import { formatDateTime } from '@/utils/display'

import { projectQueryKeys } from '../../../queryKeys'
import { reportRiskText, reportRiskToken } from '../reportPresentation'
import MemoryOrderReportNotice from './MemoryOrderReportNotice'
import { ReportActions, SummaryFact } from './report-list-presentation'
import ReportDependencyList from './ReportDependencyList'
import ReportFileDetail from './ReportFileDetail'
import ReportModuleDetail from './ReportModuleDetail'
import ReportModuleIndex from './ReportModuleIndex'
import type { OrganizedReportProps } from './types'

import styles from '../index.module.scss'

export default function OrganizedReport({
  canGenerate,
  isCreating,
  report,
  selectedFile,
  selectedModule,
  onCreate,
  onDownload,
  onOpenIssue,
  onSelectFile,
  onSelectModule
}: OrganizedReportProps) {
  const [dependencyOpen, updateDependencyOpen] = useTableViewState(
    projectQueryKeys.evaluationReportListSectionState(report.id),
    false
  )
  const setDependencyOpen = (open: boolean) => updateDependencyOpen(() => open)
  const { summary } = report
  const dependencyFindingCount = summary.dependencyAnalysisInputCount ?? 0

  if (selectedFile && selectedModule) {
    return (
      <div className={`${styles.organizedReport} ${styles.fileDetailLayout}`}>
        <ReportFileDetail file={selectedFile} module={selectedModule} reportId={report.id} />
      </div>
    )
  }

  if (selectedModule) {
    return (
      <div className={styles.organizedReport}>
        <ReportModuleDetail
          module={selectedModule}
          reportId={report.id}
          onSelectFile={onSelectFile}
        />
      </div>
    )
  }

  return (
    <div className={styles.organizedReport}>
      <section className={styles.organizedSummary}>
        <div className={styles.reportToolbar}>
          <div className={styles.reportTitleBlock}>
            <h3>报告概览</h3>
            <div className={styles.reportIdentity}>
              <Tag color="success" icon={<CheckCircleOutlined />}>
                报告已生成
              </Tag>
              <span>生成于 {formatDateTime(report.generatedAt)}</span>
            </div>
          </div>
          <ReportActions
            canGenerate={canGenerate}
            isCreating={isCreating}
            onCreate={onCreate}
            onDownload={onDownload}
          />
        </div>
        <div className={styles.reportNarrative}>
          <div>
            <span>总体风险</span>
            <strong className={styles[`risk${reportRiskToken(report.riskLevel)}`]}>
              {reportRiskText(report.riskLevel)}
            </strong>
          </div>
          <div className={styles.summaryFacts}>
            <SummaryFact primary label="代码问题" value={`${summary.codeFindingCount ?? 0} 处`} />
            <SummaryFact label="代码模块" value={`${summary.codeModuleCount ?? 0} 个`} />
            <SummaryFact label="影响文件" value={`${summary.codeFileCount ?? 0} 个`} />
            <SummaryFact label="依赖问题" value={`${dependencyFindingCount} 项`} />
            <SummaryFact label="高风险问题" value={`${summary.highRiskIssueCount ?? 0} 处`} />
          </div>
        </div>
      </section>
      <MemoryOrderReportNotice summary={summary} />
      {(summary.codeModuleCount ?? 0) > 0 ? (
        <ReportModuleIndex reportId={report.id} onSelect={onSelectModule} />
      ) : null}
      {(summary.dependencyAnalysisInputCount ?? 0) > 0 ? (
        <details
          className={styles.supportingSection}
          open={dependencyOpen}
          onToggle={(event) => setDependencyOpen(event.currentTarget.open)}
        >
          <summary>
            <span>
              <strong>依赖清单</strong>
              <small>查看不归属于具体代码模块的依赖分析结果</small>
            </span>
            <span>{summary.dependencyAnalysisInputCount} 项 · 展开详情</span>
          </summary>
          {dependencyOpen ? (
            <ReportDependencyList reportId={report.id} onOpenIssue={onOpenIssue} />
          ) : null}
        </details>
      ) : null}
    </div>
  )
}
