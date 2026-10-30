import { Button, Tag } from 'antd'
import type { ColumnsType } from 'antd/es/table'

import { getSpecializedFindings } from '@/api/project/specialized-analysis'
import BaseTable from '@/components/BaseTable'
import type {
  SpecializedAnalysis,
  SpecializedAnalysisStageId,
  SpecializedFinding
} from '@/types/project/specialized-analysis'
import { isPartialAnalysis } from '@/utils/analysisResult'

import { projectQueryKeys } from '../../../queryKeys'
import { presentFinding, severityColors, severityLabels } from '../presentation'

import tableButtons from '@/styles/tableButtons.module.scss'

export default function FindingList({
  run,
  projectId,
  stageId,
  search,
  severity,
  onFiltersChange,
  onSelect
}: {
  run: SpecializedAnalysis
  projectId: string
  stageId: SpecializedAnalysisStageId
  search: string
  severity: string
  onFiltersChange: (search: string, severity: string) => void
  onSelect: (id: string) => void
}) {
  const memoryOrder = stageId === 'memory-order-analysis'
  const columns: ColumnsType<SpecializedFinding> = [
    {
      title: stageId === 'memory-order-analysis' ? '分析结论' : '风险',
      width: 260,
      ellipsis: true,
      render: (_, raw) => (
        <Button className={tableButtons.textButton} type="link" onClick={() => onSelect(raw.id)}>
          {presentFinding(raw).title}
        </Button>
      )
    },
    {
      title: '等级',
      width: 100,
      render: (_, raw) => {
        const finding = presentFinding(raw)
        return (
          <Tag color={severityColors[finding.severity]}>{severityLabels[finding.severity]}</Tag>
        )
      }
    },
    {
      title: '文件',
      dataIndex: 'filePath',
      width: 250,
      ellipsis: true,
      render: (value) => value || '--'
    },
    { title: '行号', dataIndex: 'startLine', width: 80, render: (value) => value ?? '--' },
    { title: '说明', width: 300, ellipsis: true, render: (_, raw) => presentFinding(raw).impact },
    { title: '置信度', width: 120, render: (_, raw) => presentFinding(raw).confidence },
    {
      title: stageId === 'memory-order-analysis' ? '结论类型' : '状态',
      width: 150,
      render: (_, raw) => {
        const finding = presentFinding(raw)
        return finding.memoryOrder
          ? (
              {
                FINDING: '正式风险',
                SOURCE_CANDIDATE: '源码候选 · 待核实',
                INCONCLUSIVE: '证据不足 · 待核实',
                DISCHARGED: '受限模型内已消解'
              } as Record<string, string>
            )[finding.findingKind]
          : finding.resultStatus
      }
    },
    {
      title: '操作',
      fixed: 'right',
      width: 90,
      render: (_, raw) => (
        <Button className={tableButtons.textButton} type="link" onClick={() => onSelect(raw.id)}>
          查看
        </Button>
      )
    }
  ]
  return (
    <BaseTable<SpecializedFinding, { keyword?: string; severity?: string }>
      autoHeight
      columns={columns}
      emptyPresentation="table"
      emptyDescription={
        isPartialAnalysis(run)
          ? '暂无可展示结果，分析未完成；零正式风险不代表迁移安全'
          : '本次分析范围内未发现支持的风险'
      }
      filteredEmptyDescription="没有符合筛选条件的风险"
      errorDescription="分析结果加载失败"
      pagination={{ initialPageSize: 20 }}
      queryKey={
        memoryOrder
          ? [
              ...projectQueryKeys.specializedFindings(projectId, stageId, run.id),
              'display-classification'
            ]
          : projectQueryKeys.specializedFindings(projectId, stageId, run.id)
      }
      request={(query) =>
        getSpecializedFindings(projectId, stageId, run.id, {
          page: query.page,
          pageSize: query.pageSize,
          keyword: query.keyword?.trim() || undefined,
          severity: memoryOrder ? undefined : query.severity
        })
      }
      rowKey="id"
      size="small"
      scroll={{ x: 1200 }}
      tableLayout="fixed"
      toolbar={{
        filters: {
          initialValues: {
            keyword: search || undefined,
            severity: memoryOrder || severity === 'all' ? undefined : severity.toUpperCase()
          },
          onChange: (values) =>
            onFiltersChange(
              values.keyword ?? '',
              memoryOrder ? 'all' : (values.severity?.toLowerCase() ?? 'all')
            ),
          fields: [
            {
              name: 'keyword',
              type: 'search',
              placeholder: '搜索风险、文件或规则',
              ariaLabel: '搜索风险'
            },
            ...(!memoryOrder
              ? [
                  {
                    name: 'severity' as const,
                    type: 'select' as const,
                    placeholder: '风险等级',
                    ariaLabel: '风险等级',
                    options: Object.entries(severityLabels)
                      .filter(([key]) => key !== 'unknown')
                      .map(([value, label]) => ({ value: value.toUpperCase(), label }))
                  }
                ]
              : [])
          ]
        }
      }}
    />
  )
}
