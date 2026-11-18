package com.clinic.mpi.repo;

import com.clinic.mpi.domain.IngestionBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IngestionBatchRepo extends JpaRepository<IngestionBatch, UUID> {

    Optional<IngestionBatch> findByFileFingerprint(String fileFingerprint);

    Optional<IngestionBatch> findByBatchCode(String batchCode);

    List<IngestionBatch> findAllByOrderByReceivedAtDesc();

    List<IngestionBatch> findBySourceSystemOrderByReceivedAtDesc(String sourceSystem);
}
