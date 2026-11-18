package com.clinic.mpi.repo;

import com.clinic.mpi.domain.BatchStability;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BatchStabilityRepo extends JpaRepository<BatchStability, UUID> {

    List<BatchStability> findBySupplementBatchIdOrderByCreatedAtAsc(UUID supplementBatchId);

    List<BatchStability> findByOriginalBatchIdOrderByCreatedAtAsc(UUID originalBatchId);

    List<BatchStability> findByIdentityIdOrderByCreatedAtAsc(UUID identityId);
}
