package com.truthscope.web.repository;

import com.truthscope.web.entity.VerificationTrace;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VerificationTraceRepository extends JpaRepository<VerificationTrace, UUID> {

  List<VerificationTrace> findByVerificationResultId(UUID verificationResultId);
}
