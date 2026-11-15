package com.ok.payout.domain;

import com.ok.common.jpa.entity.BaseTime;
import com.ok.payout.PayoutSchema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import static jakarta.persistence.GenerationType.IDENTITY;

// 대사 질문 묶음 1개 = 1줄 (#619). id가 지갑과 주고받는 묶음 번호(checkId)
// 번호가 겹치면 "가장 최근 질문의 답만 처리" 규칙이 깨지므로 DB 자동 번호를 씀
// 쌓기만 하는 기록 표라 @Immutable. 보낸 시각은 createdAt(BaseTime)
// Kafka로 보내기에 실패한 묶음도 남음(번호를 먼저 받아야 메시지를 만들 수 있어서). 그 번호를 가리키는 대사 줄은 없음
//   정리 작업은 두지 않음: 보내기에 실패한 날 하루 1줄 수준이고, 실패한 묶음도 "언제 물으려다 실패했는지" 기록이라 남겨 둠. 많아지면 그때 보관 기간을 정함
@Entity
@Immutable
@Table(name = "payout_deposit_check_batch", catalog = PayoutSchema.NAME)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PayoutDepositCheckBatch extends BaseTime {

    // 한 묶음에 담는 정산 최대 수 (지갑과 합의, PR #548 문서 4-2절). Kafka 메시지 한 개의 기본 최대 크기(1MB) 안에 들어가게 정함
    public static final int MAX_ITEM_COUNT = 500;

    @Id
    @GeneratedValue(strategy = IDENTITY)
    private Long id;

    @Column(nullable = false)
    private int itemCount;

    public static PayoutDepositCheckBatch of(int itemCount) {
        if (itemCount < 1 || itemCount > MAX_ITEM_COUNT) {
            throw new IllegalArgumentException("대사 질문 묶음은 1건 이상 " + MAX_ITEM_COUNT + "건 이하입니다. itemCount=" + itemCount);
        }
        PayoutDepositCheckBatch batch = new PayoutDepositCheckBatch();
        batch.itemCount = itemCount;
        return batch;
    }
}
