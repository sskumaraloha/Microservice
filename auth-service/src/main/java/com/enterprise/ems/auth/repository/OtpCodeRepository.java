package com.enterprise.ems.auth.repository;

import com.enterprise.ems.auth.domain.OtpCode;
import com.enterprise.ems.auth.domain.OtpPurpose;
import com.enterprise.ems.auth.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OtpCodeRepository extends JpaRepository<OtpCode, Long> {

    List<OtpCode> findByUserAndPurposeOrderByCreatedAtDesc(User user, OtpPurpose purpose);
}
