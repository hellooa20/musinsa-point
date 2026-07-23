package com.musinsapayments.point.repository;

import com.musinsapayments.point.domain.ledger.PointLedger;
import com.musinsapayments.point.domain.ledger.PointType;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PointLedgerRepository extends JpaRepository<PointLedger, Long> {

    Optional<PointLedger> findByRequestId(String requestId);

    Optional<PointLedger> findByPointKey(String pointKey);

    Optional<PointLedger> findByPointKeyAndCustomerId(String pointKey, long customerId);

    Optional<PointLedger> findByOrderNumber(String orderNumber);

    List<PointLedger> findAllByPointKeyIn(Collection<String> pointKeys);

    @Query("""
            select coalesce(sum(l.remainingAmount), 0)
              from PointLedger l
             where l.customerId = :customerId
               and l.pointType = com.musinsapayments.point.domain.ledger.PointType.ACCRUAL
               and l.remainingAmount > 0
               and l.expiresAt > :now
            """)
    long sumAvailableBalance(long customerId, OffsetDateTime now);

    @Query("""
            select l from PointLedger l
             where l.customerId = :customerId
               and l.pointType = com.musinsapayments.point.domain.ledger.PointType.ACCRUAL
               and l.remainingAmount > 0
               and l.expiresAt > :now
             order by case when l.transactionType =
               com.musinsapayments.point.domain.ledger.AccrualTransactionType.MANUAL
               then 0 else 1 end, l.expiresAt, l.occurredAt, l.id
            """)
    List<PointLedger> findSpendableAccruals(long customerId, OffsetDateTime now);

    @Query("""
            select l from PointLedger l
             where l.referencePointKey = :referencePointKey
               and l.pointType = :pointType
             order by l.occurredAt, l.id
            """)
    List<PointLedger> findByReferencePointKeyAndPointType(
            String referencePointKey, PointType pointType);

    boolean existsByReferencePointKeyAndPointType(String referencePointKey, PointType pointType);

    @Query("""
            select l from PointLedger l
             where l.customerId = :customerId
               and not (l.pointType = com.musinsapayments.point.domain.ledger.PointType.ACCRUAL
                 and l.transactionType = com.musinsapayments.point.domain.ledger.AccrualTransactionType.EXPIRED_USE_REFUND)
               and (:pointType is null or l.pointType = :pointType)
               and (:fromDate is null or l.transactionDate >= :fromDate)
               and (:toDate is null or l.transactionDate <= :toDate)
            """)
    Page<PointLedger> findTopLevelTransactions(
            long customerId, PointType pointType, LocalDate fromDate,
            LocalDate toDate, Pageable pageable);
}
