package com.kovanlabs.logcontroller.jpa.repository;

import com.kovanlabs.logcontroller.model.NotificationPreference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, UUID> {

    Optional<NotificationPreference> findByUser_Id(String userId);

    @Query("SELECT np FROM NotificationPreference np WHERE np.user.email = :email")
    Optional<NotificationPreference> findByUserEmail(@Param("email") String email);
}
