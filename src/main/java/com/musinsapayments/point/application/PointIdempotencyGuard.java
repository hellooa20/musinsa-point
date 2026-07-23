package com.musinsapayments.point.application;

import com.musinsapayments.point.domain.exception.PointErrorCode;
import com.musinsapayments.point.domain.exception.PointException;
import com.musinsapayments.point.domain.ledger.AccrualTransactionType;
import com.musinsapayments.point.domain.ledger.PointLedger;
import com.musinsapayments.point.domain.ledger.PointType;
import com.musinsapayments.point.repository.PointLedgerRepository;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;

@Component
public class PointIdempotencyGuard {

    private final PointLedgerRepository ledgers;

    public PointIdempotencyGuard(PointLedgerRepository ledgers) {
        this.ledgers = ledgers;
    }

    // 동일한 적립 요청이면 최초 변경 결과를 반환
    public Optional<PointMutationResult> findAccrualReplay(
            UUID requestId, long customerId, long amount, int validityDays,
            AccrualTransactionType transactionType) {
        return replay(requestId, ledger ->
                ledger.getPointType() == PointType.ACCRUAL
                && ledger.getCustomerId() == customerId
                && ledger.getAmount() == amount
                && ledger.getTransactionType() == transactionType
                && ledger.getReferencePointKey() == null
                && ledger.getExpiresAt().equals(ledger.getOccurredAt().plusDays(validityDays)));
    }

    // 동일한 적립취소 요청이면 최초 변경 결과를 반환
    public Optional<PointMutationResult> findAccrualCancellationReplay(
            UUID requestId, long customerId, String accrualPointKey) {
        return replay(requestId, ledger ->
                ledger.getPointType() == PointType.ACCRUAL_CANCEL
                && ledger.getCustomerId() == customerId
                && Objects.equals(ledger.getReferencePointKey(), accrualPointKey));
    }

    // 동일한 사용 요청이면 최초 변경 결과를 반환
    public Optional<PointMutationResult> findUseReplay(
            UUID requestId, long customerId, String orderNumber, long amount) {
        return replay(requestId, ledger ->
                ledger.getPointType() == PointType.USE
                && ledger.getCustomerId() == customerId
                && ledger.getAmount() == amount
                && Objects.equals(ledger.getOrderNumber(), orderNumber));
    }

    // 동일한 사용취소 요청이면 최초 변경 결과를 반환
    public Optional<PointMutationResult> findUseCancellationReplay(
            UUID requestId, long customerId, String usePointKey,
            String cancelOrderNumber, long amount) {
        return replay(requestId, ledger ->
                ledger.getPointType() == PointType.USE_CANCEL
                && ledger.getCustomerId() == customerId
                && ledger.getAmount() == amount
                && Objects.equals(ledger.getReferencePointKey(), usePointKey)
                && Objects.equals(ledger.getOrderNumber(), cancelOrderNumber));
    }

    // requestId가 존재하면 정규화 입력을 비교하고 최초 결과를 반환
    private Optional<PointMutationResult> replay(
            UUID requestId, Predicate<PointLedger> sameNormalizedInput) {
        return ledgers.findByRequestId(requestId.toString()).map(ledger -> {
            if (!sameNormalizedInput.test(ledger)) {
                throw new PointException(PointErrorCode.REQUEST_ID_CONFLICT);
            }
            return PointMutationResult.from(ledger);
        });
    }
}
