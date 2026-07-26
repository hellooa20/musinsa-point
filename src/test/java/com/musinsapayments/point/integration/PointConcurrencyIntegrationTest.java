package com.musinsapayments.point.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.musinsapayments.point.application.PointAccrualCancellationService;
import com.musinsapayments.point.application.PointAccrualService;
import com.musinsapayments.point.application.PointMutationResult;
import com.musinsapayments.point.application.PointPolicyService;
import com.musinsapayments.point.application.PointQueryService;
import com.musinsapayments.point.application.PointUseCancellationService;
import com.musinsapayments.point.application.PointUseService;
import com.musinsapayments.point.application.command.AccrualCancellationCommand;
import com.musinsapayments.point.application.command.AccrualCommand;
import com.musinsapayments.point.application.command.ChangePointPolicyCommand;
import com.musinsapayments.point.application.command.PointUseCancellationCommand;
import com.musinsapayments.point.application.command.PointUseCommand;
import com.musinsapayments.point.application.query.PointPolicyResult;
import com.musinsapayments.point.domain.exception.PointErrorCode;
import com.musinsapayments.point.domain.exception.PointException;
import com.musinsapayments.point.domain.ledger.PointType;
import com.musinsapayments.point.repository.CustomerPointPolicyRepository;
import com.musinsapayments.point.repository.PointLedgerRepository;
import com.musinsapayments.point.support.MutableClock;
import com.musinsapayments.point.support.PointTestFixture;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@Import(IntegrationTestClockConfig.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PointConcurrencyIntegrationTest {

    @Autowired
    PointPolicyService policyService;

    @Autowired
    PointAccrualService accrualService;

    @Autowired
    PointAccrualCancellationService accrualCancellationService;

    @Autowired
    PointUseService useService;

    @Autowired
    PointUseCancellationService useCancellationService;

    @Autowired
    PointQueryService queryService;

    @Autowired
    PointLedgerRepository ledgers;

    @Autowired
    CustomerPointPolicyRepository policies;

    @Autowired
    MutableClock clock;

    @Test
    void 동일_고객이_같은_잔액을_동시에_사용하면_한_요청만_성공한다() throws Exception {
        createPolicy(100L);
        accrualService.accrueNormal(new AccrualCommand(PointTestFixture.uuid(1), 100L, 1_000L, 365));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();

        Callable<Void> request1 = useTask(
                PointTestFixture.uuid(11), "ORDER-11", ready, start, successes, insufficient);
        Callable<Void> request2 = useTask(
                PointTestFixture.uuid(12), "ORDER-12", ready, start, successes, insufficient);
        runConcurrently(request1, request2, ready, start);

        assertThat(successes).hasValue(1);
        assertThat(insufficient).hasValue(1);
        assertThat(queryService.balance(100L).balance()).isZero();
        assertThat(ledgers.findSpendableAccruals(100L, now())).isEmpty();
    }

    @Test
    void 동일_고객의_같은_requestId_동시_적립은_같은_결과를_재생한다() throws Exception {
        createPolicy(100L);
        AccrualCommand command = new AccrualCommand(PointTestFixture.uuid(21), 100L, 1_000L, 365);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<PointMutationResult> results = new ConcurrentLinkedQueue<>();

        runConcurrently(
                accrualTask(command, ready, start, results),
                accrualTask(command, ready, start, results), ready, start);

        assertThat(results).hasSize(2);
        assertThat(results).extracting(PointMutationResult::pointKey).containsOnly(results.peek().pointKey());
        assertThat(ledgers.count()).isEqualTo(1L);
    }

    @Test
    void 동일_신규_고객의_정책을_동시에_생성해도_한_행만_남는다() throws Exception {
        ChangePointPolicyCommand command = new ChangePointPolicyCommand(100L, 10_000L);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<PointPolicyResult> results = new ConcurrentLinkedQueue<>();

        runConcurrently(
                policyTask(command, ready, start, results),
                policyTask(command, ready, start, results),
                ready, start);

        assertThat(results).hasSize(2)
                .allMatch(result -> result.equals(new PointPolicyResult(100L, 10_000L)));
        assertThat(policies.count()).isEqualTo(1L);
        assertThat(policies.findById(100L).orElseThrow().getHoldingLimit()).isEqualTo(10_000L);
    }

    @Test
    void 동일_고객의_같은_requestId_동시_적립취소는_같은_결과를_재생한다() throws Exception {
        createPolicy(100L);
        PointMutationResult accrual = accrualService.accrueNormal(
                new AccrualCommand(PointTestFixture.uuid(21), 100L, 1_000L, 365));
        AccrualCancellationCommand command = new AccrualCancellationCommand(
                PointTestFixture.uuid(22), 100L, accrual.pointKey());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<PointMutationResult> results = new ConcurrentLinkedQueue<>();

        runConcurrently(
                mutationTask(() -> accrualCancellationService.cancel(command), ready, start, results),
                mutationTask(() -> accrualCancellationService.cancel(command), ready, start, results),
                ready, start);

        assertSameReplayResults(results);
        assertThat(ledgers.findByReferencePointKeyAndPointType(
                accrual.pointKey(), PointType.ACCRUAL_CANCEL)).hasSize(1);
    }

    @Test
    void 동일_고객의_같은_requestId_동시_사용은_같은_결과를_재생한다() throws Exception {
        createPolicy(100L);
        accrualService.accrueNormal(new AccrualCommand(PointTestFixture.uuid(21), 100L, 1_000L, 365));
        PointUseCommand command = new PointUseCommand(
                PointTestFixture.uuid(22), 100L, "ORDER-22", 600L);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<PointMutationResult> results = new ConcurrentLinkedQueue<>();

        runConcurrently(
                mutationTask(() -> useService.use(command), ready, start, results),
                mutationTask(() -> useService.use(command), ready, start, results),
                ready, start);

        assertSameReplayResults(results);
        assertThat(ledgers.findByOrderNumber("ORDER-22")).isPresent();
        assertThat(queryService.balance(100L).balance()).isEqualTo(400L);
    }

    @Test
    void 동일_고객의_같은_requestId_동시_사용취소는_같은_결과를_재생한다() throws Exception {
        createPolicy(100L);
        accrualService.accrueNormal(new AccrualCommand(PointTestFixture.uuid(21), 100L, 1_000L, 365));
        PointMutationResult use = useService.use(
                new PointUseCommand(PointTestFixture.uuid(22), 100L, "ORDER-22", 1_000L));
        PointUseCancellationCommand command = new PointUseCancellationCommand(
                PointTestFixture.uuid(23), 100L, use.pointKey(), "ORDER-22-CANCEL", 600L);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<PointMutationResult> results = new ConcurrentLinkedQueue<>();

        runConcurrently(
                mutationTask(() -> useCancellationService.cancel(command), ready, start, results),
                mutationTask(() -> useCancellationService.cancel(command), ready, start, results),
                ready, start);

        assertSameReplayResults(results);
        assertThat(ledgers.findByReferencePointKeyAndPointType(
                use.pointKey(), PointType.USE_CANCEL)).hasSize(1);
        assertThat(queryService.balance(100L).balance()).isEqualTo(600L);
    }

    @Test
    void 같은_적립에_사용과_적립취소를_동시에_요청하면_정확히_하나만_성공한다() throws Exception {
        createPolicy(100L);
        PointMutationResult accrual = accrualService.accrueNormal(
                new AccrualCommand(PointTestFixture.uuid(31), 100L, 1_000L, 365));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<MutationOutcome> useOutcome = new AtomicReference<>();
        AtomicReference<MutationOutcome> cancelOutcome = new AtomicReference<>();

        Callable<Void> use = outcomeTask(ready, start, useOutcome, PointErrorCode.POINT_BALANCE_INSUFFICIENT,
                () -> useService.use(new PointUseCommand(PointTestFixture.uuid(32), 100L, "ORDER-32", 1_000L)));
        Callable<Void> cancel = outcomeTask(
                ready, start, cancelOutcome, PointErrorCode.ACCRUAL_CANCEL_NOT_ALLOWED, () ->
                accrualCancellationService.cancel(new AccrualCancellationCommand(
                        PointTestFixture.uuid(33), 100L, accrual.pointKey())));
        runConcurrently(use, cancel, ready, start);

        assertThat(isExpectedRaceResult(useOutcome.get(), cancelOutcome.get())).isTrue();
        assertThat(queryService.balance(100L).balance()).isZero();
        assertThat(ledgers.findAll()).filteredOn(ledger -> ledger.getPointType() == PointType.ACCRUAL)
                .allSatisfy(ledger -> assertThat(ledger.getRemainingAmount()).isGreaterThanOrEqualTo(0L));
    }

    @Test
    void 서로_다른_고객의_변경은_각각_성공하고_데이터가_섞이지_않는다() throws Exception {
        policyService.change(new ChangePointPolicyCommand(100L, 10_000L));
        policyService.change(new ChangePointPolicyCommand(200L, 10_000L));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<PointMutationResult> results = new ConcurrentLinkedQueue<>();

        runConcurrently(
                accrualTask(new AccrualCommand(PointTestFixture.uuid(41), 100L, 1_000L, 365), ready, start, results),
                accrualTask(new AccrualCommand(PointTestFixture.uuid(42), 200L, 2_000L, 365), ready, start, results),
                ready, start);

        assertThat(results).extracting(PointMutationResult::customerId).containsExactlyInAnyOrder(100L, 200L);
        assertThat(queryService.balance(100L).balance()).isEqualTo(1_000L);
        assertThat(queryService.balance(200L).balance()).isEqualTo(2_000L);
    }

    private Callable<Void> useTask(
            java.util.UUID requestId, String orderNumber, CountDownLatch ready, CountDownLatch start,
            AtomicInteger successes, AtomicInteger insufficient) {
        return () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시 시작 신호를 받지 못했습니다.");
            }
            try {
                useService.use(new PointUseCommand(requestId, 100L, orderNumber, 1_000L));
                successes.incrementAndGet();
            } catch (PointException exception) {
                if (exception.getErrorCode() != PointErrorCode.POINT_BALANCE_INSUFFICIENT) {
                    throw exception;
                }
                insufficient.incrementAndGet();
            }
            return null;
        };
    }

    private Callable<Void> accrualTask(
            AccrualCommand command, CountDownLatch ready, CountDownLatch start,
            ConcurrentLinkedQueue<PointMutationResult> results) {
        return () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시 시작 신호를 받지 못했습니다.");
            }
            results.add(accrualService.accrueNormal(command));
            return null;
        };
    }

    private Callable<Void> mutationTask(
            Callable<PointMutationResult> mutation, CountDownLatch ready, CountDownLatch start,
            ConcurrentLinkedQueue<PointMutationResult> results) {
        return () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시 시작 신호를 받지 못했습니다.");
            }
            results.add(mutation.call());
            return null;
        };
    }

    private Callable<Void> policyTask(
            ChangePointPolicyCommand command, CountDownLatch ready, CountDownLatch start,
            ConcurrentLinkedQueue<PointPolicyResult> results) {
        return () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시 시작 신호를 받지 못했습니다.");
            }
            results.add(policyService.change(command));
            return null;
        };
    }

    private Callable<Void> outcomeTask(
            CountDownLatch ready, CountDownLatch start, AtomicReference<MutationOutcome> outcome,
            PointErrorCode expectedFailure, Callable<PointMutationResult> mutation) {
        return () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시 시작 신호를 받지 못했습니다.");
            }
            try {
                mutation.call();
                outcome.set(MutationOutcome.SUCCESS);
            } catch (PointException exception) {
                if (exception.getErrorCode() != expectedFailure) {
                    throw exception;
                }
                outcome.set(MutationOutcome.from(exception.getErrorCode()));
            }
            return null;
        };
    }

    private boolean isExpectedRaceResult(MutationOutcome useOutcome, MutationOutcome cancelOutcome) {
        return (useOutcome == MutationOutcome.SUCCESS
                && cancelOutcome == MutationOutcome.ACCRUAL_CANCEL_NOT_ALLOWED)
                || (useOutcome == MutationOutcome.POINT_BALANCE_INSUFFICIENT
                && cancelOutcome == MutationOutcome.SUCCESS);
    }

    private void assertSameReplayResults(ConcurrentLinkedQueue<PointMutationResult> results) {
        assertThat(results).hasSize(2);
        assertThat(results).extracting(PointMutationResult::pointKey).containsOnly(results.peek().pointKey());
    }

    private final void runConcurrently(
            Callable<Void> firstTask, Callable<Void> secondTask, CountDownLatch ready, CountDownLatch start)
            throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Void> first = executor.submit(firstTask);
            Future<Void> second = executor.submit(secondTask);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
    }

    private void createPolicy(long customerId) {
        policyService.change(new ChangePointPolicyCommand(customerId, 10_000L));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneId.of("Asia/Seoul"));
    }

    private enum MutationOutcome {
        SUCCESS,
        POINT_BALANCE_INSUFFICIENT,
        ACCRUAL_CANCEL_NOT_ALLOWED;

        private static MutationOutcome from(PointErrorCode errorCode) {
            return MutationOutcome.valueOf(errorCode.name());
        }
    }
}
