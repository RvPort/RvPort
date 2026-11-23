import { beforeEach, describe, expect, it, vi } from 'vitest'

const { request } = vi.hoisted(() => ({
  request: {
    get: vi.fn(),
    post: vi.fn()
  }
}))

vi.mock('../request', () => ({ default: request }))

import { getAdminSkillStatus, updateAdminSkills } from './skills'

describe('admin skills API contract', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    request.get.mockResolvedValue({})
    request.post.mockResolvedValue({})
  })

  it('reads the skill repository status', () => {
    getAdminSkillStatus()
    expect(request.get).toHaveBeenCalledWith('/admin/ai-migration/skills')
  })

  it('updates skills to the remote branch', () => {
    updateAdminSkills()
    expect(request.post).toHaveBeenCalledWith('/admin/ai-migration/skills:update')
  })
})
