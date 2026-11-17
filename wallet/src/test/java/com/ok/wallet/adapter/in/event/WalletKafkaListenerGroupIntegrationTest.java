package com.ok.wallet.adapter.in.event;

import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

// 통합 테스트: 지갑 리스너가 어느 토픽을 어느 그룹으로 받는지 확인 (#669)
// - 토픽 이름과 그룹 이름은 보내는 모듈·운영(밀린 메시지 확인, 다시 읽기)과의 약속이라 상수가 아니라 글자로 적어 고정한다
// - 그룹을 리스너마다 나눈 것이 핵심이라, 한 그룹을 두 리스너가 함께 쓰면 실패한다
class WalletKafkaListenerGroupIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    KafkaListenerEndpointRegistry registry;

    @Test
    void 지갑_리스너는_v1_토픽을_리스너마다_다른_그룹으로_받는다() {
        // when: 지갑 그룹(wallet.으로 시작)으로 받는 리스너의 그룹을 토픽별로 모은다. 옛 그룹 wallet으로 돌아가면 빠져서 실패하고,
        // 같은 토픽을 받는 리스너가 늘면 그 토픽의 그룹이 여러 개로 보여 실패한다
        Map<String, List<String>> sut = registry.getListenerContainers().stream()
                .filter(container -> container.getGroupId() != null && container.getGroupId().startsWith("wallet."))
                .collect(Collectors.groupingBy(
                        container -> String.join(",", container.getContainerProperties().getTopics()),
                        Collectors.mapping(MessageListenerContainer::getGroupId, Collectors.toList())));

        // then
        assertThat(sut).containsExactlyInAnyOrderEntriesOf(Map.of(
                "payout.seller-payout-created.v1", List.of("wallet.payout-deposit"),
                "refund.refund-approved.v1", List.of("wallet.refund-return"),
                "payout.payout-deposit-check-requested.v1", List.of("wallet.deposit-check-reply"),
                "order.order-created.v1", List.of("wallet.order-payment")));
    }
}
