package com.clinic.mpi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * 文件批次：一次来源文件接收的完整台账。
 *
 * <p>批次状态只有两种终态：{@link Status#ACCEPTED}（字段契约通过，记录全部完成接入判定）
 * 与 {@link Status#RESTRICTED}（受限/待修复：列名漂移、必填字段批量缺失、格式漂移或更正矛盾，
 * 该批次不向正式图谱与暂存工作区写入任何记录，修复后以新指纹重传）。</p>
 *
 * <p>批次级量化指标：字段覆盖、格式规则违反、可产生候选类型、自动决策资格、
 * 三类决策比例（可确定链接 / 仅可人工审阅 / 证据不足暂不链接）与对既有主体的预计影响。</p>
 */
@Entity
@Table(name = "ingestion_batch",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = "batchCode"),
                @UniqueConstraint(columnNames = "fileFingerprint")
        })
public class IngestionBatch {

    public enum Status {
        /** 已接收、字段契约检测与接入判定进行中（事务内瞬时态） */
        RECEIVED,
        /** 检测通过，记录全部完成接入判定 */
        ACCEPTED,
        /** 受限/待修复：列名漂移、必填批量缺失、格式漂移或更正矛盾 */
        RESTRICTED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    /** 业务批次号（缺省自动生成 BATCH-xxxx） */
    @Column(nullable = false, length = 64)
    public String batchCode;

    @Column(nullable = false, length = 32)
    public String sourceSystem;

    @Column(nullable = false, length = 256)
    public String fileName;

    /** 文件指纹（规范化内容的 SHA-256）；同指纹重传幂等，直接返回原批次 */
    @Column(nullable = false, length = 64)
    public String fileFingerprint;

    /** 字段契约版本：legacy-v1（旧病历，DOB 可选）/ enriched-v2（补充批次，DOB 必填） */
    @Column(nullable = false, length = 32)
    public String contractVersion;

    /** 实际上报列名（JSON 数组），用于列名漂移审计 */
    @Column(length = 1000)
    public String declaredColumnsJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public Status status;

    public int totalRecords;
    public int processedCount;
    public int rejectedCount;
    public int duplicateCount;

    /** 可确定链接（含强证据自动合并与强证据无匹配的确定单例） */
    public int confirmedCount;
    /** 仅可人工审阅：评分过线但不满足强证据组合 */
    public int manualReviewCount;
    /** 因证据不足暂不链接：仅进入受限工作区暂存 */
    public int insufficientCount;

    /** 满足强证据组合、具备自动决策资格的记录数 */
    public int autoEligibleCount;
    /** 实际执行自动合并的次数 */
    public int autoMergedCount;
    /** 新建正式单例主体数 */
    public int newSingletonCount;
    /** 写入暂存工作区的记录数 */
    public int stagedCount;

    /** 批次接入时的图谱版本（时点基线） */
    public long graphVersionAtIngest;

    /** 字段 -> {present,missing,coverageRate} */
    @Column(length = 4000)
    public String fieldCoverageJson;

    /** 字段 -> {rule, violations, samples} */
    @Column(length = 4000)
    public String formatRulesJson;

    /** 候选类型计数 STRONG_AUTO / MANUAL_REVIEW / NO_CANDIDATE */
    @Column(length = 1000)
    public String candidateTypeCountsJson;

    /** 质量问题清单 [{type,field,detail}]：CONTRACT_DRIFT/MISSING_BATCH_FIELD/FORMAT_VIOLATION/CORRECTION_CONTRADICTION/UNDISCLOSED_CHANGE */
    @Column(length = 8000)
    public String qualityIssuesJson;

    /** 预计对既有主体的影响 {impactedMasterIds,newSingletons,autoMerged,stagedOnly} */
    @Column(length = 4000)
    public String estimatedImpactJson;

    /** 补充批次的稳定性汇总 {stable,overturned,needsReview,upgraded} */
    @Column(length = 2000)
    public String stabilitySummaryJson;

    /** 修复重提时指向原受限批次 */
    public UUID repairOfBatchId;

    /** 同指纹重传次数（首次为 0） */
    public int reuploadCount;

    @Column(nullable = false, length = 64)
    public String createdBy;

    @Column(nullable = false)
    public Instant receivedAt = Instant.now();

    public Instant processedAt;
}
