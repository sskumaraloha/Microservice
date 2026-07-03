package com.enterprise.ems.auth.mapper;

import com.enterprise.ems.auth.domain.Role;
import com.enterprise.ems.auth.domain.User;
import com.enterprise.ems.auth.dto.UserResponse;
import org.mapstruct.Mapper;

import java.util.Set;
import java.util.stream.Collectors;

@Mapper(componentModel = "spring")
public interface UserMapper {

    UserResponse toResponse(User user);

    default Set<String> rolesToNames(Set<Role> roles) {
        return roles.stream().map(Role::getName).collect(Collectors.toSet());
    }
}
