/** AI 迁移技能仓库的版本状态。 */
export interface SkillStatus {
  /** 仓库内可用的技能（含 SKILL.md 的顶层目录）。 */
  skills: string[]
  /** 本地当前版本的提交标识。 */
  currentRevision: string
  /** 远端分支最新版本的提交标识。 */
  remoteRevision: string
  /** 本地落后远端的提交数，0 表示已是最新。 */
  commitsBehind: number
  /** 落后时远端变更的文件摘要，已最新时为空。 */
  diffStatSummary: string
}

/** 技能仓库一次更新的结果。 */
export interface SkillUpdateResult {
  beforeRevision: string
  afterRevision: string
  commitsChanged: number
  filesChanged: number
  /** 变更文件的统计行，最多 50 行；无变更时为空。 */
  diffStatLines: string[]
}
