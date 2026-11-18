package com.clinic.mpi.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** API 请求 DTO。 */
public final class Dtos {

    private Dtos() {
    }

    public record IngestRequest(
            @NotBlank String sourceSystem,
            @NotBlank String sourcePatientId,
            @NotBlank String fullName,
            String phone,
            String idCardNo,
            String gender,
            String birthDate) {
    }

    public record CorrectionRequest(
            @NotBlank String field,
            String newValue,
            String note) {
    }

    public record MergeRequest(
            UUID candidateId,
            List<UUID> identityIds,
            @NotNull Long expectedGraphVersion,
            String reason) {
    }

    public record SplitRequest(
            @NotNull UUID masterId,
            @NotNull List<UUID> splitOutIdentityIds,
            @NotNull Long expectedGraphVersion,
            String reason) {
    }

    public record RejectCandidateRequest(String reason) {
    }

    public record PublishSnapshotRequest(
            @NotBlank String businessType,
            String label) {
    }

    public record RejectSnapshotRequest(String reason) {
    }

    public record CreateAppointmentRequest(
            @NotNull UUID snapshotId,
            @NotBlank String sourceSystem,
            @NotBlank String sourcePatientId,
            @NotNull Instant apptTime) {
    }

    public record CreateSettlementRequest(
            @NotNull UUID snapshotId,
            @NotBlank String sourceSystem,
            @NotBlank String sourcePatientId,
            @NotNull BigDecimal amount,
            @NotBlank String period) {
    }

    public record CreateLabReportRequest(
            @NotNull UUID snapshotId,
            @NotBlank String sourceSystem,
            @NotBlank String sourcePatientId,
            @NotBlank String testName) {
    }

    public record AckImpactRequest(String note) {
    }

    public record ResolveResponse(
            String sourceSystem,
            String sourcePatientId,
            UUID identityId,
            UUID masterId,
            long graphVersion) {
    }

    public record BatchUploadRequest(
            String batchCode,
            @NotBlank String sourceSystem,
            String fileName,
            String contractVersion,
            String repairOfBatchCode,
            @NotNull List<String> columns,
            @NotNull List<Map<String, String>> rows) {
    }

    public record StagingPublishRequest(
            @NotNull Long expectedGraphVersion,
            String reason) {
    }

    public record StagingRevokeRequest(String reason) {
    }
}
