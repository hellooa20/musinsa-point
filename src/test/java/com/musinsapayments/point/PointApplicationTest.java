package com.musinsapayments.point;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class PointApplicationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void contextLoads() {
    }

    @Test
    void H2_락_대기시간은_2초로_설정한다() {
        Integer lockTimeout = jdbcTemplate.queryForObject("call lock_timeout()", Integer.class);

        org.assertj.core.api.Assertions.assertThat(lockTimeout).isEqualTo(2_000);
    }
}
