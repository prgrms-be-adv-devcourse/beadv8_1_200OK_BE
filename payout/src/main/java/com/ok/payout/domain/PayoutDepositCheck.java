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
import jakarta.persistence.Version;
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

    // 가장 최근에 물은 질문 묶음 번호(checkId). "없음" 답은 이 번호의 답만 처리함 ("있음" 답은 번호와 상관없이 판정, applyAnswer)
    //   어제 질문의 "없음"이 오늘 질문 뒤에 늦게 와도 버려, 옛날 원장의 "없음"이 이미 들어간 돈의 입금 메시지를 다시 보내거나 결과를 뒤집지 않게
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

    // 낙관적 잠금(@Version: 저장할 때 읽은 뒤 누가 먼저 고쳤으면 저장을 거절함)
    // 매일 대사(닫기·묻기)와 지갑 답 처리가 같은 줄을 동시에 고칠 수 있음. JPA는 행 전체를 UPDATE해서, 잠금이 없으면 나중에 커밋한 쪽이 앞의 결과를 통째로 덮음
    //   예: 늦게 온 3번째 답이 "판매자 다름"인데 같은 순간 대사가 "지갑 답 없음"으로 닫으면 판정과 지갑 값이 사라짐
    // 거절되면 지갑 답 쪽은 Kafka 오류 처리기가 다시 시도하고, 대사 쪽은 서비스가 그 단계를 한 번 더 함(PayoutDepositCheckService)
    @Version
    private long version;

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
        // 지금 흐름에서는 대사가 묻기 전에 3번 다 물은 줄을 먼저 닫아 여기 오지 않음. 순서가 바뀌는 코드 실수를 막으려고 둠
        if (attemptCount >= MAX_ATTEMPTS) {
            throw new IllegalStateException("이미 " + MAX_ATTEMPTS + "번 물은 대사 줄입니다. payoutId=" + payoutId);
        }
        attemptCount++;
        lastCheckId = Objects.requireNonNull(checkId, "checkId");
        lastCheckAnswered = false;
        lastCheckedAt = askedAt;
    }

    // 질문을 Kafka로 보내지 못했을 때 recordAsked를 되돌림. 그 질문의 답이 이미 반영됐거나 다른 질문으로 바뀌었으면 건드리지 않음
    // 그 전 질문 번호는 따로 남기지 않아 비움: 그 전 질문의 늦은 "없음"은 원래도 버릴 답이고("있음"은 번호와 상관없이 판정), 다음 날 새 질문을 함
    public boolean undoAsked(Long checkId) {
        if (status != PayoutDepositCheckStatus.WAITING || !Objects.equals(lastCheckId, checkId) || lastCheckAnswered) {
            return false;
        }
        attemptCount--;
        lastCheckId = null;
        // lastCheckedAt은 되돌리지 않음: 그 전 시각을 따로 남기지 않아 되돌릴 값이 없고, 판정에 쓰지 않는 기록용 칸이라 "마지막으로 물으려 한 시각"으로 둠
        return true;
    }

    // 지갑 답 하나를 반영함
    // - 대기 줄에 온 가장 최근 질문의 처음 답: "있음"이면 판정, "없음"이면 횟수를 세고 다시 보냄(3번째면 관리자 확인)
    // - 그 밖의 "있음" 답(이전 질문의 늦은 답, 보내기 실패로 되돌린 질문의 답 등)도 판정함 (사용자 결정, 2026-11-15)
    //     지갑 판매자 원장의 입금 줄은 고쳐지거나 지워지지 않아(wallet SellerWalletLedger @Immutable, PAYOUT:{payoutId} 유니크)
    //     지갑 답은 시간이 지나면 "없음 → 있음"으로만 바뀜. 그래서 옛날 질문의 "있음"도 판매자·금액까지 지금 사실임
    //     버리면 최근 질문의 답이 끝내 안 올 때 입금된 정산이 "지갑 답 없음"으로 잘못 올라가거나, "판매자 다름" 증거가 사라짐
    //   대기 줄: 같으면 일치, 다르면 관리자 확인(판매자 다름·금액 다름)
    //   3번째 질문의 답을 못 받고 시간이 다 되어 닫힌 줄(closeIfNoAttemptsLeft, 이유 "입금 없음"·"지갑 답 없음"): 늦은 "있음"으로 다시 판정함
    //     같으면 일치로 바꿈, 다르면 이유를 판매자 다름·금액 다름으로 바꾸고 지갑 값을 남김(관리자 확인 필요는 그대로)
    //     답이 늦었을 뿐이라, "닫힌 판정은 대사가 고치지 않는다"의 예외로 둠 (payout/CLAUDE.md)
    //   그 밖에 닫힌 줄은 건드리지 않음. 특히 3번째 질문에 지갑이 직접 "없음"으로 답해 닫힌 줄은, 원장이 "없음 → 있음"으로만 바뀌므로
    //     그보다 앞선 질문의 "있음"이 나중에 올 일이 거의 없음
    // - "없음" 답은 가장 최근 질문의 처음 답일 때만 반영함: 옛날 원장일 수 있어 지금은 들어갔을 수 있음
    public PayoutDepositAnswerOutcome applyAnswer(Long checkId, boolean found, Long answeredPayeeId, Long answeredAmount,
                                                  LocalDateTime depositedAt) {
        boolean latestFirstAnswer = status == PayoutDepositCheckStatus.WAITING
                && Objects.equals(lastCheckId, checkId) && !lastCheckAnswered;
        if (latestFirstAnswer) {
            lastCheckAnswered = true;
            if (found) {
                return judgeFound(answeredPayeeId, answeredAmount, depositedAt);
            }
            notFoundCount++;
            if (attemptCount >= MAX_ATTEMPTS) {
                return needsReview(PayoutDepositMismatchReason.NOT_FOUND);
            }
            return PayoutDepositAnswerOutcome.NOT_FOUND_RETRY;
        }
        if (!found) {
            return PayoutDepositAnswerOutcome.IGNORED;
        }
        if (status == PayoutDepositCheckStatus.WAITING) {
            return judgeFound(answeredPayeeId, answeredAmount, depositedAt);
        }
        if (isClosedWithoutLastAnswer()) {
            if (matches(answeredPayeeId, answeredAmount)) {
                matched(answeredPayeeId, answeredAmount, depositedAt);
                mismatchReason = null;
                return PayoutDepositAnswerOutcome.REVIEW_CLEARED;
            }
            // 이유가 판매자 다름·금액 다름으로 바뀌므로 다음 늦은 답에서는 이 조건에 다시 걸리지 않음
            return judgeFound(answeredPayeeId, answeredAmount, depositedAt);
        }
        return PayoutDepositAnswerOutcome.IGNORED;
    }

    // 3번째 질문의 답을 못 받은 채 closeIfNoAttemptsLeft가 닫은 줄인지
    // 마지막 질문의 답을 받았는지(lastCheckAnswered)로 3번째 "없음" 답을 받아 닫힌 줄과 나눔 (새 칸 없이)
    private boolean isClosedWithoutLastAnswer() {
        return status == PayoutDepositCheckStatus.NEEDS_REVIEW
                && !lastCheckAnswered
                && (mismatchReason == PayoutDepositMismatchReason.NO_RESPONSE
                || mismatchReason == PayoutDepositMismatchReason.NOT_FOUND);
    }

    // "있음" 답을 판정함. 판매자가 다르면 돈이 다른 사람에게 간 것이라 금액보다 먼저 봄. 기다려도 저절로 맞춰지지 않아 바로 관리자 확인
    private PayoutDepositAnswerOutcome judgeFound(Long answeredPayeeId, Long answeredAmount, LocalDateTime depositedAt) {
        if (!Objects.equals(payeeId, answeredPayeeId)) {
            recordWalletValues(answeredPayeeId, answeredAmount, depositedAt);
            return needsReview(PayoutDepositMismatchReason.PAYEE_MISMATCH);
        }
        if (!Objects.equals(amount, answeredAmount)) {
            recordWalletValues(answeredPayeeId, answeredAmount, depositedAt);
            return needsReview(PayoutDepositMismatchReason.AMOUNT_MISMATCH);
        }
        return matched(answeredPayeeId, answeredAmount, depositedAt);
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

    private boolean matches(Long answeredPayeeId, Long answeredAmount) {
        return Objects.equals(payeeId, answeredPayeeId) && Objects.equals(amount, answeredAmount);
    }

    private PayoutDepositAnswerOutcome matched(Long answeredPayeeId, Long answeredAmount, LocalDateTime depositedAt) {
        recordWalletValues(answeredPayeeId, answeredAmount, depositedAt);
        status = PayoutDepositCheckStatus.MATCHED;
        return PayoutDepositAnswerOutcome.MATCHED;
    }

    private void recordWalletValues(Long answeredPayeeId, Long answeredAmount, LocalDateTime depositedAt) {
        walletPayeeId = answeredPayeeId;
        walletAmount = answeredAmount;
        walletDepositedAt = depositedAt;
    }

    private PayoutDepositAnswerOutcome needsReview(PayoutDepositMismatchReason reason) {
        status = PayoutDepositCheckStatus.NEEDS_REVIEW;
        mismatchReason = reason;
        return PayoutDepositAnswerOutcome.NEEDS_REVIEW;
    }
}
