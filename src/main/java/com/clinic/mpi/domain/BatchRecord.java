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
 * 批次记录：文件内一行的解析结果与处置台账，记录键为 (batch, sourceSystem, sourcePatientId)。
 * 同一记录键在同一批次只解析一次（文件内行级幂等）；跨批次重传由批次文件指纹幂等覆盖。
 * 记录的判定只基于当批可见字段，补充字段在后续批次以新记录/更新到达，
 * 不会被回溯当成首批"本应拥有"的信息。
 */
@Entity
@Table(name = "batch_record",
        uniqueConstraints = @UniqueConstraint(columnNames =
                {"batchId", "sourceSystem", "sourcePatientId"}))
public class BatchRecord {

    public enum Decision {
        /** 可确定链接：强证据自动合并到既有主体 */
        AUTO_MERGED,
        /** 可确定链接：强证据充分但无匹配，确定单例（新建正式主体） */
        CONFIRMED_SINGLETON,
        /** 仅可人工审阅：评分过线但证据组合不满足自动门槛 */
        MANUAL_REVIEW,
        /** 因证据不足暂不链接：只写入受限工作区暂存 */
        INSUFFICIENT_STAGED,
        /** 同记录键/指纹重复，跳过 */
        DUPLICATE_SKIPPED,
        /** 字段契约或格式问题被拒（批次整体受限，不产生身份） */
        REJECTED
    }

    public enum CandidateType {
        /** 强证据组合命中，可自动链接 */
        STRONG_AUTO,
        /** 评分过阈值但仅弱/中证据，须人工审阅 */
        MANUAL_REVIEW,
        /** 无候选 */
        NO_CANDIDATE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @Column(nullable = false)
    public UUID batchId;

    @Column(nullable = false, length = 32)
    public String sourceSystem;

    @Column(nullable = false, length = 64)
    public String sourcePatientId;

    /** 行内字段原文（JSON，仅保存解析后规范化值，空值显式为 null 不做填补） */
    @Column(length = 4000)
    public String rawFieldsJson;

    /** 字段覆盖位图（JSON：字段名 -> true/false） */
    @Column(length = 1000)
    public String coverageJson;

    /** 格式违反说明（JSON 数组，空数组表示全部通过） */
    @Column(length = 2000)
    public String formatViolationsJson;

    /** 接入后产生/命中的来源身份；受限批次为 null */
    public UUID identityId;

    /** 自动合并或单例确定后的正式主体；暂存记录为 null */
    public UUID masterId;

    /** 最佳候选对（人工审阅类）；无候选为 null */
    public UUID candidatePairId;

    public double bestScore;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    public CandidateType candidateType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    public Decision decision;

    /** 命中的强证据组合说明（JSON 数组） */
    @Column(length = 1000)
    public String strongEvidenceJson;

    /** 预计影响的既有主体 ID（JSON 数组；自动合并目标或候选涉及主体） */
    @Column(length = 1000)
    public String estimatedImpactMastersJson;

    @Column(length = 1000)
    public String decisionNote;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();
}
