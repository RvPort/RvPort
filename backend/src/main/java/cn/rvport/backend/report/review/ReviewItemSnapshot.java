package cn.rvport.backend.report.review;

import java.util.Map;
import java.util.UUID;

/** 报告创建时固定的事实、继承依据与初始处置；AI 结果单独持久化。 */
public record ReviewItemSnapshot(UUID id,String origin,String sourceType,UUID sourceRunId,UUID sourceRefId,
        String identityHash,String evidenceHash,Map<String,Object> originalSnapshot,String title,String titleEn,
        String fileKey,String relativePath,Integer startLine,Integer startColumn,Integer endLine,Integer endColumn,
        String sourceContentHash,String snippetHash,Map<String,Object> manualDetails,ReviewDecision decision,
        String exclusionReasonEn,Map<String,Object> inheritance,UUID createdBy) { }
