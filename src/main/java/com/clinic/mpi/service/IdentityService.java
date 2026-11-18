package com.clinic.mpi.service;

import com.clinic.mpi.domain.CandidatePair;
import com.clinic.mpi.domain.IdentityCorrection;
import com.clinic.mpi.domain.MpiLink;
import com.clinic.mpi.domain.MpiMaster;
import com.clinic.mpi.domain.SourceIdentity;
import com.clinic.mpi.repo.CandidatePairRepo;
import com.clinic.mpi.repo.IdentityCorrectionRepo;
import com.clinic.mpi.repo.MpiLinkRepo;
import com.clinic.mpi.repo.MpiMasterRepo;
import com.clinic.mpi.repo.SourceIdentityRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 来源身份接入与更正。接入即为身份创建初始主体（单例主体）并触发候选匹配；
 * 更正只更新证据字段并重跑匹配，不回写任何已发布快照。
 *
 * <p>渐进式接入：批次入口 {@link #ingestBatch} 记录首批契约版本与批次溯源；
 * 补充批次通过 {@link #enrichFields} 只补齐首批为空的字段（显式留痕），
 * 已非空字段的变更必须走 {@link #correctBatch} 更正流程，禁止静默覆盖。</p>
 */
@Service
public class IdentityService {

    private final SourceIdentityRepo identityRepo;
    private final IdentityCorrectionRepo correctionRepo;
    private final CandidatePairRepo candidateRepo;
    private final MpiMasterRepo masterRepo;
    private final MpiLinkRepo linkRepo;
    private final GraphService graphService;
    private final MatchService matchService;

    public IdentityService(SourceIdentityRepo identityRepo, IdentityCorrectionRepo correctionRepo,
                           CandidatePairRepo candidateRepo, MpiMasterRepo masterRepo,
                           MpiLinkRepo linkRepo, GraphService graphService, MatchService matchService) {
        this.identityRepo = identityRepo;
        this.correctionRepo = correctionRepo;
        this.candidateRepo = candidateRepo;
        this.masterRepo = masterRepo;
        this.linkRepo = linkRepo;
        this.graphService = graphService;
        this.matchService = matchService;
    }

    /** 接入来源身份（历史直连接入口，无批次溯源）。 */
    @Transactional
    public SourceIdentity ingest(String sourceSystem, String sourcePatientId, String fullName,
                                 String phone, String idCardNo, String gender, LocalDate birthDate) {
        return ingestBatch(sourceSystem, sourcePatientId, fullName, phone, idCardNo, gender,
                birthDate, null, null);
    }

    /**
     * 批次接入：创建初始主体 + 当前版本生效的链接，并触发带批次溯源的候选匹配。
     * 空值保持 null，绝不以默认值填补。
     */
    @Transactional
    public SourceIdentity ingestBatch(String sourceSystem, String sourcePatientId, String fullName,
                                      String phone, String idCardNo, String gender, LocalDate birthDate,
                                      UUID batchId, String contractVersion) {
        if (identityRepo.existsBySourceSystemAndSourcePatientId(sourceSystem, sourcePatientId)) {
            throw new ConflictException("来源身份已存在: " + sourceSystem + "/" + sourcePatientId);
        }
        SourceIdentity identity = new SourceIdentity();
        identity.sourceSystem = sourceSystem;
        identity.sourcePatientId = sourcePatientId;
        identity.fullName = fullName;
        identity.phone = phone;
        identity.idCardNo = idCardNo;
        identity.gender = gender;
        identity.birthDate = birthDate;
        identity.createdAt = Instant.now();
        identity.firstBatchId = batchId;
        identity.lastBatchId = batchId;
        identity.contractVersionAtIngest = contractVersion;
        identityRepo.save(identity);

        MpiMaster master = new MpiMaster();
        master.origin = MpiMaster.Origin.INITIAL.name();
        masterRepo.save(master);

        MpiLink link = new MpiLink();
        link.identityId = identity.id;
        link.masterId = master.id;
        link.validFrom = graphService.currentVersion();
        linkRepo.save(link);

        matchService.generateCandidatesFor(identity, batchId);
        return identity;
    }

    /**
     * 来源系统更正证据字段（如证件号）。
     * 只更新身份本身并重跑匹配：已发布快照的物化映射不回写；
     * 原有待处理候选对标记为 SUPERSEDED，按新证据重新生成候选。
     */
    @Transactional
    public IdentityCorrection correct(UUID identityId, String field, String newValue,
                                      String note, String operatorId) {
        return correctBatch(identityId, field, newValue, note, operatorId, null);
    }

    /** 批次更正入口：新生成的候选对携带补充批次溯源。 */
    @Transactional
    public IdentityCorrection correctBatch(UUID identityId, String field, String newValue,
                                           String note, String operatorId, UUID batchId) {
        SourceIdentity identity = require(identityId);
        IdentityCorrection correction = applyFieldChange(identity, field, newValue, note, operatorId,
                "来源身份字段更正");
        identity.lastBatchId = batchId;
        identityRepo.save(identity);
        rerunMatching(identity, batchId, operatorId,
                "来源身份字段更正，候选对基于旧证据生成，自动作废");
        return correction;
    }

    /**
     * 补充批次字段补齐：只允许写入身份当前为 null/空白 的字段；
     * 唯一例外是简写姓名补全（新旧姓名存在包含关系，如 "张" -> "张三"），
     * 按更正留痕处理。已非空且不兼容的字段变更会被拒绝（必须走更正矛盾检查），
     * 每次补齐都写更正审计留痕，标注"补充批次补齐、非首批已知信息"。
     *
     * @return 实际补齐的字段 -> 新值
     */
    @Transactional
    public Map<String, String> enrichFields(UUID identityId, Map<String, String> fills,
                                            UUID batchId, String operatorId) {
        SourceIdentity identity = require(identityId);
        Map<String, String> applied = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : fills.entrySet()) {
            String field = entry.getKey();
            String newValue = entry.getValue();
            if (newValue == null || newValue.isBlank()) {
                continue; // 空值不填补
            }
            String current = readField(identity, field);
            if (current != null && !current.isBlank()) {
                if (current.equals(newValue)) {
                    continue; // 值相同，无需处理
                }
                if ("fullName".equals(field)
                        && (newValue.contains(current) || current.contains(newValue))) {
                    // 简写姓名补全：兼容包含关系，按更正留痕
                    IdentityCorrection correction = applyFieldChange(identity, field, newValue,
                            "补充批次 " + batchId + " 简写姓名补全（'" + current + "' -> '" + newValue
                                    + "'，包含关系兼容，留更正痕跡；非回溯改写首批事实）",
                            operatorId, "补充批次简写姓名补全");
                    correctionRepo.save(correction);
                    applied.put(field, newValue);
                    continue;
                }
                throw new ConflictException("字段 " + field + " 在首批已存在值 '" + current
                        + "'，补充批次不得静默覆盖为 '" + newValue + "'，须走更正流程");
            }
            IdentityCorrection correction = applyFieldChange(identity, field, newValue,
                    "补充批次 " + batchId + " 补齐首批缺失字段（首批契约下该字段不可见，非回溯改写）",
                    operatorId, "补充批次字段补齐");
            correctionRepo.save(correction);
            applied.put(field, newValue);
        }
        if (!applied.isEmpty()) {
            identity.lastBatchId = batchId;
            identityRepo.save(identity);
            rerunMatching(identity, batchId, operatorId,
                    "补充批次字段补齐，旧证据候选对作废并按新证据重跑匹配");
        }
        return applied;
    }

    private IdentityCorrection applyFieldChange(SourceIdentity identity, String field, String newValue,
                                                String note, String operatorId, String actionLabel) {
        IdentityCorrection correction = new IdentityCorrection();
        correction.identityId = identity.id;
        correction.field = field;
        correction.newValue = newValue;
        correction.note = note;
        correction.operatorId = operatorId;
        switch (field) {
            case "idCardNo" -> {
                correction.oldValue = identity.idCardNo;
                identity.idCardNo = newValue;
            }
            case "phone" -> {
                correction.oldValue = identity.phone;
                identity.phone = newValue;
            }
            case "fullName" -> {
                correction.oldValue = identity.fullName;
                identity.fullName = newValue;
            }
            case "gender" -> {
                correction.oldValue = identity.gender;
                identity.gender = newValue;
            }
            case "birthDate" -> {
                correction.oldValue = identity.birthDate == null ? null : identity.birthDate.toString();
                identity.birthDate = newValue == null ? null : LocalDate.parse(newValue);
            }
            default -> throw new BadRequestException("不支持更正的字段: " + field
                    + "（支持 idCardNo/phone/fullName/gender/birthDate）");
        }
        return correction;
    }

    private void rerunMatching(SourceIdentity identity, UUID batchId, String operatorId, String reason) {
        for (CandidatePair pair : candidateRepo.findPendingInvolving(identity.id)) {
            pair.status = CandidatePair.Status.SUPERSEDED;
            pair.decidedAt = Instant.now();
            pair.decidedBy = operatorId;
            pair.decisionReason = reason;
            candidateRepo.save(pair);
        }
        matchService.generateCandidatesFor(identity, batchId);
    }

    private String readField(SourceIdentity identity, String field) {
        return switch (field) {
            case "idCardNo" -> identity.idCardNo;
            case "phone" -> identity.phone;
            case "fullName" -> identity.fullName;
            case "gender" -> identity.gender;
            case "birthDate" -> identity.birthDate == null ? null : identity.birthDate.toString();
            default -> throw new BadRequestException("不支持的字段: " + field);
        };
    }

    private SourceIdentity require(UUID identityId) {
        return identityRepo.findById(identityId)
                .orElseThrow(() -> new NotFoundException("来源身份不存在: " + identityId));
    }

    @Transactional(readOnly = true)
    public SourceIdentity get(UUID id) {
        return require(id);
    }

    @Transactional(readOnly = true)
    public List<SourceIdentity> list() {
        return identityRepo.findAll();
    }

    @Transactional(readOnly = true)
    public List<IdentityCorrection> correctionsOf(UUID identityId) {
        return correctionRepo.findByIdentityIdOrderByCreatedAtDesc(identityId);
    }
}
