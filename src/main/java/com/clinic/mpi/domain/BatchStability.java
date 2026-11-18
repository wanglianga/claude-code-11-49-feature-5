package com.clinic.mpi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 决策稳定性评估：补充字段批次到达时，对<b>首批已做决策</b>逐条回放评估的结果。
 * 评估只对比"首批当时可见证据下的决策"与"补充字段到达后的新证据"，
 * 补充字段不会被回溯写入首批记录，也不会改写首批批次的质量报告。
 *
 * <ul>
 *   <li>{@link Verdict#STABLE}：新证据支持首批决策（自动合并仍成立 / 暂存猜测得到确认）；</li>
 *   <li>{@link Verdict#OVERTURNED}：新证据推翻首批候选（如出生年月矛盾），暂存撤回、候选作废，转人工；</li>
 *   <li>{@link Verdict#NEEDS_REVIEW}：证据增强但仍不足以自动发布，维持人工审阅；</li>
 *   <li>{@link Verdict#UPGRADED}：补充后满足强证据组合，首批暂存/人工项升级为可发布正式关联。</li>
 * </ul>
 */
@Entity
@Table(name = "batch_stability")
public class BatchStability {

    public enum Verdict {STABLE, OVERTURNED, NEEDS_REVIEW, UPGRADED}

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    /** 执行评估的补充批次 */
    @Column(nullable = false)
    public UUID supplementBatchId;

    /** 被评估的首批批次 */
    @Column(nullable = false)
    public UUID originalBatchId;

    @Column(nullable = false)
    public UUID identityId;

    @Column(nullable = false, length = 32)
    public String sourceSystem;

    @Column(nullable = false, length = 64)
    public String sourcePatientId;

    /** 首批决策快照（BatchRecord.Decision 字符串） */
    @Column(nullable = false, length = 24)
    public String originalDecision;

    /** 首批评分 */
    public double originalScore;

    /** 补充字段到达后的重算评分（仅用于评估，不回写首批候选） */
    public double reevaluatedScore;

    /** 关联的暂存归属（若首批为暂存） */
    public UUID stagedAttributionId;

    /** 关联的待处理候选对（若首批为人工审阅） */
    public UUID candidatePairId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public Verdict verdict;

    /** 评估依据（JSON：新增证据、矛盾点、对比说明） */
    @Column(length = 3000)
    public String basisJson;

    /** 评估后若发布正式关联，记录决议 */
    public UUID resolutionId;

    @Column(length = 500)
    public String note;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();
}
