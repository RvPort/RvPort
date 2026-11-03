import {
  type KeyboardEventHandler,
  memo,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState
} from 'react'
import {
  Background,
  Controls,
  type Edge,
  MarkerType,
  type Node,
  type NodeMouseHandler,
  ReactFlow,
  type ReactFlowInstance
} from '@xyflow/react'

import type { ProjectWorkflowStatus } from '@/types/project'

import { createWorkflowEdges, edgeTypes, highlightConnectedWorkflowEdges } from './edges'
import {
  createWorkflowNodes,
  getWorkflowGroupCenter,
  getWorkflowStageCenter,
  nodeTypes
} from './nodes'

import '@xyflow/react/dist/style.css'
import styles from './index.module.scss'

interface WorkflowProps {
  workflowStatus?: ProjectWorkflowStatus
  onStageClick?: (stageId: string) => void
  focusedStageId?: string
  focusRequest?: number
  focusEnabled?: boolean
  selectedStageId?: string
}

interface WorkflowCanvasProps {
  nodes: Node[]
  edges: Edge[]
  focusedStageId?: string
  focusRequest?: number
  focusEnabled?: boolean
  focusGroupId?: string
  onNodeClick: NodeMouseHandler
  onStageKeyDown: KeyboardEventHandler<HTMLDivElement>
  onNodeMouseEnter: NodeMouseHandler
  onNodeMouseLeave: NodeMouseHandler
}

const defaultEdgeOptions = {
  markerEnd: {
    type: MarkerType.ArrowClosed,
    color: '#8ea4c2',
    width: 12,
    height: 12
  },
  style: {
    stroke: '#8ea4c2',
    strokeWidth: 2.5
  }
}

const initialFocusedStageZoom = 0.92

const defaultViewport = { x: 0, y: 0, zoom: initialFocusedStageZoom }

const proOptions = { hideAttribution: true }

const WorkflowCanvas = memo(function WorkflowCanvas({
  nodes,
  edges,
  focusGroupId,
  focusedStageId,
  focusRequest,
  focusEnabled = true,
  onNodeClick,
  onStageKeyDown,
  onNodeMouseEnter,
  onNodeMouseLeave
}: WorkflowCanvasProps) {
  const reactFlowInstanceRef = useRef<ReactFlowInstance | null>(null)

  const centerFocusedStage = useCallback(
    (reactFlowInstance: ReactFlowInstance) => {
      const stageCenter = focusedStageId
        ? getWorkflowStageCenter(focusedStageId)
        : getWorkflowGroupCenter(focusGroupId)

      if (!stageCenter) {
        return
      }

      const zoom = Math.max(reactFlowInstance.getViewport().zoom, initialFocusedStageZoom)
      void reactFlowInstance.setCenter(stageCenter.x, stageCenter.y, {
        duration: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 0 : 320,
        zoom
      })
    },
    [focusGroupId, focusedStageId]
  )

  useEffect(() => {
    const reactFlowInstance = reactFlowInstanceRef.current

    if (!reactFlowInstance || !focusEnabled) {
      return
    }

    centerFocusedStage(reactFlowInstance)
  }, [centerFocusedStage, focusEnabled, focusRequest])

  const handleInit = useCallback(
    (reactFlowInstance: ReactFlowInstance) => {
      reactFlowInstanceRef.current = reactFlowInstance
      centerFocusedStage(reactFlowInstance)
    },
    [centerFocusedStage]
  )

  return (
    <ReactFlow
      nodes={nodes}
      nodeTypes={nodeTypes}
      edges={edges}
      edgeTypes={edgeTypes}
      nodesDraggable={false}
      nodesConnectable={false}
      onNodeClick={onNodeClick}
      onKeyDownCapture={onStageKeyDown}
      onNodeMouseEnter={onNodeMouseEnter}
      onNodeMouseLeave={onNodeMouseLeave}
      defaultEdgeOptions={defaultEdgeOptions}
      defaultViewport={defaultViewport}
      onInit={handleInit}
      proOptions={proOptions}
    >
      <Background />
      <Controls />
    </ReactFlow>
  )
})

function Workflow({
  workflowStatus,
  onStageClick,
  focusedStageId,
  focusRequest,
  focusEnabled,
  selectedStageId
}: WorkflowProps) {
  const [hoveredNodeId, setHoveredNodeId] = useState<string>()
  const nodes = useMemo(() => createWorkflowNodes(workflowStatus), [workflowStatus])
  const visibleNodes = useMemo(
    () =>
      selectedStageId
        ? nodes.map((node) =>
            node.type === 'pipeline-stage'
              ? { ...node, data: { ...node.data, selected: node.id === selectedStageId } }
              : node
          )
        : nodes,
    [selectedStageId, nodes]
  )
  const workflowEdges = useMemo(() => createWorkflowEdges(workflowStatus), [workflowStatus])
  const currentStageId = nodes.find(
    (node) => node.type === 'pipeline-stage' && node.data.focused
  )?.id
  const visibleEdges = useMemo(
    () => highlightConnectedWorkflowEdges(workflowEdges, hoveredNodeId, currentStageId),
    [currentStageId, hoveredNodeId, workflowEdges]
  )
  const handleNodeClick = useCallback<NodeMouseHandler>(
    (_, node) => {
      if (node.type !== 'pipeline-stage') {
        return
      }

      onStageClick?.(node.id)
    },
    [onStageClick]
  )
  const handleStageKeyDown = useCallback<KeyboardEventHandler<HTMLDivElement>>(
    (event) => {
      if (!onStageClick || event.key !== 'Enter' || event.repeat || event.nativeEvent.isComposing)
        return
      const target = event.target
      if (!(target instanceof HTMLElement)) return
      // 只处理聚焦在 ReactFlow 节点外壳上的 Enter；不截获节点内控件的键盘事件。
      const node = nodes.find(
        (item) => item.id === target.dataset.id && item.type === 'pipeline-stage'
      )
      if (!node) return
      event.preventDefault()
      event.stopPropagation()
      onStageClick(node.id)
    },
    [nodes, onStageClick]
  )
  const handleNodeMouseEnter = useCallback<NodeMouseHandler>((_, node) => {
    setHoveredNodeId(node.id)
  }, [])
  const handleNodeMouseLeave = useCallback<NodeMouseHandler>(() => {
    setHoveredNodeId(undefined)
  }, [])

  return (
    <div className={styles.canvasPanel}>
      <WorkflowCanvas
        nodes={visibleNodes}
        edges={visibleEdges}
        focusGroupId={workflowStatus?.focusGroupId}
        focusedStageId={focusedStageId ?? workflowStatus?.currentStageId}
        focusRequest={focusRequest}
        focusEnabled={focusEnabled}
        onNodeClick={handleNodeClick}
        onStageKeyDown={handleStageKeyDown}
        onNodeMouseEnter={handleNodeMouseEnter}
        onNodeMouseLeave={handleNodeMouseLeave}
      />
    </div>
  )
}

export default Workflow
