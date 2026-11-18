package com.clinic.mpi.service.ingest;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 字段契约检测模块（与文件接收、候选生成相互独立）：
 * 负责列名别名归一、契约版本校验、格式规则校验与字段覆盖统计。
 *
 * <p>硬约束：检测不通过时只产出问题清单，由批次模块把整批置为 RESTRICTED，
 * 不以空值填补缺失字段，也不静默换用旧解析器放行漂移列。</p>
 */
@Component
public class FieldContractDetector {

    /** 规范字段名 -> 可接受的上报列名（含历史别名） */
    public static final Map<String, Set<String>> COLUMN_ALIASES;

    /** 旧病历批次契约：仅病历号与姓名必填，出生年月允许缺失 */
    public static final String CONTRACT_LEGACY_V1 = "legacy-v1";
    /** 补充批次契约：出生年月必填 */
    public static final String CONTRACT_ENRICHED_V2 = "enriched-v2";

    public static final List<String> CANONICAL_FIELDS =
            List.of("sourcePatientId", "fullName", "phone", "idCardNo", "gender", "birthDate");

    private static final Map<String, List<String>> REQUIRED_BY_CONTRACT = Map.of(
            CONTRACT_LEGACY_V1, List.of("sourcePatientId", "fullName"),
            CONTRACT_ENRICHED_V2, List.of("sourcePatientId", "fullName", "birthDate"));

    /** 批量缺失阈值：同列空值比例超过该值即判定批量缺失 */
    public static final double BATCH_MISSING_RATIO = 0.50;

    private static final Pattern PHONE = Pattern.compile("\\d{7,15}");
    private static final Pattern ID_CARD = Pattern.compile("[0-9A-Za-z]{15,18}");
    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern PATIENT_ID = Pattern.compile("[^\\s]{1,64}");
    private static final Pattern NAME = Pattern.compile("[^\\s]{1,64}");

    static {
        Map<String, Set<String>> aliases = new LinkedHashMap<>();
        aliases.put("sourcePatientId", Set.of("sourcePatientId", "病历号", "oldPatientId",
                "mrn", "patientId", "source_patient_id"));
        aliases.put("fullName", Set.of("fullName", "姓名", "name", "patientName", "full_name"));
        aliases.put("phone", Set.of("phone", "手机号", "mobile", "contactPhone", "contact_phone"));
        aliases.put("idCardNo", Set.of("idCardNo", "证件号", "idCard", "id_card_no"));
        aliases.put("gender", Set.of("gender", "性别", "sex"));
        aliases.put("birthDate", Set.of("birthDate", "出生年月", "dob", "birth_date"));
        COLUMN_ALIASES = Map.copyOf(aliases);
    }

    /** 单列检测结果 */
    public record ColumnDetection(String rawColumn, String canonicalField, boolean mapped) {
    }

    /** 整批契约检测结果 */
    public record ContractResult(
            String contractVersion,
            List<ColumnDetection> columns,
            Set<String> presentCanonicalFields,
            List<QualityIssue> issues,
            boolean restricted) {
    }

    /** 质量问题（落库到批次 qualityIssuesJson） */
    public record QualityIssue(String type, String field, String detail) {
    }

    /** 单行格式校验结果：violations 为空表示通过；normalized 保留显式 null（不填补） */
    public record RowValidation(Map<String, String> normalized, List<String> violations,
                                Map<String, Boolean> coverage) {
    }

    /** 检测整批列名与契约。 */
    public ContractResult detect(String contractVersion, List<String> rawColumns, int rowCount) {
        String contract = contractVersion == null || contractVersion.isBlank()
                ? CONTRACT_LEGACY_V1 : contractVersion;
        if (!REQUIRED_BY_CONTRACT.containsKey(contract)) {
            throw new IllegalArgumentException("未知字段契约版本: " + contract
                    + "（支持 " + REQUIRED_BY_CONTRACT.keySet() + "）");
        }
        List<ColumnDetection> columns = new java.util.ArrayList<>();
        Set<String> present = new LinkedHashSet<>();
        List<QualityIssue> issues = new java.util.ArrayList<>();

        for (String raw : rawColumns) {
            String canonical = matchCanonical(raw);
            columns.add(new ColumnDetection(raw, canonical, canonical != null));
            if (canonical == null) {
                // 列名漂移：未知列不能被旧解析器默默吞掉
                issues.add(new QualityIssue("CONTRACT_DRIFT", raw,
                        "列名 '" + raw + "' 无法映射到契约字段，疑似字段列名变更，需修复后重传"));
            } else if (!present.add(canonical)) {
                issues.add(new QualityIssue("CONTRACT_DRIFT", raw,
                        "列名 '" + raw + "' 与其他列归一到同一契约字段 " + canonical + "，存在重复/别名混用"));
            }
        }

        for (String required : REQUIRED_BY_CONTRACT.get(contract)) {
            if (!present.contains(required)) {
                issues.add(new QualityIssue("CONTRACT_DRIFT", required,
                        "契约 " + contract + " 要求的必填列缺失: " + required));
            }
        }
        boolean restricted = !issues.isEmpty();
        return new ContractResult(contract, List.copyOf(columns),
                Set.copyOf(present), List.copyOf(issues), restricted);
    }

    /** 校验并归一单行：空值显式保留为 null，绝不填补。 */
    public RowValidation validateRow(Map<String, String> canonicalRow, Set<String> presentColumns) {
        Map<String, String> normalized = new LinkedHashMap<>();
        Map<String, Boolean> coverage = new LinkedHashMap<>();
        List<String> violations = new java.util.ArrayList<>();
        for (String field : CANONICAL_FIELDS) {
            String value = canonicalRow.get(field);
            boolean blank = value == null || value.isBlank();
            coverage.put(field, !blank);
            if (blank) {
                normalized.put(field, null);
                continue;
            }
            String trimmed = value.trim();
            normalized.put(field, trimmed);
            String error = formatError(field, trimmed);
            if (error != null) {
                violations.add(error);
            }
        }
        // 不能用 Map.copyOf：归一化结果显式保留 null（空值不填补），ImmutableCollections 拒绝 null
        return new RowValidation(java.util.Collections.unmodifiableMap(new LinkedHashMap<>(normalized)),
                List.copyOf(violations),
                java.util.Collections.unmodifiableMap(new LinkedHashMap<>(coverage)));
    }

    /** 批量缺失检测：某已声明列空值比例超阈值 → 批量缺失问题。 */
    public List<QualityIssue> detectBatchMissing(Map<String, Integer> blankCountByField,
                                                 Set<String> presentColumns, int rowCount) {
        List<QualityIssue> issues = new java.util.ArrayList<>();
        if (rowCount == 0) {
            return issues;
        }
        for (String field : CANONICAL_FIELDS) {
            if (!presentColumns.contains(field)) {
                continue;
            }
            int blanks = blankCountByField.getOrDefault(field, 0);
            double ratio = (double) blanks / rowCount;
            if (ratio > BATCH_MISSING_RATIO) {
                issues.add(new QualityIssue("MISSING_BATCH_FIELD", field,
                        "字段 " + field + " 批量缺失：" + blanks + "/" + rowCount
                                + " 行为空（比例 " + (int) Math.round(ratio * 100) + "% > 阈值 "
                                + (int) Math.round(BATCH_MISSING_RATIO * 100) + "%），禁止以空值填补放行"));
            }
        }
        return issues;
    }

    private String formatError(String field, String value) {
        return switch (field) {
            case "sourcePatientId" -> PATIENT_ID.matcher(value).matches() ? null
                    : "病历号格式非法（需 1-64 个非空白字符）: " + value;
            case "fullName" -> NAME.matcher(value).matches() ? null
                    : "姓名格式非法（需 1-64 个非空白字符）";
            case "phone" -> PHONE.matcher(value).matches() ? null
                    : "手机号格式非法（需 7-15 位数字）: " + value;
            case "idCardNo" -> ID_CARD.matcher(value).matches() ? null
                    : "证件号格式非法（需 15-18 位数字或字母）: " + value;
            case "gender" -> ("M".equals(value) || "F".equals(value)) ? null
                    : "性别取值非法（仅支持 M/F）: " + value;
            case "birthDate" -> ISO_DATE.matcher(value).matches() && parseableDate(value) ? null
                    : "出生年月格式漂移（仅接受 ISO yyyy-MM-dd，不静默兼容其他格式）: " + value;
            default -> null;
        };
    }

    private boolean parseableDate(String iso) {
        try {
            java.time.LocalDate.parse(iso);
            return true;
        } catch (java.time.DateTimeException e) {
            return false;
        }
    }

    private String matchCanonical(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        for (Map.Entry<String, Set<String>> entry : COLUMN_ALIASES.entrySet()) {
            for (String alias : entry.getValue()) {
                if (alias.equalsIgnoreCase(trimmed)) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }
}
