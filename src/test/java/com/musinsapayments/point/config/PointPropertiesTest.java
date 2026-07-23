package com.musinsapayments.point.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class PointPropertiesTest {

    @Test
    void 유효한_포인트_설정을_생성한다() {
        PointProperties properties = new PointProperties(100_000L, 365, 7, ZoneId.of("Asia/Seoul"));

        assertThat(properties.maxAccrualAmount()).isEqualTo(100_000L);
        assertThat(properties.defaultValidityDays()).isEqualTo(365);
        assertThat(properties.expiredRefundValidityDays()).isEqualTo(7);
        assertThat(properties.zoneId()).isEqualTo(ZoneId.of("Asia/Seoul"));
    }

    @Test
    void 필수_설정값이_유효하지_않으면_거부한다() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new PointProperties(0L, 365, 7, ZoneId.of("Asia/Seoul")));
    }
}
