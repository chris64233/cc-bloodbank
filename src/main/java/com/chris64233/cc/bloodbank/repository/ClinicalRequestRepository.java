package com.chris64233.cc.bloodbank.repository;

import com.chris64233.cc.bloodbank.domain.ClinicalRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ClinicalRequestRepository extends JpaRepository<ClinicalRequest, Long> {

    Optional<ClinicalRequest> findByRequestNo(String requestNo);
}
