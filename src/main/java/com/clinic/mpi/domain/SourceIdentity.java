package com.clinic.mpi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 来源身份：各院区挂号 / 检验 / 结算系统上报的就诊人身份。
 * 同一来源系统内 sourcePatientId 唯一；证据字段（姓名/手机/证件）允许后续更正，
 * 更正历史保存在 identity_correction，已发布快照不回写。
 */
@Entity
@Table(name = "source_identity",
        uniqueConstraints = @UniqueConstraint(columnNames = {"sourceSystem", "sourcePatientId"}))
public class SourceIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    /** 来源系统，如 REG_A（院区A挂号）、REG_B、LAB_A（检验）、SETTLE_A（结算） */
    @Column(nullable = false, length = 32)
    public String sourceSystem;

    /** 来源系统内部的就诊人编号 */
    @Column(nullable = false, length = 64)
    public String sourcePatientId;

    @Column(nullable = false, length = 64)
    public String fullName;

    @Column(length = 32)
    public String phone;

    @Column(length = 32)
    public String idCardNo;

    @Column(length = 8)
    public String gender;

    public LocalDate birthDate;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();

    /** 首次接入该身份的文件批次（渐进式接入溯源） */
    public java.util.UUID firstBatchId;

    /** 最近一次补充/更新该身份字段的批次 */
    public java.util.UUID lastBatchId;

    /** 接入时的字段契约版本（legacy-v1 / enriched-v2 ...），记录"当时能看到什么" */
    @Column(length = 32)
    public String contractVersionAtIngest;
}
