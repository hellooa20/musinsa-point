package com.musinsapayments.point.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PointProperties.class)
public class PointTimeConfig {

    @Bean
    Clock pointClock(PointProperties properties) {
        return Clock.system(properties.zoneId());
    }
}
