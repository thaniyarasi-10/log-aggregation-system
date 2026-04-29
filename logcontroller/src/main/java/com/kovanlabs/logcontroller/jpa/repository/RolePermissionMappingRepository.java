package com.kovanlabs.logcontroller.jpa.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.kovanlabs.logcontroller.model.RolePermissionMapping;

public interface RolePermissionMappingRepository extends JpaRepository<RolePermissionMapping, UUID> {

    @Query("""
            SELECT DISTINCT rp.permission.name
            FROM RolePermissionMapping rp
            WHERE rp.role.id IN (
                SELECT ur.role.id
                FROM UserRoleMapping ur
                WHERE ur.user.id = :userId
            )
            """)
    List<String> findPermissionNamesByUserId(@Param("userId") String userId);
}
