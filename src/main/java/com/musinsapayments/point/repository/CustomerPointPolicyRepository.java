package com.musinsapayments.point.repository;

import com.musinsapayments.point.domain.policy.CustomerPointPolicy;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface CustomerPointPolicyRepository extends JpaRepository<CustomerPointPolicy, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from CustomerPointPolicy p where p.customerId = :customerId")
    Optional<CustomerPointPolicy> findByCustomerIdForUpdate(long customerId);
}
