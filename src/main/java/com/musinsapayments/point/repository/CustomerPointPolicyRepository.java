package com.musinsapayments.point.repository;

import com.musinsapayments.point.domain.policy.CustomerPointPolicy;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerPointPolicyRepository extends JpaRepository<CustomerPointPolicy, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from CustomerPointPolicy p where p.customerId = :customerId")
    Optional<CustomerPointPolicy> findByCustomerIdForUpdate(long customerId);

    @Modifying
    @Query(value = """
            merge into customer_point_policy (
                customer_id, holding_limit, created_at, updated_at
            ) key (customer_id) values (
                :customerId, :holdingLimit, :now, :now
            )
            """, nativeQuery = true)
    int upsertForInitialCreation(
            @Param("customerId") long customerId,
            @Param("holdingLimit") long holdingLimit,
            @Param("now") OffsetDateTime now);
}
