import {
  PROJECT_PANEL_IDS,
  type ProjectPanelId,
  type ProjectStageDetailStatus
} from '@/types/project'

export type WorkflowStageStatus = ProjectStageDetailStatus

export const WORKFLOW_STAGE_IDS = PROJECT_PANEL_IDS

export type WorkflowStageId = ProjectPanelId

export type WorkflowGroupTone = 'setup' | 'analysis' | 'assessment' | 'delivery'

export interface WorkflowStageDefinition {
  id: string
  order: number
  title: string
  status: WorkflowStageStatus
  prerequisiteIds: string[]
  position: {
    x: number
    y: number
  }
}

export interface WorkflowGroupDefinition {
  id: string
  title: string
  tone: WorkflowGroupTone
  stageIds: string[]
  padding?: Partial<WorkflowGroupPadding>
}

export interface WorkflowEdgeDefinition {
  id: string
  source: string
  target: string
  feedback?: boolean
  label?: string
  sourceHandle?: string
  targetHandle?: string
}

export interface WorkflowGatewayDefinition {
  id: string
  prerequisiteIds: string[]
  targetId: string
  position: {
    x: number
    y: number
  }
}

export interface WorkflowGroupPadding {
  left: number
  top: number
  right: number
  bottom: number
}

export const WORKFLOW_STAGE_WIDTH = 176
export const WORKFLOW_STAGE_HEIGHT = 72

export const WORKFLOW_GROUP_PADDING: WorkflowGroupPadding = {
  left: 32,
  top: 50,
  right: 40,
  bottom: 50
}

// Specialized analysis shares the x86 build input and remains optional for report generation.
export const workflowStages: WorkflowStageDefinition[] = [
  {
    id: 'project',
    order: 1,
    title: '项目基础配置',
    status: 'done',
    prerequisiteIds: [],
    position: { x: 50, y: 350 }
  },
  {
    id: 'env',
    order: 2,
    title: '环境管理',
    status: 'done',
    prerequisiteIds: ['project'],
    position: { x: 270, y: 220 }
  },
  {
    id: 'assets',
    order: 3,
    title: '源码管理',
    status: 'done',
    prerequisiteIds: ['project'],
    position: { x: 270, y: 480 }
  },
  {
    id: 'x86-build',
    order: 5,
    title: 'x86 构建',
    status: 'done',
    prerequisiteIds: ['env', 'assets'],
    position: { x: 520, y: 350 }
  },
  {
    id: 'rv-dependency-analysis',
    order: 6,
    title: '依赖分析',
    status: 'done',
    prerequisiteIds: ['x86-build'],
    position: { x: 760, y: 155 }
  },
  {
    id: 'kb',
    order: 7,
    title: '知识库扫描',
    status: 'done',
    prerequisiteIds: ['x86-build'],
    position: { x: 760, y: 285 }
  },
  {
    id: 'memory-order-analysis',
    order: 8,
    title: '强弱内存序分析',
    status: 'pending',
    prerequisiteIds: ['x86-build'],
    position: { x: 760, y: 415 }
  },
  {
    id: 'floating-point-analysis',
    order: 9,
    title: '浮点精度扫描',
    status: 'pending',
    prerequisiteIds: ['x86-build'],
    position: { x: 760, y: 545 }
  },
  {
    id: 'report',
    order: 10,
    title: '评估报告',
    status: 'running',
    prerequisiteIds: ['kb', 'rv-dependency-analysis'],
    position: { x: 1080, y: 350 }
  },
  // AI 迁移与人工修复并列：都是看完评估报告后处理代码的方式，前者由迁移代理改写并产出补丁。
  {
    id: 'ai-migration',
    order: 11,
    title: 'AI 迁移',
    status: 'pending',
    prerequisiteIds: ['report'],
    position: { x: 1080, y: 520 }
  },
  {
    id: 'manual',
    order: 12,
    title: '人工修复',
    status: 'pending',
    prerequisiteIds: ['report'],
    position: { x: 1080, y: 650 }
  },
  {
    id: 'build',
    order: 13,
    title: '构建验证',
    status: 'pending',
    prerequisiteIds: ['report'],
    position: { x: 1340, y: 350 }
  },
  {
    id: 'test',
    order: 14,
    title: '自动化测试',
    status: 'pending',
    prerequisiteIds: ['build'],
    position: { x: 1580, y: 350 }
  },
  {
    id: 'optimize',
    order: 15,
    title: '优化闭环',
    status: 'pending',
    prerequisiteIds: ['test'],
    position: { x: 1820, y: 350 }
  },
  {
    id: 'archive',
    order: 16,
    title: '归档',
    status: 'pending',
    prerequisiteIds: ['optimize'],
    position: { x: 2060, y: 350 }
  }
]

export const workflowGateways: WorkflowGatewayDefinition[] = [
  {
    id: 'report-inputs',
    prerequisiteIds: ['kb', 'rv-dependency-analysis'],
    targetId: 'report',
    position: { x: 980, y: 364 }
  }
]

export const workflowGroups: WorkflowGroupDefinition[] = [
  {
    id: 'source-data',
    title: '基础准备',
    tone: 'setup',
    stageIds: ['env', 'assets']
  },
  {
    id: 'source-scan',
    title: '扫描与分析',
    tone: 'analysis',
    stageIds: [
      'x86-build',
      'rv-dependency-analysis',
      'kb',
      'memory-order-analysis',
      'floating-point-analysis'
    ]
  },
  {
    id: 'assessment',
    title: '评估与处理',
    tone: 'assessment',
    stageIds: ['report', 'ai-migration', 'manual'],
    padding: { right: 48 }
  },
  {
    id: 'optimization-test',
    title: '验证与交付',
    tone: 'delivery',
    stageIds: ['build', 'test', 'optimize', 'archive'],
    padding: { right: 48 }
  }
]

export const workflowEdges: WorkflowEdgeDefinition[] = [
  { id: 'project-env', source: 'project', target: 'env' },
  { id: 'project-assets', source: 'project', target: 'assets' },
  { id: 'env-x86-build', source: 'env', target: 'x86-build' },
  { id: 'assets-x86-build', source: 'assets', target: 'x86-build' },
  { id: 'x86-build-kb', source: 'x86-build', target: 'kb' },
  { id: 'kb-report', source: 'kb', target: 'report-inputs' },
  { id: 'x86-build-rv-dependency-analysis', source: 'x86-build', target: 'rv-dependency-analysis' },
  {
    id: 'rv-dependency-analysis-report',
    source: 'rv-dependency-analysis',
    target: 'report-inputs'
  },
  { id: 'x86-build-memory-order-analysis', source: 'x86-build', target: 'memory-order-analysis' },
  {
    id: 'x86-build-floating-point-analysis',
    source: 'x86-build',
    target: 'floating-point-analysis'
  },
  // Example results are not required inputs of the real assessment report.
  { id: 'report-inputs-report', source: 'report-inputs', target: 'report' },
  {
    id: 'report-ai-migration',
    source: 'report',
    target: 'ai-migration',
    sourceHandle: 'branch-source',
    targetHandle: 'branch-target'
  },
  {
    id: 'report-manual',
    source: 'report',
    target: 'manual',
    sourceHandle: 'branch-source',
    targetHandle: 'branch-target'
  },
  { id: 'report-build', source: 'report', target: 'build' },
  {
    id: 'ai-migration-assets',
    source: 'ai-migration',
    target: 'assets',
    feedback: true,
    label: '应用补丁后更新源码'
  },
  {
    id: 'manual-assets',
    source: 'manual',
    target: 'assets',
    feedback: true,
    label: '修复后更新源码'
  },
  { id: 'build-test', source: 'build', target: 'test' },
  { id: 'optimize-archive', source: 'optimize', target: 'archive' },
  {
    id: 'test-optimize',
    source: 'test',
    target: 'optimize'
  },
  {
    id: 'optimize-assets',
    source: 'optimize',
    target: 'assets',
    feedback: true,
    label: '优化后重新验证'
  }
]

export const workflowStageById = new Map(workflowStages.map((stage) => [stage.id, stage]))
