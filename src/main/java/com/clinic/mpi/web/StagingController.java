package com.clinic.mpi.web;

import com.clinic.mpi.domain.StagedAttribution;
import com.clinic.mpi.service.ingest.StagingService;
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

import java.util.List;
import java.util.UUID;

@Tag(name = "受限工作区（可逆暂存归属）",
        description = "弱证据身份的临时归属：仅 RESTRICTED_QUERY_ONLY 受限查询可见，"
                + "结构上不进入正式图谱/快照/结算/提醒/分析；发布需强证据守卫")
@RestController
@RequestMapping("/api/staging")
public class StagingController {

    private final StagingService stagingService;

    public StagingController(StagingService stagingService) {
        this.stagingService = stagingService;
    }

    @Operation(summary = "生效中的暂存归属列表")
    @GetMapping("/active")
    public List<StagedAttribution> active() {
        return stagingService.listActive();
    }

    @Operation(summary = "受限历史查询",
            description = "只返回工作区临时归属，每条结果强制携带临时性警告与 RESTRICTED_QUERY_ONLY 范围提示")
    @GetMapping("/restricted-query")
    public List<StagingService.StagedView> restrictedQuery(
            @RequestParam(required = false) String sourceSystem,
            @RequestParam(required = false) String nameKeyword) {
        return stagingService.restrictedQuery(sourceSystem, nameKeyword);
    }

    @Operation(summary = "发布正式关联",
            description = "强证据守卫：无强证据组合候选时 409；发布经正式合并决议产生新图谱版本并关闭暂存")
    @PostMapping("/{id}/publish")
    public StagedAttribution publish(@PathVariable UUID id,
                                     @Valid @RequestBody Dtos.StagingPublishRequest req,
                                     @RequestHeader(name = "X-Operator-Id", defaultValue = "system") String operator) {
        return stagingService.publish(id, req.expectedGraphVersion(), operator, null);
    }

    @Operation(summary = "撤回暂存归属",
            description = "补充资料推翻猜测时撤回；正式图谱从未写入该猜测，无下游污染")
    @PostMapping("/{id}/revoke")
    public StagedAttribution revoke(@PathVariable UUID id,
                                    @RequestBody(required = false) Dtos.StagingRevokeRequest req,
                                    @RequestHeader(name = "X-Operator-Id", defaultValue = "system") String operator) {
        return stagingService.revoke(id, operator, null,
                req == null ? "人工撤回暂存归属" : req.reason());
    }
}
