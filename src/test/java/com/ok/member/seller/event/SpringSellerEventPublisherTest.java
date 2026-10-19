package com.ok.member.seller.event;

import com.ok.common.event.SellerApprovedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class SpringSellerEventPublisherTest {

    @Mock
    ApplicationEventPublisher applicationEventPublisher;

    @InjectMocks
    SpringSellerEventPublisher sut;

    @Test
    void 판매자_승인을_알리면_회원_id와_승인_일시로_승인_이벤트를_만들어_발행한다() {
        // given
        LocalDateTime approvedAt = LocalDateTime.of(2026, 10, 20, 11, 0);

        // when
        sut.publishSellerApproved(1L, approvedAt);

        // then
        then(applicationEventPublisher).should()
                .publishEvent(new SellerApprovedEvent(1L, approvedAt));
    }

    @Test
    void 승인_일시가_null이면_예외가_발생하고_승인_이벤트를_발행하지_않는다() {
        // when & then
        assertThatThrownBy(() -> sut.publishSellerApproved(1L, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("approvedAt은 필수입니다.");
        then(applicationEventPublisher).shouldHaveNoInteractions();
    }

}
