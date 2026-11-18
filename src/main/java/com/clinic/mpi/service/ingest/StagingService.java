package com.clinic.mpi.service.ingest;

import com.clinic.mpi.domain.BatchRecord;
import com.clinic.mpi.domain.BatchStability;
import com.clinic.mpi.domain.CandidatePair;
import com.clinic.mpi.domain.Resolution;
import com.clinic.mpi.domain.SourceIdentity;
import com.clinic.mpi.domain.StagedAttribution;
import com.clinic.mpi.repo.BatchRecordRepo;
import com.clinic.mpi.repo.BatchStabilityRepo;
import com.clinic.mpi.repo.CandidatePairRepo;
import com.clinic.mpi.repo.SourceIdentityRepo;
import com.clinic.mpi.repo.StagedAttributionRepo;
import com.clinic.mpi.service.ConflictException;
import com.clinic.mpi.service.GraphService;
import com.clinic.mpi.service.NotFoundException;
import com.clinic.mpi.service.ResolutionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 可逆暂存归属工作区。
 *
 * <p>边界约束（结构性隔离，而非约定）：</p>
 * <ul>
 *   <li>暂存归属只存在于 staged_attribution 表，<b>永不写入 mpi_link</b>，
 *       因此时态关系图、快照物化、结算、预约提醒、正式分析在数据层面无法读取它；</li>
 *   <li>受限查询返回的每条结果都强制携带 scope=RESTRICTED_QUERY_ONLY 与临时性警告；</li>
 *   <li>发布正式关联必须有满足强证据组合的待处理候选对，否则 409；发布经正式决议产生新版本，
 *       暂存随即关闭；补充资料推翻猜测时只能撤回（REVOKED），正式图谱从未被污染。</li>
 * </ul>
 */
@Service
public class StagingService {

    public static final String WARNING = "临时暂存归属（RESTRICTED_QUERY_ONLY）：基于弱证据的工作区猜测，"
            + "不得用于结算、预约提醒或正式分析；补充资料到达并复核前不构成正式身份事实";

    private final StagedAttributionRepo stagingRepo;
    private final SourceIdentityRepo identityRepo;
    private final CandidatePairRepo candidateRepo;
    private final BatchRecordRepo recordRepo;
    private final BatchStabilityRepo stabilityRepo;
    private final ResolutionService resolutionService;
    private final GraphService graphService;
    private final ObjectMapper objectMapper;

    public StagingService(StagedAttributionRepo stagingRepo, SourceIdentityRepo identityRepo,
                          CandidatePairRepo candidateRepo, BatchRecordRepo recordRepo,
                          BatchStabilityRepo stabilityRepo, ResolutionService resolutionService,
                          GraphService graphService, ObjectMapper objectMapper) {
        this.stagingRepo = stagingRepo;
        this.identityRepo = identityRepo;
        this.candidateRepo = candidateRepo;
        this.recordRepo = recordRepo;
        this.stabilityRepo = stabilityRepo;
        this.resolutionService = resolutionService;
        this.graphService = graphService;
        this.objectMapper = objectMapper;
    }

    /** 创建暂存归属（仅批次服务调用）。 */
    @Transactional
    public StagedAttribution stage(SourceIdentity identity, UUID batchId, UUID provisionalMasterId,
                                   List<String> weakEvidence, String reason) {
        stagingRepo.findByIdentityIdAndStatus(identity.id, StagedAttribution.Status.ACTIVE)
                .ifPresent(s -> {
                    throw new ConflictException("身份 " + identity.id + " 已存在生效中的暂存归属");
                });
        StagedAttribution staged = new StagedAttribution();
        staged.identityId = identity.id;
        staged.batchId = batchId;
        staged.sourceSystem = identity.sourceSystem;
        staged.sourcePatientId = identity.sourcePatientId;
        staged.formalMasterId = graphService.currentMasterOf(identity.id);
        staged.provisionalMasterId = provisionalMasterId;
        staged.provisionalEvidenceJson = writeJson(weakEvidence);
        staged.reason = reason;
        staged.warningText = WARNING;
        staged.reviewRequired = true;
        return stagingRepo.save(staged);
    }

    /** 受限历史查询：只返回暂存工作区内的临时归属，响应自带临时性与范围提示。 */
    @Transactional(readOnly = true)
    public List<StagedView> restrictedQuery(String sourceSystem, String nameKeyword) {
        List<StagedView> views = new ArrayList<>();
        for (StagedAttribution staged : stagingRepo.findByStatusOrderByCreatedAtAsc(
                StagedAttribution.Status.ACTIVE)) {
            SourceIdentity identity = identityRepo.findById(staged.identityId).orElse(null);
            if (identity == null) {
                continue;
            }
            if (sourceSystem != null && !sourceSystem.isBlank()
                    && !sourceSystem.equals(identity.sourceSystem)) {
                continue;
            }
            if (nameKeyword != null && !nameKeyword.isBlank()
                    && (identity.fullName == null || !identity.fullName.contains(nameKeyword))) {
                continue;
            }
            views.add(new StagedView(staged, identity, staged.formalMasterId,
                    staged.provisionalMasterId, WARNING, StagedAttribution.SCOPE_RESTRICTED_QUERY));
        }
        return views;
    }

    @Transactional(readOnly = true)
    public List<StagedAttribution> listActive() {
        return stagingRepo.findByStatusOrderByCreatedAtAsc(StagedAttribution.Status.ACTIVE);
    }

    @Transactional(readOnly = true)
    public StagedAttribution get(UUID id) {
        return stagingRepo.findById(id)
                .orElseThrow(() -> new NotFoundException("暂存归属不存在: " + id));
    }

    /**
     * 发布正式关联：强证据守卫。仅当存在强证据组合的待处理候选对时，
     * 经正式合并决议发布，暂存关闭为 PUBLISHED，并留稳定性评估。
     */
    @Transactional
    public StagedAttribution publish(UUID stagedId, long expectedGraphVersion,
                                     String operator, UUID supplementBatchId) {
        PublishOutcome outcome = doPublish(stagedId, expectedGraphVersion, operator, supplementBatchId);
        recordStability(supplementBatchId, outcome.staged(), BatchStability.Verdict.UPGRADED,
                outcome.pair(), outcome.resolution().id, "强证据组合命中，暂存升级为正式关联");
        return outcome.staged();
    }

    /** 内部发布：只做强证据守卫、正式决议与暂存关闭，稳定性由批次流程统一记录。 */
    @Transactional
    public PublishOutcome doPublish(UUID stagedId, long expectedGraphVersion,
                                    String operator, UUID supplementBatchId) {
        StagedAttribution staged = get(stagedId);
        if (staged.status != StagedAttribution.Status.ACTIVE) {
            throw new ConflictException("暂存归属状态为 " + staged.status + "，不能发布");
        }
        List<CandidatePair> strong = candidateRepo.findPendingInvolving(staged.identityId).stream()
                .filter(p -> p.autoLinkEligible).toList();
        if (strong.isEmpty()) {
            throw new ConflictException("暂存身份尚无强证据组合候选（证件号一致 或 手机+姓名全称+出生年月一致），"
                    + "不允许把弱证据暂存发布为正式关联");
        }
        if (strong.size() > 1) {
            throw new ConflictException("存在 " + strong.size() + " 个强证据候选，主体不唯一，转人工决议");
        }
        CandidatePair pair = strong.get(0);
        Resolution resolution = resolutionService.merge(pair.id, null, expectedGraphVersion,
                "补充资料满足强证据门槛，暂存归属发布为正式关联", operator);

        staged.status = StagedAttribution.Status.PUBLISHED;
        staged.closedAt = Instant.now();
        staged.closedBy = operator;
        staged.closedByBatchId = supplementBatchId;
        staged.resolutionId = resolution.id;
        return new PublishOutcome(stagingRepo.save(staged), pair, resolution);
    }

    /** 撤回暂存（补充资料推翻猜测）：正式图谱从未写入该猜测，撤回无下游污染。 */
    @Transactional
    public StagedAttribution revoke(UUID stagedId, String operator, UUID supplementBatchId, String reason) {
        StagedAttribution staged = doRevoke(stagedId, operator, supplementBatchId);
        recordStability(supplementBatchId, staged, BatchStability.Verdict.OVERTURNED,
                null, null, "补充资料推翻弱证据猜测，撤回暂存归属：" + reason);
        return staged;
    }

    /** 内部撤回：只关闭暂存，稳定性由批次流程统一记录。 */
    @Transactional
    public StagedAttribution doRevoke(UUID stagedId, String operator, UUID supplementBatchId) {
        StagedAttribution staged = get(stagedId);
        if (staged.status != StagedAttribution.Status.ACTIVE) {
            throw new ConflictException("暂存归属状态为 " + staged.status + "，不能撤回");
        }
        staged.status = StagedAttribution.Status.REVOKED;
        staged.closedAt = Instant.now();
        staged.closedBy = operator;
        staged.closedByBatchId = supplementBatchId;
        return stagingRepo.save(staged);
    }

    /** 发布结果：暂存、命中的强证据候选与正式决议。 */
    public record PublishOutcome(StagedAttribution staged, CandidatePair pair, Resolution resolution) {
    }

    private void recordStability(UUID batchId, StagedAttribution staged, BatchStability.Verdict verdict,
                                 CandidatePair pair, UUID resolutionId, String note) {
        if (batchId == null) {
            return;
        }
        BatchRecord original = recordRepo.findByIdentityIdOrderByCreatedAtAsc(staged.identityId).stream()
                .findFirst().orElse(null);
        BatchStability stability = new BatchStability();
        stability.supplementBatchId = batchId;
        stability.originalBatchId = staged.batchId;
        stability.identityId = staged.identityId;
        stability.sourceSystem = staged.sourceSystem;
        stability.sourcePatientId = staged.sourcePatientId;
        stability.originalDecision = original == null ? "INSUFFICIENT_STAGED" : original.decision.name();
        stability.originalScore = original == null ? 0 : original.bestScore;
        stability.reevaluatedScore = pair == null ? 0 : pair.score;
        stability.stagedAttributionId = staged.id;
        stability.candidatePairId = pair == null ? null : pair.id;
        stability.verdict = verdict;
        stability.resolutionId = resolutionId;
        stability.note = note;
        stability.basisJson = writeJson(List.of(note));
        stabilityRepo.save(stability);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 受限查询视图：固定携带临时性与范围提示。 */
    public record StagedView(StagedAttribution attribution, SourceIdentity identity,
                             UUID formalMasterId, UUID provisionalMasterId,
                             String warning, String scope) {
    }
}
