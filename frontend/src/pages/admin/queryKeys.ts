export const adminQueryKeys = {
  users: () => ['admin-users'] as const,
  mailSettings: () => ['admin-mail-settings'] as const,
  workers: () => ['admin-workers'] as const,
  worker: (instanceId?: string | null) => ['admin-worker-detail', instanceId] as const,
  skills: () => ['admin-skills'] as const
}
