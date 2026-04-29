package com.kovanlabs.logcontroller.jpa.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kovanlabs.logcontroller.model.ServiceAccessRequest;
import com.kovanlabs.logcontroller.model.ServiceAccessRequest.RequestStatus;

public interface ServiceAccessRequestRepository extends JpaRepository<ServiceAccessRequest, UUID> {

    List<ServiceAccessRequest> findByStatusOrderByCreatedAtDesc(RequestStatus status);

    List<ServiceAccessRequest> findAllByOrderByCreatedAtDesc();

    List<ServiceAccessRequest> findByRequestedByOrderByCreatedAtDesc(String requestedBy);
}
