package com.musinsapayments.point.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.musinsapayments.point.application.command.ChangePointPolicyCommand;
import com.musinsapayments.point.application.query.PointPolicyResult;
import com.musinsapayments.point.config.PointProperties;
import com.musinsapayments.point.domain.exception.PointErrorCode;
import com.musinsapayments.point.domain.exception.PointException;
import com.musinsapayments.point.domain.policy.CustomerPointPolicy;
import com.musinsapayments.point.repository.CustomerPointPolicyRepository;
import com.musinsapayments.point.repository.PointLedgerRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PointPolicyServiceTest {

    @Mock
    CustomerPointPolicyRepository policies;

    @Mock
    PointLedgerRepository ledgers;

    @Mock
    Clock clock;

    private PointPolicyService service;

    @BeforeEach
    void setUp() {
        service = new PointPolicyService(
                policies, ledgers, clock, new PointProperties(100_000L, 365, 7, ZoneId.of("Asia/Seoul")));
        given(clock.instant()).willReturn(Instant.parse("2026-07-22T01:00:00Z"));
    }

    @Test
    void 정책이_없으면_생성한다() {
        given(policies.findByCustomerIdForUpdate(100L)).willReturn(Optional.empty());

        PointPolicyResult result = service.change(new ChangePointPolicyCommand(100L, 10_000L));

        assertThat(result).isEqualTo(new PointPolicyResult(100L, 10_000L));
        then(policies).should().save(any(CustomerPointPolicy.class));
    }

    @Test
    void 현재_잔액보다_낮게_한도를_내릴_수_없다() {
        given(policies.findByCustomerIdForUpdate(100L)).willReturn(Optional.of(policy(10_000L)));
        given(ledgers.sumAvailableBalance(eq(100L), any())).willReturn(2_000L);

        assertThatThrownBy(() -> service.change(new ChangePointPolicyCommand(100L, 1_999L)))
                .isInstanceOf(PointException.class)
                .extracting("errorCode").isEqualTo(PointErrorCode.HOLDING_LIMIT_EXCEEDED);
    }

    private CustomerPointPolicy policy(long holdingLimit) {
        return CustomerPointPolicy.create(
                100L, holdingLimit, OffsetDateTime.parse("2026-07-22T10:00:00+09:00"));
    }
}
