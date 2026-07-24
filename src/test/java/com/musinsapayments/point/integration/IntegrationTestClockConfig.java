package com.musinsapayments.point.integration;

import com.musinsapayments.point.support.MutableClock;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class IntegrationTestClockConfig {

    @Bean
    @Primary
    MutableClock mutableClock() {
        return new MutableClock(
                Instant.parse("2026-07-22T01:00:00Z"), ZoneId.of("Asia/Seoul"));
    }
}
