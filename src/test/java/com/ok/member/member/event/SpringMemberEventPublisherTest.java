package com.ok.member.member.event;

import com.ok.common.event.MemberRegisteredEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class SpringMemberEventPublisherTest {

    @Mock
    ApplicationEventPublisher applicationEventPublisher;

    @InjectMocks
    SpringMemberEventPublisher sut;

    @Test
    void 회원가입_완료를_알리면_회원_id로_가입_이벤트를_만들어_발행한다() {
        // when
        sut.publishMemberRegistered(1L);

        // then
        then(applicationEventPublisher).should()
                .publishEvent(new MemberRegisteredEvent(1L));
    }

}
