import { useMemo } from 'react'
import { Button, Flex, Popconfirm } from 'antd'
import type { ColumnsType } from 'antd/es/table'

import type { ProjectAutomatedTest, ProjectTestComparison } from '@/types/project'

import { renderComparisonStatusTag, renderTestStatusTag } from '../presentation'
import TestHistory from './TestHistory'

import tableButtons from '@/styles/tableButtons.module.scss'

export default function TestRecords({
  tests,
  comparisons,
  projectId,
  onOpenBatch,
  onOpenTest,
  onCancel
}: {
  tests: ProjectAutomatedTest[]
  comparisons: ProjectTestComparison[]
  projectId?: string
  onOpenBatch: (id: string) => void
  onOpenTest: (test: ProjectAutomatedTest) => void
  onCancel: (id: string) => void
}) {
  const history = useMemo(() => {
    const batches = new Map<string, ProjectAutomatedTest[]>()
    tests.forEach((item) => {
      batches.set(item.testBatchId ?? item.id, [
        ...(batches.get(item.testBatchId ?? item.id) ?? []),
        item
      ])
    })
    return [...batches.entries()].map(([id, tests]) => ({
      id,
      tests,
      comparison: comparisons.find((item) => item.testBatchId === id)
    }))
  }, [comparisons, tests])
  const columns = useMemo<ColumnsType<(typeof history)[number]>>(
    () => [
      { title: '测试批次', dataIndex: 'id', ellipsis: true },
      {
        title: '源码版本',
        render: (_, row) => row.tests[0]?.sourceRevisionId ?? '--',
        ellipsis: true
      },
      {
        title: 'x86',
        width: 110,
        render: (_, row) =>
          renderTestStatusTag(row.tests.find((item) => item.testBatchRole === 'SOURCE')?.status)
      },
      {
        title: 'RISC-V',
        width: 110,
        render: (_, row) =>
          renderTestStatusTag(row.tests.find((item) => item.testBatchRole === 'TARGET')?.status)
      },
      {
        title: '比较',
        width: 130,
        render: (_, row) =>
          row.comparison
            ? renderComparisonStatusTag(row.comparison.overallStatus ?? row.comparison.status)
            : renderComparisonStatusTag()
      },
      {
        title: '操作',
        width: 100,
        render: (_, row) => (
          <Flex wrap gap={4}>
            <Button
              className={tableButtons.textButton}
              type="link"
              size="small"
              disabled={!row.tests[0]}
              onClick={() =>
                row.tests[0].testBatchId ? onOpenBatch(row.id) : onOpenTest(row.tests[0])
              }
            >
              查看
            </Button>
            {row.tests
              .filter((test) => ['PENDING', 'QUEUED', 'RUNNING'].includes(test.status))
              .map((test) => (
                <Popconfirm
                  key={test.id}
                  title="确认取消测试？"
                  onConfirm={() => onCancel(test.id)}
                >
                  <Button className={tableButtons.textButton} type="link" size="small">
                    取消 {test.testBatchRole === 'SOURCE' ? 'x86' : 'RISC-V'}
                  </Button>
                </Popconfirm>
              ))}
          </Flex>
        )
      }
    ],
    [onCancel, onOpenBatch, onOpenTest]
  )

  return <TestHistory columns={columns} data={history} projectId={projectId} />
}
