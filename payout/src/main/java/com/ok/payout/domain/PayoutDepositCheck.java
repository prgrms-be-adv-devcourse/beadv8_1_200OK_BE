package com.ok.payout.domain;

import com.ok.common.jpa.entity.BaseTime;
import com.ok.payout.PayoutSchema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Objects;

import static jakarta.persistence.GenerationType.IDENTITY;

// 정산·지갑 대사 줄 1개 = 지갑 입금을 확인할 정산 1건 (#619, 상위 #308)
// 정산 확정 때 outbox 줄과 함께 확인 대기(WAITING)로 넣고, 매일 대사가 지갑에 물어 답으로 판정함
// 정산 표 중 예외로 수정을 허용함 (@Immutable 아님, payout/CLAUDE.md):
//   돈의 원본(정산 장부·지갑 원장)이 아니라 "지갑 확인이 어디까지 갔는지"를 담는 진행 상태 표라 outbox와 성격이 같음
//   확인할 때마다 새 줄을 쌓으면 "지금 확인 대기인 정산"을 고를 때 정산마다 마지막 줄을 찾아야 해서 조회가 복잡해짐
@Entity
@Table(name = "payout_deposit_check", catalog = PayoutSchema.NAME)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PayoutDepositCheck extends BaseTime {

    // 지갑에 물어보는 최대 횟수 (지갑과 합의, PR #548 문서 4-1절). 이만큼 물어도 입금이 확인되지 않으면 관리자 확인 필요
    public static final int MAX_ATTEMPTS = 3;

    @Id
    @GeneratedValue(strategy = IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long payoutId;

    // 정산이 기대하는 판매자·금액. 정산(payout)은 바뀌지 않아 복사해 둠
    // 질문 메시지를 만들 때와 답을 비교할 때 정산 표를 조인하지 않으려고 (매일 대기 줄을 묶음마다 읽음)
    @Column(nullable = false)
    private Long payeeId;

    @Column(nullable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PayoutDepositCheckStatus status;

    // 지갑에 물어본 횟수. Kafka로 보내기에 성공한 질문만 셈 (보내지 못한 날을 세면 질문이 가지도 않았는데 "지갑 답 없음"이 됨)
    // 답이 오든 안 오든 물을 때 늘림 (답 안 온 날을 세지 않으면 지갑 장애가 길 때 아무도 모르게 쌓임)
    @Column(nullable = false)
    private int attemptCount;

    // 지갑이 "없음"으로 답한 횟수. 3번을 다 물었을 때 이유를 "입금 없음"과 "지갑 답 없음"으로 나누는 데 씀
    @Column(nullable = false)
    private int notFoundCount;

    // 가장 최근에 물은 질문 묶음 번호(checkId). 이 번호의 답만 처리함
    //   어제 질문의 답이 오늘 질문 뒤에 늦게 와도 버려, 늦은 "없음"이 이미 들어간 돈의 입금 메시지를 다시 보내거나 결과를 뒤집지 않게
    private Long lastCheckId;

    // 가장 최근 질문의 답을 처리했는지. Kafka는 같은 메시지를 두 번 전달할 수 있어 두 번째 답을 버리려고 둠
    @Column(nullable = false)
    private boolean lastCheckAnswered;

    private LocalDateTime lastCheckedAt;

    // 관리자 확인 필요일 때만 값이 있음
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private PayoutDepositMismatchReason mismatchReason;

    // 지갑이 "있음"으로 답했을 때 원장 값 (관리자가 정산 값과 비교해 봄)
    private Long walletPayeeId;

    private Long walletAmount;

    private LocalDateTime walletDepositedAt;

    public static PayoutDepositCheck waiting(Long payoutId, Long payeeId, Long amount) {
        PayoutDepositCheck check = new PayoutDepositCheck();
        check.payoutId = Objects.requireNonNull(payoutId, "payoutId");
        check.payeeId = Objects.requireNonNull(payeeId, "payeeId");
        check.amount = Objects.requireNonNull(amount, "amount");
        check.status = PayoutDepositCheckStatus.WAITING;
        return check;
    }

    // 질문을 Kafka로 보내기 직전에 부름 (보낸 뒤가 아님)
    //   지갑은 몇 밀리초 만에 답할 수 있어, 보낸 뒤에 번호를 적으면 그 사이에 온 답이 "최근 질문의 답이 아님"으로 버려짐
    //   보내기에 실패하면 undoAsked로 되돌려, 보내지 못한 날은 물은 횟수에 넣지 않음
    public void recordAsked(Long checkId, LocalDateTime askedAt) {
        if (status != PayoutDepositCheckStatus.WAITING) {
            throw new IllegalStateException("확인 대기가 아닌 대사 줄에는 묻지 않습니다. payoutId=" + payoutId + ", status=" + status);
        }
        attemptCount++;
        lastCheckId = Objects.requireNonNull(checkId, "checkId");
        lastCheckAnswered = false;
        lastCheckedAt = askedAt;
    }

    // 질문을 Kafka로 보내지 못했을 때 recordAsked를 되돌림. 그 질문의 답이 이미 반영됐거나 다른 질문으로 바뀌었으면 건드리지 않음
    // 그 전 질문 번호는 따로 남기지 않아 비움: 그 전 질문의 늦은 답은 원래도 버릴 답이고, 다음 날 새 질문을 함
    public boolean undoAsked(Long checkId) {
        if (status != PayoutDepositCheckStatus.WAITING || !Objects.equals(lastCheckId, checkId) || lastCheckAnswered) {
            return false;
        }
        attemptCount--;
        lastCheckId = null;
        return true;
    }

    // 지갑 답 하나를 반영함. 가장 최근 질문의 처음 온 답만 반영하고 나머지는 IGNORED
    public PayoutDepositAnswerOutcome applyAnswer(Long checkId, boolean found, Long answeredPayeeId, Long answeredAmount,
                                                  LocalDateTime depositedAt) {
        if (status != PayoutDepositCheckStatus.WAITING || !Objects.equals(lastCheckId, checkId) || lastCheckAnswered) {
            return PayoutDepositAnswerOutcome.IGNORED;
        }
        lastCheckAnswered = true;
        if (found) {
            walletPayeeId = answeredPayeeId;
            walletAmount = answeredAmount;
            walletDepositedAt = depositedAt;
            // 판매자가 다르면 돈이 다른 사람에게 간 것이라 금액보다 먼저 봄. 기다려도 저절로 맞춰지지 않아 바로 관리자 확인
            if (!Objects.equals(payeeId, answeredPayeeId)) {
                return needsReview(PayoutDepositMismatchReason.PAYEE_MISMATCH);
            }
            if (!Objects.equals(amount, answeredAmount)) {
                return needsReview(PayoutDepositMismatchReason.AMOUNT_MISMATCH);
            }
            status = PayoutDepositCheckStatus.MATCHED;
            return PayoutDepositAnswerOutcome.MATCHED;
        }
        notFoundCount++;
        if (attemptCount >= MAX_ATTEMPTS) {
            return needsReview(PayoutDepositMismatchReason.NOT_FOUND);
        }
        return PayoutDepositAnswerOutcome.NOT_FOUND_RETRY;
    }

    // 3번을 다 물었는데 마지막 질문의 답까지 오지 않은 줄을 끝냄. 매일 대사가 묻기 전에 부름 (마지막 질문에 하루를 기다려 준 뒤)
    // 한 번이라도 "없음"이 왔으면 입금 없음, 한 번도 답이 없었으면 지갑 답 없음
    public boolean closeIfNoAttemptsLeft() {
        if (status != PayoutDepositCheckStatus.WAITING || attemptCount < MAX_ATTEMPTS) {
            return false;
        }
        needsReview(notFoundCount > 0 ? PayoutDepositMismatchReason.NOT_FOUND : PayoutDepositMismatchReason.NO_RESPONSE);
        return true;
    }

    private PayoutDepositAnswerOutcome needsReview(PayoutDepositMismatchReason reason) {
        status = PayoutDepositCheckStatus.NEEDS_REVIEW;
        mismatchReason = reason;
        return PayoutDepositAnswerOutcome.NEEDS_REVIEW;
    }
}
