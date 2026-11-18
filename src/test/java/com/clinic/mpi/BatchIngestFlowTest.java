package com.clinic.mpi;

import com.clinic.mpi.domain.Appointment;
import com.clinic.mpi.domain.BatchRecord;
import com.clinic.mpi.domain.BatchStability;
import com.clinic.mpi.domain.CandidatePair;
import com.clinic.mpi.domain.IngestionBatch;
import com.clinic.mpi.domain.Snapshot;
import com.clinic.mpi.domain.SnapshotMapping;
import com.clinic.mpi.domain.SourceIdentity;
import com.clinic.mpi.domain.StagedAttribution;
import com.clinic.mpi.repo.BatchStabilityRepo;
import com.clinic.mpi.repo.CandidatePairRepo;
import com.clinic.mpi.repo.SnapshotMappingRepo;
import com.clinic.mpi.repo.SourceIdentityRepo;
import com.clinic.mpi.repo.StagedAttributionRepo;
import com.clinic.mpi.service.BizService;
import com.clinic.mpi.service.ConflictException;
import com.clinic.mpi.service.GraphService;
import com.clinic.mpi.service.IdentityService;
import com.clinic.mpi.service.SnapshotService;
import com.clinic.mpi.service.ingest.BatchIngestService;
import com.clinic.mpi.service.ingest.StagingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 渐进式来源接入验收（真实 PostgreSQL，ccdev_test 库）：
 * 简写姓名首批 → 第二批出生年月、重复上传幂等、列名漂移、批量缺失、
 * 更正矛盾、补充资料推翻早期候选、暂存归属隔离与稳定性评估。
 */
@SpringBootTest
class BatchIngestFlowTest {

    @Autowired BatchIngestService batchService;
    @Autowired StagingService stagingService;
    @Autowired IdentityService identityService;
    @Autowired GraphService graphService;
    @Autowired SnapshotService snapshotService;
    @Autowired BizService bizService;
    @Autowired SourceIdentityRepo identityRepo;
    @Autowired StagedAttributionRepo stagedRepo;
    @Autowired CandidatePairRepo candidateRepo;
    @Autowired SnapshotMappingRepo mappingRepo;
    @Autowired BatchStabilityRepo stabilityRepo;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private String uid(String tag) {
        return "T" + SEQ.incrementAndGet() + "-" + tag;
    }

    private Map<String, String> row(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private BatchIngestService.UploadCommand cmd(String source, String contract,
                                                  List<String> columns, List<Map<String, String>> rows) {
        return new BatchIngestService.UploadCommand(null, source, source + ".csv",
                contract, null, columns, rows, "op_batch");
    }

    private BatchRecord recordOf(IngestionBatch batch, String mrn) {
        return batchService.recordsOf(batch.id).stream()
                .filter(r -> r.sourcePatientId.equals(mrn)).findFirst().orElseThrow();
    }

    @Test
    void legacyShortNameBatchStagesThenSupplementUpgradesAndStaysStable() {
        String src = uid("SRC");
        // 正式图谱中已存在的锚点身份（三要素齐全）
        SourceIdentity anchor = identityService.ingest(src + "-ANCHOR", "A-1", "张三",
                "13811112222", null, "M", LocalDate.of(1990, 1, 1));

        // 首批：旧病历只有病历号 + 简写姓名（中文列名别名），无出生年月/手机/证件
        List<String> legacyCols = List.of("病历号", "姓名", "性别");
        List<Map<String, String>> legacyRows = List.of(
                row("病历号", "MRN-101", "姓名", "张", "性别", "M"),
                row("病历号", "MRN-102", "姓名", "李", "性别", "M"));
        IngestionBatch first = batchService.upload(cmd(src, "legacy-v1", legacyCols, legacyRows));

        assertThat(first.status).isEqualTo(IngestionBatch.Status.ACCEPTED);
        assertThat(first.contractVersion).isEqualTo("legacy-v1");
        assertThat(first.insufficientCount).isEqualTo(2);
        assertThat(first.confirmedCount).isZero();
        assertThat(first.stagedCount).isEqualTo(2);
        // 字段覆盖：出生年月 0%，病历号/姓名 100%
        assertThat(first.fieldCoverageJson).contains("\"birthDate\"", "\"coverageRate\":0.0");
        // 简写姓名不产生候选，全部进入暂存工作区
        assertThat(recordOf(first, "MRN-101").decision)
                .isEqualTo(BatchRecord.Decision.INSUFFICIENT_STAGED);
        assertThat(stagingService.listActive()).hasSizeGreaterThanOrEqualTo(2);

        SourceIdentity staged101 = identityRepo
                .findBySourceSystemAndSourcePatientId(src, "MRN-101").orElseThrow();
        StagedAttribution staged = stagedRepo
                .findByIdentityIdAndStatus(staged101.id, StagedAttribution.Status.ACTIVE).orElseThrow();
        // 暂存结果强制带临时性与范围提示
        assertThat(staged.scope).isEqualTo(StagedAttribution.SCOPE_RESTRICTED_QUERY);
        assertThat(staged.warningText).contains("RESTRICTED_QUERY_ONLY");
        List<StagingService.StagedView> views = stagingService.restrictedQuery(src, "张");
        assertThat(views).extracting(v -> v.identity().id).contains(staged101.id);
        assertThat(views.get(0).warning()).contains("不得用于结算");

        // 第二批（enriched-v2）补齐出生年月等：101 与锚点三要素一致 → UPGRADED 发布正式关联
        List<String> enrichedCols = List.of("病历号", "姓名", "手机号", "性别", "出生年月");
        List<Map<String, String>> supplementRows = List.of(
                row("病历号", "MRN-101", "姓名", "张三", "手机号", "13811112222",
                        "性别", "M", "出生年月", "1990-01-01"),
                row("病历号", "MRN-102", "姓名", "李", "手机号", "13833334444",
                        "性别", "M", "出生年月", "1970-07-07"));
        IngestionBatch second = batchService.upload(
                new BatchIngestService.UploadCommand(null, src, src + "-v2.csv",
                        "enriched-v2", null, enrichedCols, supplementRows, "op_batch"));

        assertThat(second.status).isEqualTo(IngestionBatch.Status.ACCEPTED);
        List<BatchStability> stabilities = batchService.stabilityOf(second.id);
        Map<String, BatchStability> byMrn = new LinkedHashMap<>();
        stabilities.forEach(s -> byMrn.put(s.sourcePatientId, s));
        assertThat(byMrn.get("MRN-101").verdict).isEqualTo(BatchStability.Verdict.UPGRADED);
        assertThat(byMrn.get("MRN-102").verdict).isEqualTo(BatchStability.Verdict.STABLE);

        // 101 已发布正式关联：与锚点同主体，暂存关闭，正式决议留痕
        assertThat(graphService.currentMasterOf(staged101.id))
                .isEqualTo(graphService.currentMasterOf(anchor.id));
        assertThat(stagedRepo.findById(staged.id).orElseThrow().status)
                .isEqualTo(StagedAttribution.Status.PUBLISHED);
        // 102 无矛盾无候选：暂存仍生效，正式图谱仍是自己的单例主体
        SourceIdentity identity102 = identityRepo
                .findBySourceSystemAndSourcePatientId(src, "MRN-102").orElseThrow();
        assertThat(stagedRepo.findByIdentityIdAndStatus(
                identity102.id, StagedAttribution.Status.ACTIVE)).isPresent();

        // 首批批次报告不被回溯改写
        IngestionBatch firstReloaded = batchService.get(first.id);
        assertThat(firstReloaded.insufficientCount).isEqualTo(2);
        assertThat(firstReloaded.stabilitySummaryJson).isNull();

        // 三类比例比较可量化
        Map<String, Object> comparison = batchService.compare(first.id, second.id);
        assertThat(comparison).containsKeys("batchA", "batchB");
    }

    @Test
    void duplicateFileUploadIsIdempotentByFingerprint() {
        String src = uid("DUP");
        List<String> cols = List.of("病历号", "姓名");
        List<Map<String, String>> rows = List.of(
                row("病历号", "D-1", "姓名", "周一"),
                row("病历号", "D-1", "姓名", "周一")); // 文件内同键重复行
        IngestionBatch first = batchService.upload(cmd(src, "legacy-v1", cols, rows));
        long identitiesAfterFirst = identityRepo.count();

        IngestionBatch reupload = batchService.upload(cmd(src, "legacy-v1", cols, rows));
        assertThat(reupload.id).isEqualTo(first.id);
        assertThat(reupload.reuploadCount).isEqualTo(1);
        assertThat(identityRepo.count()).isEqualTo(identitiesAfterFirst);
        // 文件内重复行只接一次，另一条幂等跳过
        assertThat(first.duplicateCount).isEqualTo(1);
        assertThat(batchService.recordsOf(first.id)).hasSize(2);
    }

    @Test
    void columnDriftRestrictsBatchAndRepairReuploadSucceeds() {
        String src = uid("DRIFT");
        // 未知列名：不能被旧解析器默默放行
        List<Map<String, String>> badRows = List.of(
                row("old_mrn", "X-1", "病人简写", "赵"));
        IngestionBatch restricted = batchService.upload(
                cmd(src, "legacy-v1", List.of("old_mrn", "病人简写"), badRows));
        assertThat(restricted.status).isEqualTo(IngestionBatch.Status.RESTRICTED);
        assertThat(restricted.qualityIssuesJson).contains("CONTRACT_DRIFT");
        assertThat(identityRepo.findBySourceSystemAndSourcePatientId(src, "X-1")).isEmpty();
        assertThat(restricted.rejectedCount).isEqualTo(1);

        // 修复后以新指纹重传，登记修复关系
        List<Map<String, String>> fixedRows = List.of(row("病历号", "X-1", "姓名", "赵"));
        IngestionBatch fixed = batchService.upload(
                new BatchIngestService.UploadCommand(null, src, "fixed.csv", "legacy-v1",
                        restricted.batchCode, List.of("病历号", "姓名"), fixedRows, "op_batch"));
        assertThat(fixed.status).isEqualTo(IngestionBatch.Status.ACCEPTED);
        assertThat(fixed.repairOfBatchId).isEqualTo(restricted.id);
        assertThat(identityRepo.findBySourceSystemAndSourcePatientId(src, "X-1")).isPresent();
    }

    @Test
    void batchMissingBirthDateUnderEnrichedContractIsRestricted() {
        String src = uid("MISS");
        List<String> cols = List.of("病历号", "姓名", "出生年月");
        List<Map<String, String>> rows = List.of(
                row("病历号", "M-1", "姓名", "孙一", "出生年月", ""),
                row("病历号", "M-2", "姓名", "孙二", "出生年月", ""),
                row("病历号", "M-3", "姓名", "孙三", "出生年月", "1990-01-01"));
        IngestionBatch batch = batchService.upload(cmd(src, "enriched-v2", cols, rows));
        assertThat(batch.status).isEqualTo(IngestionBatch.Status.RESTRICTED);
        assertThat(batch.qualityIssuesJson).contains("MISSING_BATCH_FIELD", "birthDate");
        // 绝不以空值填补放行：没有任何身份落库
        assertThat(identityRepo.findBySourceSystemAndSourcePatientId(src, "M-1")).isEmpty();
    }

    @Test
    void correctionContradictionRestrictsSupplementBatch() {
        String src = uid("CONT");
        IngestionBatch first = batchService.upload(cmd(src, "legacy-v1",
                List.of("病历号", "姓名", "证件号"),
                List.of(row("病历号", "C-1", "姓名", "吴九", "证件号", "110101197703031111"))));
        assertThat(first.status).isEqualTo(IngestionBatch.Status.ACCEPTED);

        // 第二批试图把首批证件号改成另一个值：矛盾，受限待修复，不得静默覆盖
        IngestionBatch contradict = batchService.upload(cmd(src, "legacy-v1",
                List.of("病历号", "姓名", "证件号"),
                List.of(row("病历号", "C-1", "姓名", "吴九", "证件号", "110101197703031112"))));
        assertThat(contradict.status).isEqualTo(IngestionBatch.Status.RESTRICTED);
        assertThat(contradict.qualityIssuesJson).contains("CORRECTION_CONTRADICTION");
        SourceIdentity identity = identityRepo
                .findBySourceSystemAndSourcePatientId(src, "C-1").orElseThrow();
        assertThat(identity.idCardNo).isEqualTo("110101197703031111");
    }

    @Test
    void supplementOverturnsEarlyWeakCandidate() {
        String src = uid("OVER");
        // 既有锚点：手机/性别与旧病历相同，但出生年月不同
        identityService.ingest(src + "-ANCHOR", "W-ANCHOR", "王五",
                "13700009999", null, "M", LocalDate.of(1985, 5, 5));

        // 首批：简写姓名"王" + 同手机 + 同性别 → 0.4 分弱候选，仅人工审阅
        IngestionBatch first = batchService.upload(cmd(src, "legacy-v1",
                List.of("病历号", "姓名", "手机号", "性别"),
                List.of(row("病历号", "W-1", "姓名", "王", "手机号", "13700009999", "性别", "M"))));
        BatchRecord firstRecord = recordOf(first, "W-1");
        assertThat(firstRecord.decision).isEqualTo(BatchRecord.Decision.MANUAL_REVIEW);
        assertThat(firstRecord.candidateType).isEqualTo(BatchRecord.CandidateType.MANUAL_REVIEW);
        CandidatePair weakPair = candidateRepo.findById(firstRecord.candidatePairId).orElseThrow();
        assertThat(weakPair.autoLinkEligible).isFalse();

        // 第二批补出生年月，与锚点矛盾 → 稳定性 OVERTURNED，候选作废转人工
        IngestionBatch second = batchService.upload(cmd(src, "enriched-v2",
                List.of("病历号", "姓名", "手机号", "性别", "出生年月"),
                List.of(row("病历号", "W-1", "姓名", "王", "手机号", "13700009999",
                        "性别", "M", "出生年月", "1992-02-02"))));
        BatchStability stability = batchService.stabilityOf(second.id).get(0);
        assertThat(stability.verdict).isEqualTo(BatchStability.Verdict.OVERTURNED);
        assertThat(candidateRepo.findById(weakPair.id).orElseThrow().status)
                .isEqualTo(CandidatePair.Status.SUPERSEDED);
        // 正式图谱未被弱候选污染：两身份仍是不同主体
        SourceIdentity w1 = identityRepo
                .findBySourceSystemAndSourcePatientId(src, "W-1").orElseThrow();
        SourceIdentity anchor = identityRepo
                .findBySourceSystemAndSourcePatientId(src + "-ANCHOR", "W-ANCHOR").orElseThrow();
        assertThat(graphService.currentMasterOf(w1.id))
                .isNotEqualTo(graphService.currentMasterOf(anchor.id));
    }

    @Test
    void strongEvidenceAutoLinksButStagingNeverFlowsIntoSnapshotOrBiz() {
        String src = uid("ISO");
        // 强证据锚点：证件号一致即可自动链接
        SourceIdentity anchor = identityService.ingest(src + "-ANCHOR", "S-ANCHOR", "郑十",
                "13600000010", "110101198001011234", "M", LocalDate.of(1980, 1, 1));

        IngestionBatch batch = batchService.upload(cmd(src, "legacy-v1",
                List.of("病历号", "姓名", "手机号", "证件号", "性别", "出生年月"),
                List.of(
                        // 强证据：证件号一致 → 自动合并
                        row("病历号", "S-1", "姓名", "郑十", "手机号", "13600000010",
                                "证件号", "110101198001011234", "性别", "M", "出生年月", "1980-01-01"),
                        // 弱证据：仅简写姓名，无手机/证件/DOB → 暂存
                        row("病历号", "S-2", "姓名", "郑"))));
        assertThat(batch.autoMergedCount).isEqualTo(1);
        assertThat(recordOf(batch, "S-1").decision).isEqualTo(BatchRecord.Decision.AUTO_MERGED);
        assertThat(recordOf(batch, "S-2").decision).isEqualTo(BatchRecord.Decision.INSUFFICIENT_STAGED);
        SourceIdentity s1 = identityRepo.findBySourceSystemAndSourcePatientId(src, "S-1").orElseThrow();
        assertThat(graphService.currentMasterOf(s1.id))
                .isEqualTo(graphService.currentMasterOf(anchor.id));

        // 无强证据时发布暂存被拒（409），弱证据不能包装成正式事实
        SourceIdentity s2 = identityRepo.findBySourceSystemAndSourcePatientId(src, "S-2").orElseThrow();
        StagedAttribution staged = stagedRepo
                .findByIdentityIdAndStatus(s2.id, StagedAttribution.Status.ACTIVE).orElseThrow();
        assertThatThrownBy(() -> stagingService.publish(
                staged.id, graphService.currentVersion(), "op_batch", null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("强证据");

        // 发布正式业务快照：物化映射只含正式主体，暂存猜测不进入
        Snapshot snapshot = snapshotService.publish(
                Snapshot.BusinessType.APPOINTMENT, "隔离验证快照-" + src, "op_batch");
        SnapshotMapping mapping = snapshotService
                .resolve(snapshot.id, src, "S-2");
        assertThat(mapping.masterId).isEqualTo(staged.formalMasterId);
        // 所有身份都有正式链接，快照映射数与身份总数一致，不存在"暂存专有"主体映射
        assertThat(mappingRepo.findBySnapshotId(snapshot.id))
                .hasSize((int) identityRepo.count());

        // 下游预约通过正式快照建单，归属为正式单例主体，联系信息不被暂存猜测影响
        Appointment appt = bizService.createAppointment(snapshot.id, src, "S-2",
                Instant.now().plus(2, ChronoUnit.DAYS));
        assertThat(appt.masterId).isEqualTo(staged.formalMasterId);
        // 暂存仍处于 ACTIVE，未流入任何正式业务表
        assertThat(stagedRepo.findById(staged.id).orElseThrow().status)
                .isEqualTo(StagedAttribution.Status.ACTIVE);
        assertThat(stabilityRepo.findAll()).isNotNull();
    }
}
