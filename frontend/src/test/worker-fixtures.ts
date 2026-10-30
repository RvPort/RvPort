import type { WorkerInstance, WorkerTask } from '@/types/admin/workers'

export function createWorker(overrides: Partial<WorkerInstance> = {}): WorkerInstance {
  return {
    activeConcurrency: 2,
    activeTaskCount: 2,
    appVersion: '1.2.0',
    concurrencyLimit: 4,
    createdAt: '2026-10-28T00:00:00Z',
    hostname: 'build-host',
    instanceId: 'worker-1',
    lastHeartbeatAt: '2026-10-28T00:00:00Z',
    processId: 1024,
    startedAt: '2026-10-28T00:00:00Z',
    status: 'ONLINE',
    updatedAt: '2026-10-28T00:00:00Z',
    ...overrides
  }
}
export function createWorkerTask(taskId: string): WorkerTask {
  return {
    message: null,
    progress: 10,
    projectId: 'project',
    projectName: '测试项目',
    startedAt: '2026-10-28T00:00:00Z',
    status: 'RUNNING',
    taskId,
    type: 'BUILD_VALIDATION'
  }
}
