package com.kovanlabs.logcontroller.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.kovanlabs.logcontroller.model.UserRoleMapping;

public interface UserRoleMappingRepository extends JpaRepository<UserRoleMapping, UUID> {

    @Query("SELECT ur.role.name FROM UserRoleMapping ur WHERE ur.user.id = :userId")
    List<String> findRoleNamesByUserId(@Param("userId") String userId);

    @Query("SELECT DISTINCT ur.role.name FROM UserRoleMapping ur WHERE ur.user.id = :userId")
    List<String> findDistinctRoleNamesByUserId(@Param("userId") String userId);

    void deleteByUser_Id(String userId);
}
