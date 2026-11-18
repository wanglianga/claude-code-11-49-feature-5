package com.clinic.mpi.repo;

import com.clinic.mpi.domain.StagedAttribution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StagedAttributionRepo extends JpaRepository<StagedAttribution, UUID> {

    List<StagedAttribution> findByStatusOrderByCreatedAtAsc(StagedAttribution.Status status);

    List<StagedAttribution> findByIdentityIdOrderByCreatedAtAsc(UUID identityId);

    Optional<StagedAttribution> findByIdentityIdAndStatus(UUID identityId, StagedAttribution.Status status);

    List<StagedAttribution> findByBatchId(UUID batchId);
}
