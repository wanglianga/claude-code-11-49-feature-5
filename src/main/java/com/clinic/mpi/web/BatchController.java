package com.clinic.mpi.web;

import com.clinic.mpi.domain.BatchRecord;
import com.clinic.mpi.domain.BatchStability;
import com.clinic.mpi.domain.IngestionBatch;
import com.clinic.mpi.service.ingest.BatchIngestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "渐进式来源接入",
        description = "文件批次接收、字段契约检测、候选资格、暂存/正式归属与批次质量报告")
@RestController
@RequestMapping("/api/batches")
public class BatchController {

    private final BatchIngestService batchService;

    public BatchController(BatchIngestService batchService) {
        this.batchService = batchService;
    }

    @Operation(summary = "接收文件批次",
            description = "按文件指纹+记录键幂等；列名漂移/格式漂移/批量缺失/更正矛盾时整批复 RESTRICTED，"
                    + "不以空值填补、不沿用旧解析器放行。contractVersion: legacy-v1（旧病历，DOB 可选）"
                    + " / enriched-v2（补充批次，DOB 必填）")
    @PostMapping
    public IngestionBatch upload(@Valid @RequestBody Dtos.BatchUploadRequest req,
                                 @RequestHeader(name = "X-Operator-Id", defaultValue = "system") String operator) {
        return batchService.upload(new BatchIngestService.UploadCommand(
                req.batchCode(), req.sourceSystem(), req.fileName(), req.contractVersion(),
                req.repairOfBatchCode(), req.columns(), req.rows(), operator));
    }

    @Operation(summary = "批次列表（含字段覆盖与三类决策比例）")
    @GetMapping
    public List<IngestionBatch> list() {
        return batchService.list();
    }

    @Operation(summary = "批次详情（质量报告）")
    @GetMapping("/{id}")
    public IngestionBatch get(@PathVariable UUID id) {
        return batchService.get(id);
    }

    @Operation(summary = "批次记录台账（逐行候选类型、决策与预计影响）")
    @GetMapping("/{id}/records")
    public List<BatchRecord> records(@PathVariable UUID id) {
        return batchService.recordsOf(id);
    }

    @Operation(summary = "补充批次的稳定性评估（STABLE/OVERTURNED/NEEDS_REVIEW/UPGRADED）")
    @GetMapping("/{id}/stability")
    public List<BatchStability> stability(@PathVariable UUID id) {
        return batchService.stabilityOf(id);
    }

    @Operation(summary = "按批次比较三类决策比例",
            description = "可确定链接 confirmedLink / 仅可人工审阅 manualReviewOnly / 证据不足暂不链接 insufficientNoLink")
    @GetMapping("/compare")
    public Map<String, Object> compare(@RequestParam UUID batchAId, @RequestParam UUID batchBId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.putAll(batchService.compare(batchAId, batchBId));
        return body;
    }
}
