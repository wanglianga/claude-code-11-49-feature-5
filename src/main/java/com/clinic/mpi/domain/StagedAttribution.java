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
 * 可逆暂存归属（受限工作区）：证据不足、不满足自动链接门槛的来源身份，
 * 允许先在工作区内得到一个<b>临时性</b>归属猜测以支撑受限历史查询，
 * 但暂存归属不进入时态关系图（mpi_link）、不进入任何业务快照物化映射，
 * 因此结构上不可能流入结算、预约提醒或正式分析。
 *
 * <p>生命周期：{@link Status#ACTIVE}（暂存生效，仅受限查询可见，带临时性与范围提示）
 * → 补充资料到达后 {@link Status#PUBLISHED}（经正式决议发布正式关联后关闭）
 * 或 {@link Status#REVOKED}（补充资料推翻猜测，撤回暂存归属）。</p>
 */
@Entity
@Table(name = "staged_attribution")
public class StagedAttribution {

    public enum Status {ACTIVE, PUBLISHED, REVOKED}

    /** 暂存归属唯一可用范围 */
    public static final String SCOPE_RESTRICTED_QUERY = "RESTRICTED_QUERY_ONLY";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @Column(nullable = false)
    public UUID identityId;

    /** 产生暂存的批次 */
    @Column(nullable = false)
    public UUID batchId;

    @Column(nullable = false, length = 32)
    public String sourceSystem;

    @Column(nullable = false, length = 64)
    public String sourcePatientId;

    /** 暂存创建时身份的正式主体（单例）；正式图谱始终以此为准 */
    @Column(nullable = false)
    public UUID formalMasterId;

    /** 工作区猜测的临时主体（弱证据候选主体；无候选时等于正式单例主体） */
    @Column(nullable = false)
    public UUID provisionalMasterId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public Status status = Status.ACTIVE;

    /** 固定为 RESTRICTED_QUERY_ONLY：任何正式业务场景均不可用 */
    @Column(nullable = false, length = 32)
    public String scope = SCOPE_RESTRICTED_QUERY;

    /** 暂存依据的弱证据（JSON 数组，如 ["姓名相似","手机号一致"]） */
    @Column(length = 1000)
    public String provisionalEvidenceJson;

    @Column(length = 500)
    public String reason;

    /** 强制提示：临时性、范围限制、不得用于结算/提醒/正式分析 */
    @Column(nullable = false, length = 500)
    public String warningText;

    /** 是否必须人工复核才能发布 */
    @Column(nullable = false)
    public boolean reviewRequired = true;

    /** 发布/撤回该暂存的补充批次 */
    public UUID closedByBatchId;

    /** 发布时产生的正式决议 */
    public UUID resolutionId;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();

    public Instant closedAt;

    @Column(length = 64)
    public String closedBy;
}
