package com.treasury.clearing.repo;

import com.treasury.clearing.domain.LegalEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LegalEntityRepository extends JpaRepository<LegalEntity, String> {
}
