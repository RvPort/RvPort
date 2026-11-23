import type { SkillStatus, SkillUpdateResult } from '@/types/admin/skills'

import request from '../request'

export function getAdminSkillStatus() {
  return request.get<SkillStatus>('/admin/ai-migration/skills')
}

export function updateAdminSkills() {
  return request.post<SkillUpdateResult>('/admin/ai-migration/skills:update')
}
