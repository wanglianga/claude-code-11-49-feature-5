package com.clinic.mpi.repo;

import com.clinic.mpi.domain.BatchRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BatchRecordRepo extends JpaRepository<BatchRecord, UUID> {

    List<BatchRecord> findByBatchIdOrderByCreatedAtAsc(UUID batchId);

    Optional<BatchRecord> findByBatchIdAndSourceSystemAndSourcePatientId(
            UUID batchId, String sourceSystem, String sourcePatientId);

    List<BatchRecord> findByIdentityIdOrderByCreatedAtAsc(UUID identityId);
}
