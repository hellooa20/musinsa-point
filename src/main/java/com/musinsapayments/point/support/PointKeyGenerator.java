package com.musinsapayments.point.support;

import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class PointKeyGenerator {

    public String generate() {
        return UUID.randomUUID().toString();
    }
}
