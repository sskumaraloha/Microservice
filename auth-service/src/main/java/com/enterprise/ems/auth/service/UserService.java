package com.enterprise.ems.auth.service;

import com.enterprise.ems.auth.dto.UserResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface UserService {

    Page<UserResponse> listUsers(Pageable pageable);

    UserResponse getById(Long id);
}
