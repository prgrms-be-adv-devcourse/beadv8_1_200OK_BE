package com.ok.member.member.adapter.out.event;

import com.ok.common.event.MemberRegisteredEvent;
import com.ok.member.member.application.port.out.MemberOutboxRepository;
import com.ok.member.member.domain.MemberOutbox;
import com.ok.member.member.domain.MemberOutboxEventType;
import com.ok.member.member.domain.MemberOutboxStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class OutboxMemberEventPublisherTest {

    @Mock
    MemberOutboxRepository memberOutboxRepository;

    @Mock
    ApplicationEventPublisher applicationEventPublisher;

    @InjectMocks
    OutboxMemberEventPublisher sut;

    @Test
    void 회원가입_완료를_알리면_보낼_대기_줄을_남기고_장바구니용_스프링_이벤트도_발행한다() {
        // when
        sut.publishMemberRegistered(1L);

        // then
        ArgumentCaptor<MemberOutbox> captor = ArgumentCaptor.forClass(MemberOutbox.class);
        then(memberOutboxRepository).should().save(captor.capture());
        MemberOutbox saved = captor.getValue();
        assertThat(saved).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(saved.getEventType()).isEqualTo(MemberOutboxEventType.MEMBER_REGISTERED);
            softly.assertThat(saved.getMemberId()).isEqualTo(1L);
            softly.assertThat(saved.getStatus()).isEqualTo(MemberOutboxStatus.PENDING);
        });
        then(applicationEventPublisher).should().publishEvent(new MemberRegisteredEvent(1L));
    }

    @Test
    void 회원_id가_null이면_예외가_발생하고_대기_줄도_이벤트도_남기지_않는다() {
        // when & then
        assertThatThrownBy(() -> sut.publishMemberRegistered(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("memberId는 필수입니다.");
        then(memberOutboxRepository).shouldHaveNoInteractions();
        then(applicationEventPublisher).shouldHaveNoInteractions();
    }

}
