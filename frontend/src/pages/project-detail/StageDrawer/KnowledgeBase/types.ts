import type { ProjectKnowledgeBaseScanConfig } from '@/types/project/knowledge-base'

export type KnowledgeBaseView =
  | { type: 'results' }
  | { type: 'config' }
  | { findingId: string; returnTo: 'results' | 'record'; scanId: string; type: 'detail' }
  | { type: 'history' }
  | { scanId: string; type: 'record' }
  | { returnTo: 'history' | 'results' | 'record'; scanId: string; type: 'scan-info' }

export interface FindingFilters {
  filePath?: string
  severity?: string
  status?: string
}

export const initialScanConfig: ProjectKnowledgeBaseScanConfig = {
  language: ['C/C++'],
  sourceArch: 'x86',
  scope: './',
  excludedDirectoryNames: 'build,node_modules,.git,target,__pycache__',
  excludedPaths: 'third_party/simd\nvendor/x86'
}
