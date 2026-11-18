package com.clinic.mpi.service.ingest;

import com.clinic.mpi.domain.BatchRecord;
import com.clinic.mpi.domain.BatchStability;
import com.clinic.mpi.domain.CandidatePair;
import com.clinic.mpi.domain.IngestionBatch;
import com.clinic.mpi.domain.Resolution;
import com.clinic.mpi.domain.SourceIdentity;
import com.clinic.mpi.domain.StagedAttribution;
import com.clinic.mpi.repo.BatchRecordRepo;
import com.clinic.mpi.repo.BatchStabilityRepo;
import com.clinic.mpi.repo.CandidatePairRepo;
import com.clinic.mpi.repo.IngestionBatchRepo;
import com.clinic.mpi.repo.SourceIdentityRepo;
import com.clinic.mpi.service.BadRequestException;
import com.clinic.mpi.service.GraphService;
import com.clinic.mpi.service.IdentityService;
import com.clinic.mpi.service.NotFoundException;
import com.clinic.mpi.service.ResolutionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 渐进式文件批次接入总控，串联并相互约束六个模块职责：
 * <ol>
 *   <li>文件接收：文件指纹 + 记录键双重幂等；</li>
 *   <li>字段契约检测：列名漂移、格式漂移、批量缺失一律把批次置为 RESTRICTED；</li>
 *   <li>候选生成：评分、候选类型与强证据自动链接资格；</li>
 *   <li>暂存 / 正式归属：弱证据进可逆暂存工作区，强证据才进正式图谱；</li>
 *   <li>业务快照资格：暂存归属不写 mpi_link，结构上不可能被快照物化；</li>
 *   <li>批次质量报告：字段覆盖、格式规则、三类决策比例、预计影响与稳定性。</li>
 * </ol>
 */
@Service
public class BatchIngestService {

    private final IngestionBatchRepo batchRepo;
    private final BatchRecordRepo recordRepo;
    private final BatchStabilityRepo stabilityRepo;
    private final SourceIdentityRepo identityRepo;
    private final CandidatePairRepo candidateRepo;
    private final FieldContractDetector detector;
    private final FileFingerprinter fingerprinter;
    private final IdentityService identityService;
    private final GraphService graphService;
    private final StagingService stagingService;
    private final ResolutionService resolutionService;
    private final ObjectMapper objectMapper;

    public BatchIngestService(IngestionBatchRepo batchRepo, BatchRecordRepo recordRepo,
                              BatchStabilityRepo stabilityRepo, SourceIdentityRepo identityRepo,
                              CandidatePairRepo candidateRepo, FieldContractDetector detector,
                              FileFingerprinter fingerprinter, IdentityService identityService,
                              GraphService graphService,
                              StagingService stagingService, ResolutionService resolutionService,
                              ObjectMapper objectMapper) {
        this.batchRepo = batchRepo;
        this.recordRepo = recordRepo;
        this.stabilityRepo = stabilityRepo;
        this.identityRepo = identityRepo;
        this.candidateRepo = candidateRepo;
        this.detector = detector;
        this.fingerprinter = fingerprinter;
        this.identityService = identityService;
        this.graphService = graphService;
        this.stagingService = stagingService;
        this.resolutionService = resolutionService;
        this.objectMapper = objectMapper;
    }

    /** 上传请求（web 层 DTO 转入）。 */
    public record UploadCommand(String batchCode, String sourceSystem, String fileName,
                                String contractVersion, String repairOfBatchCode,
                                List<String> columns, List<Map<String, String>> rows,
                                String operator) {
    }

    /**
     * 接收一个文件批次。同文件指纹重传幂等（不重复解析、不重复建身份）；
     * 契约/格式/批量缺失/更正矛盾时整批受限，不产生任何正式或暂存归属。
     */
    @Transactional
    public IngestionBatch upload(UploadCommand cmd) {
        if (cmd.columns() == null || cmd.columns().isEmpty()) {
            throw new BadRequestException("批次必须声明列名 columns");
        }
        if (cmd.rows() == null || cmd.rows().isEmpty()) {
            throw new BadRequestException("批次至少包含一行记录");
        }
        String fingerprint = fingerprinter.fingerprint(
                cmd.sourceSystem(), cmd.columns(), cmd.rows());

        // 幂等：同文件指纹直接返回原批次，仅累计重传次数
        IngestionBatch existing = batchRepo.findByFileFingerprint(fingerprint).orElse(null);
        if (existing != null) {
            existing.reuploadCount += 1;
            return batchRepo.save(existing);
        }

        // 字段契约检测先行：契约版本是批次必填台账，落库前必须确定
        FieldContractDetector.ContractResult contract =
                detector.detect(cmd.contractVersion(), cmd.columns(), cmd.rows().size());

        IngestionBatch batch = new IngestionBatch();
        batch.batchCode = (cmd.batchCode() == null || cmd.batchCode().isBlank())
                ? "BATCH-" + UUID.randomUUID().toString().substring(0, 8) : cmd.batchCode();
        batch.sourceSystem = cmd.sourceSystem();
        batch.fileName = cmd.fileName() == null ? "unknown.dat" : cmd.fileName();
        batch.fileFingerprint = fingerprint;
        batch.totalRecords = cmd.rows().size();
        batch.graphVersionAtIngest = graphService.currentVersion();
        batch.createdBy = cmd.operator();
        batch.status = IngestionBatch.Status.RECEIVED;
        batch.contractVersion = contract.contractVersion();
        if (cmd.repairOfBatchCode() != null && !cmd.repairOfBatchCode().isBlank()) {
            batch.repairOfBatchId = batchRepo.findByBatchCode(cmd.repairOfBatchCode())
                    .orElseThrow(() -> new NotFoundException(
                            "被修复批次不存在: " + cmd.repairOfBatchCode())).id;
        }
        batchRepo.save(batch);

        // ---- 模块 2：字段契约检测结果 ----
        batch.declaredColumnsJson = toJson(cmd.columns());
        List<FieldContractDetector.QualityIssue> issues = new ArrayList<>(contract.issues());

        // 原始列名 -> 规范字段名
        Map<String, String> rawToCanonical = new LinkedHashMap<>();
        for (FieldContractDetector.ColumnDetection col : contract.columns()) {
            if (col.mapped()) {
                rawToCanonical.put(col.rawColumn(), col.canonicalField());
            }
        }

        // 行解析与格式校验
        List<ParsedRow> parsed = new ArrayList<>();
        Map<String, Integer> blankCounts = new HashMap<>();
        Set<String> presentColumns = contract.presentCanonicalFields();
        Set<String> intraBatchKeys = new LinkedHashSet<>();
        for (Map<String, String> rawRow : cmd.rows()) {
            Map<String, String> canonicalRow = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : rawRow.entrySet()) {
                String canonical = rawToCanonical.get(e.getKey());
                if (canonical != null) {
                    canonicalRow.put(canonical, e.getValue());
                }
            }
            FieldContractDetector.RowValidation validation =
                    detector.validateRow(canonicalRow, presentColumns);
            for (String field : FieldContractDetector.CANONICAL_FIELDS) {
                if (Boolean.FALSE.equals(validation.coverage().get(field))) {
                    blankCounts.merge(field, 1, Integer::sum);
                }
            }
            parsed.add(new ParsedRow(rawRow, validation.normalized(),
                    validation.violations(), validation.coverage()));
        }
        issues.addAll(detector.detectBatchMissing(blankCounts, presentColumns, parsed.size()));

        // 记录键与行级格式问题
        for (ParsedRow row : parsed) {
            String key = row.normalized().get("sourcePatientId");
            if (key == null || key.isBlank()) {
                issues.add(new FieldContractDetector.QualityIssue("FORMAT_VIOLATION",
                        "sourcePatientId", "存在病历号为空的行，记录键不可为空"));
            } else if (!intraBatchKeys.add(cmd.sourceSystem() + "/" + key)) {
                row.duplicate = true; // 文件内重复行：幂等跳过，不算质量事故
            }
            if (!row.violations().isEmpty()) {
                issues.add(new FieldContractDetector.QualityIssue("FORMAT_VIOLATION",
                        "sourcePatientId", "记录 " + key + " 格式违反: " + row.violations()));
            }
        }

        // 更正矛盾预检：补充批次试图改写首批已有值（区别于补齐空字段）
        List<SupplementPlan> supplements = new ArrayList<>();
        for (ParsedRow row : parsed) {
            if (row.duplicate || !row.violations().isEmpty()) {
                continue;
            }
            String key = row.normalized().get("sourcePatientId");
            if (key == null) {
                continue;
            }
            SourceIdentity existed = identityRepo
                    .findBySourceSystemAndSourcePatientId(cmd.sourceSystem(), key).orElse(null);
            if (existed == null) {
                continue;
            }
            Map<String, String> contradictions = new LinkedHashMap<>();
            Map<String, String> fills = new LinkedHashMap<>();
            for (String field : FieldContractDetector.CANONICAL_FIELDS) {
                String incoming = row.normalized().get(field);
                if (incoming == null || field.equals("sourcePatientId")) {
                    continue;
                }
                String current = readField(existed, field);
                if (current == null || current.isBlank()) {
                    fills.put(field, incoming);
                } else if (!current.equals(incoming)) {
                    // 简写姓名补全（包含关系）视为兼容更正，允许走补齐留痕；
                    // 其余字段的不兼容差异一律判矛盾，整批受限
                    if ("fullName".equals(field)
                            && (incoming.contains(current) || current.contains(incoming))) {
                        fills.put(field, incoming);
                    } else {
                        contradictions.put(field, current + " -> " + incoming);
                    }
                }
            }
            if (!contradictions.isEmpty()) {
                issues.add(new FieldContractDetector.QualityIssue("CORRECTION_CONTRADICTION",
                        cmd.sourceSystem() + "/" + key,
                        "补充数据与首批已确认字段矛盾 " + contradictions
                                + "，批次受限待修复，不得静默覆盖首批事实"));
            }
            supplements.add(new SupplementPlan(existed, fills, contradictions));
        }

        // ---- 受限批次：只落台账，不产生身份/候选/暂存，绝不用空值填补放行 ----
        if (!issues.isEmpty()) {
            return restrict(batch, parsed, issues);
        }
        return accept(batch, parsed, supplements, cmd);
    }

    private IngestionBatch accept(IngestionBatch batch, List<ParsedRow> parsed,
                                  List<SupplementPlan> supplements, UploadCommand cmd) {
        Map<String, SupplementPlan> supplementByKey = new LinkedHashMap<>();
        for (SupplementPlan plan : supplements) {
            supplementByKey.put(plan.identity().sourceSystem + "/"
                    + plan.identity().sourcePatientId, plan);
        }

        List<BatchRecord> records = new ArrayList<>();
        Set<UUID> impactedMasters = new LinkedHashSet<>();
        Map<String, Integer> dupCounters = new HashMap<>();
        int autoEligible = 0, autoMerged = 0, newSingletons = 0, staged = 0;
        Map<String, Integer> candidateTypeCounts = new LinkedHashMap<>();
        candidateTypeCounts.put("STRONG_AUTO", 0);
        candidateTypeCounts.put("MANUAL_REVIEW", 0);
        candidateTypeCounts.put("NO_CANDIDATE", 0);

        for (ParsedRow row : parsed) {
            String key = row.normalized().get("sourcePatientId");
            if (row.duplicate) {
                // 文件内同键多行：追加序号保证台账唯一约束，记录幂等跳过不接入
                int seq = dupCounters.merge(key, 1, Integer::sum);
                BatchRecord skipped = baseRecord(batch, row, null);
                skipped.sourcePatientId = key + "#dup" + seq;
                skipped.candidateType = BatchRecord.CandidateType.NO_CANDIDATE;
                skipped.decision = BatchRecord.Decision.DUPLICATE_SKIPPED;
                skipped.bestScore = 0;
                skipped.strongEvidenceJson = toJson(List.of());
                skipped.estimatedImpactMastersJson = toJson(List.of());
                skipped.decisionNote = "文件内重复记录键，幂等跳过";
                records.add(recordRepo.save(skipped));
                continue;
            }

            SupplementPlan supplement = supplementByKey.get(cmd.sourceSystem() + "/" + key);
            if (supplement != null) {
                records.add(evaluateSupplement(batch, row, supplement, cmd.operator()));
                candidateTypeCounts.merge(records.get(records.size() - 1).candidateType.name(),
                        1, Integer::sum);
                continue;
            }

            // ---- 新身份接入（空值显式保留，不填补）----
            Map<String, String> f = row.normalized();
            SourceIdentity identity = identityService.ingestBatch(
                    cmd.sourceSystem(), key, f.get("fullName"), f.get("phone"),
                    f.get("idCardNo"), f.get("gender"),
                    f.get("birthDate") == null ? null : LocalDate.parse(f.get("birthDate")),
                    batch.id, batch.contractVersion);

            List<CandidatePair> pending = candidateRepo.findPendingInvolving(identity.id);
            List<CandidatePair> strong = pending.stream().filter(p -> p.autoLinkEligible).toList();
            CandidatePair best = pending.stream()
                    .max((a, b) -> Double.compare(a.score, b.score)).orElse(null);

            BatchRecord record = baseRecord(batch, row, identity.id);
            record.bestScore = best == null ? 0 : best.score;
            record.candidatePairId = best == null ? null : best.id;
            record.estimatedImpactMastersJson = toJson(pending.stream()
                    .map(p -> otherParty(p, identity.id))
                    .map(graphService::currentMasterOf).map(UUID::toString).distinct().toList());

            if (!strong.isEmpty()) {
                autoEligible++;
                record.candidateType = BatchRecord.CandidateType.STRONG_AUTO;
                candidateTypeCounts.merge("STRONG_AUTO", 1, Integer::sum);
                if (strong.size() == 1) {
                    // 保守门槛下唯一强证据候选：自动链接（正式图谱，留决议痕迹）
                    Resolution resolution = resolutionService.merge(strong.get(0).id, null,
                            graphService.currentVersion(),
                            "批次 " + batch.batchCode + " 强证据组合自动链接", cmd.operator());
                    UUID masterId = graphService.currentMasterOf(identity.id);
                    record.decision = BatchRecord.Decision.AUTO_MERGED;
                    record.masterId = masterId;
                    record.strongEvidenceJson = strong.get(0).evidence;
                    record.decisionNote = "自动合并决议 " + resolution.id + "，证据: "
                            + strong.get(0).evidence;
                    impactedMasters.add(masterId);
                    autoMerged++;
                } else {
                    record.decision = BatchRecord.Decision.MANUAL_REVIEW;
                    record.decisionNote = "存在 " + strong.size() + " 个强证据候选，主体不唯一，转人工";
                }
            } else if (best != null) {
                record.candidateType = BatchRecord.CandidateType.MANUAL_REVIEW;
                candidateTypeCounts.merge("MANUAL_REVIEW", 1, Integer::sum);
                record.decision = BatchRecord.Decision.MANUAL_REVIEW;
                record.decisionNote = "评分 " + best.score + " 过线但不满足强证据组合，仅可人工审阅";
                impactedMasters.add(graphService.currentMasterOf(otherParty(best, identity.id)));
            } else {
                record.candidateType = BatchRecord.CandidateType.NO_CANDIDATE;
                candidateTypeCounts.merge("NO_CANDIDATE", 1, Integer::sum);
                if (hasStrongEvidenceProfile(identity)) {
                    record.decision = BatchRecord.Decision.CONFIRMED_SINGLETON;
                    record.masterId = graphService.currentMasterOf(identity.id);
                    record.decisionNote = "证据要素充分且无候选，可确定为独立新主体";
                    newSingletons++;
                } else {
                    // 弱证据：不包装成正式事实，进入可逆暂存工作区
                    UUID provisional = graphService.currentMasterOf(identity.id);
                    String reason = "证据不足（如仅有简写姓名/旧病历号），暂不正式链接";
                    StagedAttribution stagedEntity = stagingService.stage(
                            identity, batch.id, provisional, List.of(), reason);
                    record.decision = BatchRecord.Decision.INSUFFICIENT_STAGED;
                    record.masterId = stagedEntity.formalMasterId;
                    record.decisionNote = "已写入受限工作区暂存 " + stagedEntity.id
                            + "，仅支持 RESTRICTED_QUERY_ONLY 受限查询";
                    staged++;
                }
            }
            records.add(recordRepo.save(record));
        }

        // ---- 批次质量报告汇总（三类决策比例由记录实态统计，避免两套计数）----
        long confirmed = records.stream()
                .filter(r -> r.decision == BatchRecord.Decision.AUTO_MERGED
                        || r.decision == BatchRecord.Decision.CONFIRMED_SINGLETON).count();
        long manual = records.stream()
                .filter(r -> r.decision == BatchRecord.Decision.MANUAL_REVIEW).count();
        long insufficient = records.stream()
                .filter(r -> r.decision == BatchRecord.Decision.INSUFFICIENT_STAGED).count();
        long duplicates = records.stream()
                .filter(r -> r.decision == BatchRecord.Decision.DUPLICATE_SKIPPED).count();
        batch.status = IngestionBatch.Status.ACCEPTED;
        batch.processedCount = records.size();
        batch.rejectedCount = 0;
        batch.duplicateCount = (int) duplicates;
        batch.confirmedCount = (int) confirmed;
        batch.manualReviewCount = (int) manual;
        batch.insufficientCount = (int) insufficient;
        batch.autoEligibleCount = autoEligible;
        batch.autoMergedCount = autoMerged;
        batch.newSingletonCount = newSingletons;
        batch.stagedCount = staged;
        batch.fieldCoverageJson = toJson(coverageSummary(parsed));
        batch.formatRulesJson = toJson(formatRuleSummary(parsed));
        batch.candidateTypeCountsJson = toJson(candidateTypeCounts);
        batch.qualityIssuesJson = toJson(List.of());
        batch.estimatedImpactJson = toJson(Map.of(
                "impactedMasterIds", impactedMasters.stream().map(UUID::toString).toList(),
                "newSingletons", newSingletons,
                "autoMerged", autoMerged,
                "stagedOnly", staged));
        List<BatchStability> stabilities = stabilityRepo.findBySupplementBatchIdOrderByCreatedAtAsc(batch.id);
        if (!stabilities.isEmpty()) {
            Map<String, Long> verdictCounts = stabilities.stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                            s -> s.verdict.name(), java.util.stream.Collectors.counting()));
            batch.stabilitySummaryJson = toJson(verdictCounts);
        }
        batch.processedAt = Instant.now();
        return batchRepo.save(batch);
    }

    /** 补充批次：补齐首批空字段后，评估首批决策稳定性，而非把新字段当成首批本应拥有的信息。 */
    private BatchRecord evaluateSupplement(IngestionBatch batch, ParsedRow row,
                                           SupplementPlan plan, String operator) {
        SourceIdentity identity = plan.identity();
        BatchRecord original = recordRepo.findByIdentityIdOrderByCreatedAtAsc(identity.id).stream()
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "身份 " + identity.id + " 缺少首批批次记录"));

        Map<String, String> applied = identityService.enrichFields(
                identity.id, plan.fills(), batch.id, operator);

        BatchRecord record = baseRecord(batch, row, identity.id);
        record.candidateType = BatchRecord.CandidateType.NO_CANDIDATE;
        record.decision = original.decision;
        record.bestScore = original.bestScore;
        record.decisionNote = "补充批次：补齐字段 " + applied.keySet()
                + "；首批决策 " + original.decision + " 仅基于首批当时证据，不回溯改写";

        List<CandidatePair> freshCandidates = candidateRepo.findPendingInvolving(identity.id);
        CandidatePair strong = freshCandidates.stream()
                .filter(p -> p.autoLinkEligible).findFirst().orElse(null);
        BatchStability stability = new BatchStability();
        stability.supplementBatchId = batch.id;
        stability.originalBatchId = original.batchId;
        stability.identityId = identity.id;
        stability.sourceSystem = identity.sourceSystem;
        stability.sourcePatientId = identity.sourcePatientId;
        stability.originalDecision = original.decision.name();
        stability.originalScore = original.bestScore;
        stability.candidatePairId = strong == null ? null : strong.id;

        // 矛盾检查：新字段与候选对方/暂存目标的既有事实冲突
        CandidatePair contradictory = findContradiction(identity, freshCandidates, applied);
        StagedAttribution activeStaging = stagingRepoActive(identity.id);

        if (contradictory != null) {
            stability.verdict = BatchStability.Verdict.OVERTURNED;
            stability.reevaluatedScore = contradictory.score;
            stability.basisJson = toJson(List.of("补充字段与候选 " + contradictory.id
                    + " 的对方事实矛盾，首批弱证据候选被推翻，作废并转人工"));
            stability.note = "推翻候选 " + contradictory.id;
            contradictory.status = CandidatePair.Status.SUPERSEDED;
            contradictory.decidedAt = Instant.now();
            contradictory.decidedBy = operator;
            contradictory.decisionReason = "补充资料矛盾，稳定性评估推翻该候选";
            candidateRepo.save(contradictory);
            if (activeStaging != null) {
                StagedAttribution revoked = stagingService.doRevoke(
                        activeStaging.id, operator, batch.id);
                stability.stagedAttributionId = revoked.id;
            }
            record.decision = BatchRecord.Decision.MANUAL_REVIEW;
            record.decisionNote += "；稳定性结论 OVERTURNED，暂存撤回/候选作废，转人工";
        } else if (strong != null) {
            long count = freshCandidates.stream().filter(p -> p.autoLinkEligible).count();
            if (count == 1) {
                stability.verdict = BatchStability.Verdict.UPGRADED;
                stability.reevaluatedScore = strong.score;
                stability.basisJson = toJson(List.of("补充后满足强证据组合 " + strong.evidence
                        + "，首批 " + original.decision + " 升级为可发布正式关联"));
                if (activeStaging != null) {
                    StagingService.PublishOutcome outcome = stagingService.doPublish(
                            activeStaging.id, graphService.currentVersion(), operator, batch.id);
                    stability.stagedAttributionId = outcome.staged().id;
                    stability.resolutionId = outcome.resolution().id;
                } else {
                    Resolution resolution = resolutionService.merge(strong.id, null,
                            graphService.currentVersion(),
                            "补充资料满足强证据门槛，升级首批人工项为正式关联", operator);
                    stability.resolutionId = resolution.id;
                }
                record.candidateType = BatchRecord.CandidateType.STRONG_AUTO;
                record.decision = BatchRecord.Decision.AUTO_MERGED;
                record.masterId = graphService.currentMasterOf(identity.id);
                record.decisionNote += "；稳定性结论 UPGRADED，已发布正式关联";
            } else {
                stability.verdict = BatchStability.Verdict.NEEDS_REVIEW;
                stability.basisJson = toJson(List.of("存在多个强证据候选，主体不唯一，维持人工审阅"));
                record.decision = BatchRecord.Decision.MANUAL_REVIEW;
                record.decisionNote += "；稳定性结论 NEEDS_REVIEW";
            }
        } else if (!freshCandidates.isEmpty()) {
            stability.verdict = BatchStability.Verdict.NEEDS_REVIEW;
            CandidatePair best = freshCandidates.stream()
                    .max((a, b) -> Double.compare(a.score, b.score)).orElseThrow();
            stability.reevaluatedScore = best.score;
            stability.candidatePairId = best.id;
            stability.basisJson = toJson(List.of("证据增强但仍不满足强证据组合，维持人工审阅"));
            record.candidateType = BatchRecord.CandidateType.MANUAL_REVIEW;
            record.candidatePairId = best.id;
            record.decision = BatchRecord.Decision.MANUAL_REVIEW;
            record.decisionNote += "；稳定性结论 NEEDS_REVIEW";
        } else {
            // 无候选：首批正式决策保持稳定；暂存无猜测对象时同样视为稳定
            stability.verdict = BatchStability.Verdict.STABLE;
            stability.reevaluatedScore = original.bestScore;
            stability.basisJson = toJson(List.of("补充字段未产生矛盾候选，首批决策维持不变"));
            record.decisionNote += "；稳定性结论 STABLE";
        }
        stabilityRepo.save(stability);
        return recordRepo.save(record);
    }

    private CandidatePair findContradiction(SourceIdentity identity, List<CandidatePair> candidates,
                                            Map<String, String> applied) {
        if (applied.isEmpty() || !applied.containsKey("birthDate")) {
            return null;
        }
        for (CandidatePair pair : candidates) {
            SourceIdentity other = identityRepo.findById(otherParty(pair, identity.id)).orElse(null);
            if (other != null && other.birthDate != null
                    && !other.birthDate.equals(identity.birthDate)) {
                return pair;
            }
        }
        return null;
    }

    private StagedAttribution stagingRepoActive(UUID identityId) {
        return stagingService.listActive().stream()
                .filter(s -> s.identityId.equals(identityId)).findFirst().orElse(null);
    }

    private IngestionBatch restrict(IngestionBatch batch, List<ParsedRow> parsed,
                                    List<FieldContractDetector.QualityIssue> issues) {
        Set<String> usedKeys = new LinkedHashSet<>();
        int rowIndex = 0;
        for (ParsedRow row : parsed) {
            rowIndex++;
            BatchRecord record = new BatchRecord();
            record.batchId = batch.id;
            record.sourceSystem = batch.sourceSystem;
            String key = row.normalized().get("sourcePatientId");
            if (key == null) {
                key = "<unknown-row-" + rowIndex + ">";
            }
            // 文件内同键多行时追加行序号，避免唯一约束冲突；受限批次只做台账不接入
            String uniqueKey = key;
            int suffix = 1;
            while (!usedKeys.add(uniqueKey)) {
                uniqueKey = key + "#dup" + (suffix++);
            }
            record.sourcePatientId = uniqueKey;
            record.rawFieldsJson = toJson(row.raw());
            record.coverageJson = toJson(row.coverage());
            record.formatViolationsJson = toJson(row.violations());
            record.candidateType = BatchRecord.CandidateType.NO_CANDIDATE;
            record.decision = BatchRecord.Decision.REJECTED;
            record.bestScore = 0;
            record.decisionNote = "批次受限/待修复，该行未接入：" + issues.stream()
                    .map(i -> i.type() + "(" + i.field() + ")").distinct().toList();
            recordRepo.save(record);
        }
        batch.status = IngestionBatch.Status.RESTRICTED;
        batch.processedCount = 0;
        batch.rejectedCount = parsed.size();
        batch.fieldCoverageJson = toJson(coverageSummary(parsed));
        batch.formatRulesJson = toJson(formatRuleSummary(parsed));
        batch.candidateTypeCountsJson = toJson(Map.of(
                "STRONG_AUTO", 0, "MANUAL_REVIEW", 0, "NO_CANDIDATE", 0));
        batch.qualityIssuesJson = toJson(issues);
        batch.estimatedImpactJson = toJson(Map.of(
                "impactedMasterIds", List.of(), "newSingletons", 0,
                "autoMerged", 0, "stagedOnly", 0));
        batch.processedAt = Instant.now();
        return batchRepo.save(batch);
    }

    private BatchRecord baseRecord(IngestionBatch batch, ParsedRow row, UUID identityId) {
        BatchRecord record = new BatchRecord();
        record.batchId = batch.id;
        record.sourceSystem = batch.sourceSystem;
        record.sourcePatientId = row.normalized().get("sourcePatientId");
        record.rawFieldsJson = toJson(row.raw());
        record.coverageJson = toJson(row.coverage());
        record.formatViolationsJson = toJson(row.violations());
        record.identityId = identityId;
        return record;
    }

    private Map<String, Object> coverageSummary(List<ParsedRow> parsed) {
        Map<String, Object> summary = new LinkedHashMap<>();
        for (String field : FieldContractDetector.CANONICAL_FIELDS) {
            long present = parsed.stream().filter(r -> Boolean.TRUE.equals(r.coverage().get(field))).count();
            Map<String, Object> stat = new LinkedHashMap<>();
            stat.put("present", present);
            stat.put("missing", parsed.size() - present);
            stat.put("coverageRate", parsed.isEmpty() ? 0 : Math.round(present * 1000.0 / parsed.size()) / 10.0);
            summary.put(field, stat);
        }
        return summary;
    }

    private Map<String, Object> formatRuleSummary(List<ParsedRow> parsed) {
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("phone", "7-15 位数字");
        rules.put("idCardNo", "15-18 位数字或字母");
        rules.put("gender", "仅 M/F");
        rules.put("birthDate", "ISO yyyy-MM-dd，其他格式判漂移不放行");
        Map<String, Integer> violationCount = new LinkedHashMap<>();
        for (ParsedRow row : parsed) {
            for (String violation : row.violations()) {
                violationCount.merge(violation, 1, Integer::sum);
            }
        }
        rules.put("violations", violationCount);
        return rules;
    }

    private boolean hasStrongEvidenceProfile(SourceIdentity identity) {
        return nonBlank(identity.idCardNo)
                || (nonBlank(identity.phone) && nonBlank(identity.fullName) && identity.birthDate != null);
    }

    private UUID otherParty(CandidatePair pair, UUID identityId) {
        return pair.identityAId.equals(identityId) ? pair.identityBId : pair.identityAId;
    }

    private String readField(SourceIdentity identity, String field) {
        return switch (field) {
            case "fullName" -> identity.fullName;
            case "phone" -> identity.phone;
            case "idCardNo" -> identity.idCardNo;
            case "gender" -> identity.gender;
            case "birthDate" -> identity.birthDate == null ? null : identity.birthDate.toString();
            default -> null;
        };
    }

    @Transactional(readOnly = true)
    public IngestionBatch get(UUID id) {
        return batchRepo.findById(id)
                .orElseThrow(() -> new NotFoundException("批次不存在: " + id));
    }

    @Transactional(readOnly = true)
    public List<IngestionBatch> list() {
        return batchRepo.findAllByOrderByReceivedAtDesc();
    }

    @Transactional(readOnly = true)
    public List<BatchRecord> recordsOf(UUID batchId) {
        get(batchId);
        return recordRepo.findByBatchIdOrderByCreatedAtAsc(batchId);
    }

    @Transactional(readOnly = true)
    public List<BatchStability> stabilityOf(UUID batchId) {
        get(batchId);
        return stabilityRepo.findBySupplementBatchIdOrderByCreatedAtAsc(batchId);
    }

    /** 按批次比较三类决策比例与覆盖指标。 */
    @Transactional(readOnly = true)
    public Map<String, Object> compare(UUID batchAId, UUID batchBId) {
        IngestionBatch a = get(batchAId);
        IngestionBatch b = get(batchBId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batchA", proportions(a));
        result.put("batchB", proportions(b));
        return result;
    }

    private Map<String, Object> proportions(IngestionBatch b) {
        int decided = b.confirmedCount + b.manualReviewCount + b.insufficientCount;
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("batchCode", b.batchCode);
        p.put("status", b.status.name());
        p.put("totalRecords", b.totalRecords);
        p.put("decidedRecords", decided);
        p.put("confirmedLink", ratio(b.confirmedCount, decided));
        p.put("manualReviewOnly", ratio(b.manualReviewCount, decided));
        p.put("insufficientNoLink", ratio(b.insufficientCount, decided));
        p.put("counts", Map.of(
                "confirmed", b.confirmedCount,
                "manualReview", b.manualReviewCount,
                "insufficient", b.insufficientCount,
                "duplicate", b.duplicateCount,
                "rejected", b.rejectedCount));
        return p;
    }

    private double ratio(int part, int total) {
        return total == 0 ? 0 : Math.round(part * 1000.0 / total) / 10.0;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean nonBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** 解析后的行。 */
    private static final class ParsedRow {
        private final Map<String, String> raw;
        private final Map<String, String> normalized;
        private final List<String> violations;
        private final Map<String, Boolean> coverage;
        private boolean duplicate;

        private ParsedRow(Map<String, String> raw, Map<String, String> normalized,
                          List<String> violations, Map<String, Boolean> coverage) {
            this.raw = raw;
            this.normalized = normalized;
            this.violations = violations;
            this.coverage = coverage;
        }

        Map<String, String> raw() {
            return raw;
        }

        Map<String, String> normalized() {
            return normalized;
        }

        List<String> violations() {
            return violations;
        }

        Map<String, Boolean> coverage() {
            return coverage;
        }
    }

    /** 补充批次对既有身份的补齐/矛盾计划。 */
    private record SupplementPlan(SourceIdentity identity, Map<String, String> fills,
                                  Map<String, String> contradictions) {
    }
}
