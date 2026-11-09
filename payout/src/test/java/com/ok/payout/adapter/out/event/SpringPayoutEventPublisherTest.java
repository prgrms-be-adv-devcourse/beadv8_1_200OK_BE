package com.ok.payout.adapter.out.event;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.ok.common.event.SellerPayoutCreatedEvent;
import com.ok.payout.application.port.out.SellerPayoutCreated;

import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class SpringPayoutEventPublisherTest {

    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 30, 14, 0);

    @Mock
    ApplicationEventPublisher applicationEventPublisher;

    @InjectMocks
    SpringPayoutEventPublisher sut;

    @Test
    void 정산_확정을_지갑이_받는_Spring_이벤트로_필드마다_같은_값을_담아_발행한다() {
        // given
        // 같은 Long 필드 3개에 서로 다른 값을 넣어, 필드 순서가 뒤바뀌면 실패하게 한다
        SellerPayoutCreated created = new SellerPayoutCreated(12L, 5L, 9_700L, CREATED_AT);

        // when
        sut.publishSellerPayoutCreated(created);

        // then
        then(applicationEventPublisher).should().publishEvent(new SellerPayoutCreatedEvent(12L, 5L, 9_700L, CREATED_AT));
    }
}
