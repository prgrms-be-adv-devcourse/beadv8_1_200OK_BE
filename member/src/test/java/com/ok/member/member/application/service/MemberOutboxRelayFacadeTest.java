package com.ok.member.member.application.service;

import com.ok.member.member.application.dto.MemberOutboxMessage;
import com.ok.member.member.application.port.out.MemberMessageSender;
import com.ok.member.member.domain.MemberOutboxEventType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

// 정상 발송(실제 Kafka)은 MemberRegisteredKafkaIntegrationTest가 본다. 여기서는 실패했을 때 무엇을 남기고 어디서 멈추는지 본다
@ExtendWith(MockitoExtension.class)
class MemberOutboxRelayFacadeTest {

    private static final int BATCH_SIZE = 100;
    private static final MemberOutboxMessage FIRST = new MemberOutboxMessage(10L, MemberOutboxEventType.MEMBER_REGISTERED, 1L, null, 0);
    private static final MemberOutboxMessage SECOND = new MemberOutboxMessage(11L, MemberOutboxEventType.MEMBER_REGISTERED, 2L, null, 0);

    @Mock
    MemberOutboxService memberOutboxService;

    @Mock
    MemberMessageSender memberMessageSender;

    @InjectMocks
    MemberOutboxRelayFacade sut;

    @Test
    void 발송이_실패하면_실패를_기록하고_그_실행은_멈춰_뒤_줄을_보내지_않는다() {
        // given: 앞 줄이 실패하면 같은 회원의 뒤 줄이 먼저 나갈 수 있어 멈춘다
        given(memberOutboxService.findPending(BATCH_SIZE)).willReturn(List.of(FIRST, SECOND));
        willThrow(new IllegalStateException("회원가입 Kafka 발행에 실패했습니다. memberId=1", new TimeoutException("확인 대기 시간 초과")))
                .given(memberMessageSender).sendMemberRegistered(1L);

        // when
        int sent = sut.relayPending(BATCH_SIZE);

        // then
        assertThat(sent).isZero();
        then(memberOutboxService).should().recordFailure(10L,
                "IllegalStateException: 회원가입 Kafka 발행에 실패했습니다. memberId=1 / TimeoutException: 확인 대기 시간 초과");
        then(memberMessageSender).should(never()).sendMemberRegistered(2L);
        then(memberOutboxService).should(never()).markSent(anyLong());
    }

    @Test
    void 앞_줄을_보낸_뒤_실패하면_앞_줄만_보냄으로_남는다() {
        // given
        given(memberOutboxService.findPending(BATCH_SIZE)).willReturn(List.of(FIRST, SECOND));
        willDoNothing().given(memberMessageSender).sendMemberRegistered(1L);
        willThrow(new IllegalStateException("회원가입 Kafka 발행에 실패했습니다. memberId=2"))
                .given(memberMessageSender).sendMemberRegistered(2L);

        // when
        int sent = sut.relayPending(BATCH_SIZE);

        // then
        assertThat(sent).isEqualTo(1);
        then(memberOutboxService).should().markSent(10L);
        then(memberOutboxService).should(never()).markSent(11L);
        then(memberOutboxService).should().recordFailure(eq(11L), anyString());
    }

    @Test
    void 출고지_우편번호_줄은_저장한_본문을_판매자_키로_보낸다() {
        // given
        MemberOutboxMessage postalCode = new MemberOutboxMessage(
                12L, MemberOutboxEventType.SELLER_ORIGIN_POSTAL_CODE_CHANGED, 7L, "{\"sellerId\":7}", 0);
        given(memberOutboxService.findPending(BATCH_SIZE)).willReturn(List.of(postalCode));

        // when
        int sent = sut.relayPending(BATCH_SIZE);

        // then
        assertThat(sent).isEqualTo(1);
        then(memberMessageSender).should().sendSellerOriginPostalCodeChanged(7L, "{\"sellerId\":7}");
        then(memberOutboxService).should().markSent(12L);
    }

    @Test
    void 보낸_뒤_보냄_기록이_실패하면_예외를_올리고_실패로_기록하지_않는다() {
        // given: 메시지는 이미 나갔으므로 실패 횟수를 늘리지 않는다. 줄은 PENDING으로 남아 다음 실행에서 한 번 더 나간다
        given(memberOutboxService.findPending(BATCH_SIZE)).willReturn(List.of(FIRST));
        willThrow(new IllegalStateException("DB 오류")).given(memberOutboxService).markSent(10L);

        // when & then
        assertThatThrownBy(() -> sut.relayPending(BATCH_SIZE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("DB 오류");
        then(memberOutboxService).should(never()).recordFailure(anyLong(), anyString());
    }

    @Test
    void 실패_기록마저_실패하면_원래_발송_실패를_붙여_예외를_올린다() {
        // given: Kafka와 DB가 함께 실패하는 경우. 발송 실패 원인을 잃지 않아야 한다
        given(memberOutboxService.findPending(BATCH_SIZE)).willReturn(List.of(FIRST));
        IllegalStateException sendFailure = new IllegalStateException("회원가입 Kafka 발행에 실패했습니다. memberId=1");
        willThrow(sendFailure).given(memberMessageSender).sendMemberRegistered(1L);
        willThrow(new DataAccessResourceFailureException("DB 연결 실패"))
                .given(memberOutboxService).recordFailure(eq(10L), anyString());

        // when & then
        assertThatThrownBy(() -> sut.relayPending(BATCH_SIZE))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .hasSuppressedException(sendFailure);
    }

    @Test
    void 실패_원인은_원인_사슬_맨_안쪽까지_따라가_남긴다() {
        // given: 발송기 예외 → ExecutionException → 실제 브로커 오류 순으로 감싼 경우
        RuntimeException e = new IllegalStateException("회원가입 Kafka 발행에 실패했습니다. memberId=1",
                new ExecutionException("감싼 예외", new IllegalArgumentException("토픽 권한 없음")));

        // when
        String sut = MemberOutboxRelayFacade.failureReason(e);

        // then
        assertThat(sut).isEqualTo(
                "IllegalStateException: 회원가입 Kafka 발행에 실패했습니다. memberId=1 / IllegalArgumentException: 토픽 권한 없음");
    }

    @Test
    void 원인이_서로를_가리키는_순환이_있어도_끝나고_실패_원인을_남긴다() {
        // given: A → B → A 순환. 상한이 없으면 끝나지 않아 발송 스케줄러가 멈춘다
        RuntimeException first = new IllegalStateException("앞 예외");
        RuntimeException second = new IllegalArgumentException("뒤 예외", first);
        first.initCause(second);

        // when
        String sut = MemberOutboxRelayFacade.failureReason(first);

        // then: 상한에서 멈춘 위치가 순환의 어느 쪽인지는 상한 값에 따라 달라, 바깥 예외가 남는지만 본다
        assertThat(sut).startsWith("IllegalStateException: 앞 예외");
    }

    @Test
    void 메시지가_없는_예외는_null_대신_예외_이름만_남긴다() {
        // when
        String sut = MemberOutboxRelayFacade.failureReason(new IllegalStateException("발송 실패", new NullPointerException()));

        // then
        assertThat(sut).isEqualTo("IllegalStateException: 발송 실패 / NullPointerException");
    }
}
