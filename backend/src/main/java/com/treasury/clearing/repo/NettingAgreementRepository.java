package com.treasury.clearing.repo;

import com.treasury.clearing.domain.NettingAgreement;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NettingAgreementRepository extends JpaRepository<NettingAgreement, String> {
}
