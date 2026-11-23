import type { SkillStatus, SkillUpdateResult } from '@/types/admin/skills'

/** 版本标识只显示前若干位，完整值用于复制与核对。 */
export function shortRevision(revision: string | null | undefined) {
  if (!revision) return '--'
  return revision.length > 12 ? revision.slice(0, 12) : revision
}

export interface SkillStatusPresentation {
  /** 一句话说明当前状态，已是最新或有可用更新。 */
  summary: string
  /** 是否存在可更新的版本。 */
  hasUpdate: boolean
  /** 远端变更的文件列表，已是最新时为空。 */
  changedFiles: string[]
}

/**
 * 远端变更摘要形如 ` dir/a.md | 10 +++--\n dir/b.md |  2 -\n 2 files changed…`：
 * 文件行形如 `<路径> | <变更图>`，末行的汇总行没有竖线。只保留含竖线的行，
 * 否则汇总行（"2 files changed, …"）会被当成一个文件名。
 */
export function parseChangedFiles(diffStat: string | null | undefined) {
  if (!diffStat) return []
  return diffStat
    .split('\n')
    .filter((line) => line.includes('|'))
    .map((line) => line.split('|')[0]?.trim())
    .filter((file): file is string => Boolean(file))
}

export function presentSkillStatus(
  status: SkillStatus | null | undefined
): SkillStatusPresentation {
  const changedFiles = parseChangedFiles(status?.diffStatSummary)
  const behind = status?.commitsBehind ?? 0
  return {
    summary:
      behind > 0
        ? `远端有 ${behind} 个新提交，涉及 ${changedFiles.length || '若干'} 个文件`
        : '技能已是最新版本',
    hasUpdate: behind > 0,
    changedFiles
  }
}

/** 更新完成后的提示语。 */
export function describeUpdateResult(result: SkillUpdateResult) {
  if (result.commitsChanged === 0) return '技能已是最新版本，无需更新'
  return `技能已更新：${result.commitsChanged} 个提交，${result.filesChanged} 个文件`
}

/** 更新前后版本是否有变化；提交数为 0 时版本一定相同。 */
export function didRevisionChange(result: SkillUpdateResult) {
  return result.commitsChanged > 0 && result.beforeRevision !== result.afterRevision
}
