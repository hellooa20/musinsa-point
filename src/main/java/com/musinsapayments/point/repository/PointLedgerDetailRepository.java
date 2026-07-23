package com.musinsapayments.point.repository;

import com.musinsapayments.point.domain.ledger.PointLedgerDetail;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PointLedgerDetailRepository extends JpaRepository<PointLedgerDetail, Long> {

    List<PointLedgerDetail> findByPointKeyOrderBySequenceNoAsc(String pointKey);

    List<PointLedgerDetail> findBySourceAccrualPointKeyOrderByIdAsc(String sourceAccrualPointKey);

    @Query("""
            select d.sourceAccrualPointKey, coalesce(sum(d.amount), 0)
              from PointLedgerDetail d, PointLedger l
             where d.pointKey = l.pointKey
               and l.pointType = com.musinsapayments.point.domain.ledger.PointType.USE_CANCEL
               and l.referencePointKey = :usePointKey
             group by d.sourceAccrualPointKey
            """)
    List<Object[]> sumCanceledAmountBySource(String usePointKey);
}
