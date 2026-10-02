package com.treasury.clearing.repo;

import com.treasury.clearing.domain.Claim;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ClaimRepository extends JpaRepository<Claim, String> {

    List<Claim> findByAgreementCodeOrderByInvoiceNo(String agreementCode);

    List<Claim> findByOffsetBatchIdIsNullOrderByInvoiceNo();
}
