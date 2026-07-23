package com.musinsapayments.point.config;

import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "point")
public record PointProperties(
        long maxAccrualAmount,
        int defaultValidityDays,
        int expiredRefundValidityDays,
        ZoneId zoneId) {

    public PointProperties {
        if (maxAccrualAmount < 1 || defaultValidityDays < 1
                || expiredRefundValidityDays < 1 || zoneId == null) {
            throw new IllegalArgumentException("포인트 설정값을 확인해 주세요.");
        }
    }
}
