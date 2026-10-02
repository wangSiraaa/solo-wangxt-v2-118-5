package com.treasury.clearing.repo;

import com.treasury.clearing.domain.NettingBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NettingBatchRepository extends JpaRepository<NettingBatch, String> {

    List<NettingBatch> findAllByOrderByCreatedAtDesc();

    Optional<NettingBatch> findById(String id);
}
