package com.clinic.mpi.service;

import com.clinic.mpi.domain.CandidatePair;
import com.clinic.mpi.domain.SourceIdentity;
import com.clinic.mpi.repo.CandidatePairRepo;
import com.clinic.mpi.repo.SourceIdentityRepo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 候选匹配：按证据字段（证件号、手机号、姓名、出生日期、性别）加权评分，
 * 达到阈值生成候选重复对，等待人工决议。评分只产生"尚在审核的身份推断"，
 * 不直接改变图谱。
 *
 * <p>渐进式接入下新增<b>自动链接资格</b>判定：只有强证据组合命中的候选对才允许
 * 被批次接入自动合并（{@link #isStrongAuto(Evaluation)}），其余候选一律人工审阅，
 * 保守门槛宁可降低初期连续性也不把弱证据包装成正式身份事实。</p>
 */
@Service
public class MatchService {

    /** 候选对生成阈值 */
    public static final double THRESHOLD = 0.40;

    private final SourceIdentityRepo identityRepo;
    private final CandidatePairRepo candidateRepo;
    private final GraphService graphService;
    private final ObjectMapper objectMapper;

    public MatchService(SourceIdentityRepo identityRepo, CandidatePairRepo candidateRepo,
                        GraphService graphService, ObjectMapper objectMapper) {
        this.identityRepo = identityRepo;
        this.candidateRepo = candidateRepo;
        this.graphService = graphService;
        this.objectMapper = objectMapper;
    }

    /** 两身份的证据画像：评分、命中证据与各字段命中标志，供资格判定与稳定性评估复用。 */
    public record Evaluation(double score, List<String> evidence,
                             boolean idCardMatch, boolean phoneMatch,
                             boolean nameExact, boolean nameSimilar,
                             boolean birthDateMatch, boolean genderMatch,
                             boolean strongAuto) {
    }

    /** 计算两身份的匹配评分与命中证据（保留旧签名，委托 {@link #evaluate}）。 */
    public ScoreResult score(SourceIdentity a, SourceIdentity b) {
        Evaluation e = evaluate(a, b);
        return new ScoreResult(e.score(), e.evidence());
    }

    /** 计算完整证据画像。 */
    public Evaluation evaluate(SourceIdentity a, SourceIdentity b) {
        double score = 0;
        List<String> evidence = new ArrayList<>();
        boolean idCardMatch = false, phoneMatch = false, nameExact = false, nameSimilar = false;
        boolean birthDateMatch = false, genderMatch = false;
        if (nonBlank(a.idCardNo) && a.idCardNo.equals(b.idCardNo)) {
            score += 0.55;
            evidence.add("证件号一致");
            idCardMatch = true;
        }
        if (nonBlank(a.phone) && a.phone.equals(b.phone)) {
            score += 0.25;
            evidence.add("手机号一致");
            phoneMatch = true;
        }
        if (nonBlank(a.fullName) && a.fullName.equals(b.fullName)) {
            score += 0.20;
            evidence.add("姓名一致");
            nameExact = true;
        } else if (nonBlank(a.fullName) && nonBlank(b.fullName)
                && (a.fullName.contains(b.fullName) || b.fullName.contains(a.fullName))) {
            score += 0.10;
            evidence.add("姓名相似");
            nameSimilar = true;
        }
        if (a.birthDate != null && a.birthDate.equals(b.birthDate)) {
            score += 0.10;
            evidence.add("出生日期一致");
            birthDateMatch = true;
        }
        if (nonBlank(a.gender) && a.gender.equals(b.gender)) {
            score += 0.05;
            evidence.add("性别一致");
            genderMatch = true;
        }
        return new Evaluation(Math.min(1.0, score), evidence, idCardMatch, phoneMatch,
                nameExact, nameSimilar, birthDateMatch, genderMatch,
                isStrongAuto(idCardMatch, phoneMatch, nameExact, birthDateMatch));
    }

    /**
     * 强证据组合（自动链接门槛）：
     * <ul>
     *   <li>证件号一致（双方均非空）；或</li>
     *   <li>手机号 + 姓名全称 + 出生年月 三要素同时一致。</li>
     * </ul>
     * 简写姓名只构成"姓名相似"，不满足三要素；家庭共用号码缺姓名全称一致时也不满足。
     */
    public static boolean isStrongAuto(boolean idCardMatch, boolean phoneMatch,
                                       boolean nameExact, boolean birthDateMatch) {
        return idCardMatch || (phoneMatch && nameExact && birthDateMatch);
    }

    /** 画像级强证据判定。 */
    public static boolean isStrongAuto(Evaluation e) {
        return e.strongAuto();
    }

    /**
     * 为新接入（或刚更正）的身份生成候选对。
     * 跳过：已属同一主体的身份、已存在待处理候选对的组合。
     */
    @Transactional
    public List<CandidatePair> generateCandidatesFor(SourceIdentity fresh) {
        return generateCandidatesFor(fresh, null);
    }

    /** 批次接入入口：记录候选对的来源批次与自动链接资格。 */
    @Transactional
    public List<CandidatePair> generateCandidatesFor(SourceIdentity fresh, UUID originBatchId) {
        List<CandidatePair> created = new ArrayList<>();
        for (SourceIdentity other : identityRepo.findAll()) {
            if (other.id.equals(fresh.id)) {
                continue;
            }
            if (sameMaster(fresh.id, other.id)) {
                continue;
            }
            if (candidateRepo.existsPendingBetween(fresh.id, other.id)) {
                continue;
            }
            Evaluation result = evaluate(fresh, other);
            // 浮点累加误差修正（如 0.25+0.10+0.05 实际为 0.39999...）
            if (result.score() >= THRESHOLD - 1e-9) {
                CandidatePair pair = new CandidatePair();
                pair.identityAId = fresh.id;
                pair.identityBId = other.id;
                pair.score = result.score();
                pair.evidence = toJson(result.evidence());
                pair.originBatchId = originBatchId;
                pair.autoLinkEligible = result.strongAuto();
                pair.candidateType = result.strongAuto()
                        ? BatchCandidateType.STRONG_AUTO : BatchCandidateType.MANUAL_REVIEW;
                created.add(candidateRepo.save(pair));
            }
        }
        return created;
    }

    /** 候选类型常量（与 BatchRecord.CandidateType 字面量保持一致）。 */
    public static final class BatchCandidateType {
        public static final String STRONG_AUTO = "STRONG_AUTO";
        public static final String MANUAL_REVIEW = "MANUAL_REVIEW";

        private BatchCandidateType() {
        }
    }

    private boolean sameMaster(UUID a, UUID b) {
        try {
            return graphService.currentMasterOf(a).equals(graphService.currentMasterOf(b));
        } catch (NotFoundException e) {
            return false;
        }
    }

    private String toJson(List<String> evidence) {
        try {
            return objectMapper.writeValueAsString(evidence);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean nonBlank(String s) {
        return s != null && !s.isBlank();
    }

    public record ScoreResult(double score, List<String> evidence) {
    }
}
