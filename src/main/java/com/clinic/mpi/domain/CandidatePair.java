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
 * 候选重复对：由匹配评分生成，等待人工决议。
 * 候选对属于"尚在审核的身份推断"，下游不可见。
 */
@Entity
@Table(name = "candidate_pair")
public class CandidatePair {

    public enum Status {PENDING, MERGED, REJECTED, SUPERSEDED}

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @Column(nullable = false)
    public UUID identityAId;

    @Column(nullable = false)
    public UUID identityBId;

    /** 匹配评分 0~1 */
    @Column(nullable = false)
    public double score;

    /** 命中证据（JSON 数组，如 ["证件号一致","手机号一致"]） */
    @Column(length = 2000)
    public String evidence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public Status status = Status.PENDING;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();

    public Instant decidedAt;

    @Column(length = 64)
    public String decidedBy;

    @Column(length = 500)
    public String decisionReason;

    /** 生成该候选对的接入批次（渐进式接入溯源；历史直连接入可为 null） */
    public java.util.UUID originBatchId;

    /**
     * 自动链接资格：仅当强证据组合（证件号一致，或 手机+姓名+出生年月 三要素一致）命中时为 true。
     * true 且身份仍为单例主体时，批次接入可自动执行合并；false 的候选对只能人工决议。
     * 可空：历史直连接入产生的候选对没有该标记，按 false 处理。
     */
    @Column
    public boolean autoLinkEligible = false;

    /** 候选类型：STRONG_AUTO / MANUAL_REVIEW */
    @Column(length = 16)
    public String candidateType;
}
