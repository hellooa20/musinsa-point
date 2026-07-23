package com.musinsapayments.point.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.musinsapayments.point.application.command.ChangePointPolicyCommand;
import com.musinsapayments.point.domain.policy.CustomerPointPolicy;
import com.musinsapayments.point.repository.CustomerPointPolicyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PointPolicyIntegrationTest {

    @Autowired
    PointPolicyService service;

    @Autowired
    CustomerPointPolicyRepository policies;

    @Test
    void 같은_고객의_순차적인_동일_한도_변경은_정책_한_행만_남긴다() {
        service.change(new ChangePointPolicyCommand(100L, 10_000L));
        service.change(new ChangePointPolicyCommand(100L, 10_000L));

        CustomerPointPolicy policy = policies.findById(100L).orElseThrow();
        assertThat(policy.getHoldingLimit()).isEqualTo(10_000L);
        assertThat(policies.count()).isEqualTo(1L);
    }
}
