package com.ok.payout.domain;

import com.ok.common.jpa.entity.BaseTime;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.util.ArrayList;
import java.util.List;

import static jakarta.persistence.GenerationType.IDENTITY;

@Entity
@Immutable
@Table(name = "payout")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payout extends BaseTime {

    @Id
    @GeneratedValue(strategy = IDENTITY)
    private Long id;

    // 이 정산을 확정한 스냅샷 ID
    // 객체(@ManyToOne) 대신 ID 숫자만 둠: 스냅샷 ID는 저장할 때 적고, 발행할 때 "이번 스냅샷 정산만" 고르는 데만 쓰여
    // 스냅샷의 다른 정보(기간 등)를 꺼낼 일이 없음
    // 없는 스냅샷 ID가 들어가지 않는 것은 DB 외래키(fk_payout_snapshot_id)가 막아 줌
    @Column(nullable = false)
    private Long snapshotId;

    // 수취인 회원 ID (판매자). 시스템 몫이면 회원 수취인이 없으므로 null
    private Long payeeId;

    @Column(nullable = false)
    private Long amount;

    // 시스템(플랫폼) 몫 정산 여부 (판매 수수료면 true)
    @Column(name = "is_system", nullable = false)
    private boolean system;

    @OneToMany(mappedBy = "payout", cascade = CascadeType.PERSIST)
    private List<PayoutItem> items = new ArrayList<>();

    // 판매자 몫 정산 (판매 대금·배송비). 판매자 draft 1건 → payout 1건
    // draft 전체가 아니라 항목 목록을 받음: 결과 검증에 걸린 주문의 항목을 commitStep이 거른 뒤 남은 것만 넘김 (#290)
    public static Payout forSeller(Long snapshotId, Long sellerId, List<PayoutSellerDraftItem> draftItems) {
        Payout payout = create(snapshotId, sellerId, false);
        for (PayoutSellerDraftItem item : draftItems) {
            payout.addItem(item.getCandidateId(), item.getEventType(), item.getOrderId(), item.getOrderItemId(),
                    item.getSellerId(), item.getAmount());
        }
        return payout;
    }

    // 시스템 몫 정산 (판매 수수료). 스냅샷의 수수료 항목 전체를 합쳐 1건
    // 회차가 여러 번이어도 commitStep에서 한 번만 호출되므로 스냅샷당 1건
    public static Payout forSystem(Long snapshotId, List<PayoutSystemDraftItem> draftItems) {
        Payout payout = create(snapshotId, null, true);
        for (PayoutSystemDraftItem item : draftItems) {
            payout.addItem(item.getCandidateId(), item.getEventType(), item.getOrderId(), item.getOrderItemId(),
                    item.getSellerId(), item.getAmount());
        }
        return payout;
    }

    // 수취인·시스템 여부 조합은 위 두 메서드로만 만들게 해서 잘못된 조합(DB CHECK 위반)을 막음
    private static Payout create(Long snapshotId, Long payeeId, boolean system) {
        Payout payout = new Payout();
        payout.snapshotId = snapshotId;
        payout.payeeId = payeeId;
        payout.system = system;
        // 합계를 더해 가기 위한 시작값 (최종 금액 0원이 아님). 이후 addItem()이 항목 금액을 하나씩 더함
        // amount는 Long(객체)이라 기본값이 null → 0으로 두지 않으면 첫 addItem()의 += 에서 NullPointerException
        payout.amount = 0L;
        return payout;
    }

    // 항목을 추가하고 합계에 더함
    // 저장(INSERT) 전에 Java 객체 안에서만 더하는 것으로, DB UPDATE가 아님 (@Immutable이라 저장 후에는 바꿀 수 없음)
    //   예) 0 → addItem(10,000) → 10,000 → addItem(3,000) → 13,000 → saveAll 시 amount = 13,000으로 INSERT
    //
    // 판매자 draft 헤더 금액(PayoutSellerDraft.amount)을 복사하지 않고 항목을 다시 더하는 이유
    //   결과 검증에 걸린 주문의 항목을 거르면(#290) 헤더 금액과 정산 금액이 원래 달라지므로 헤더를 쓸 수 없음
    //   거른 항목이 없어도 헤더를 복사하면 장부의 정확성이 "앞에서 verify가 돌았다"는 가정에 의존함
    //     → verify 없이 commit이 불리거나(테스트, #67의 수동 재확정 경로 등) Step 순서·검증 규칙이 바뀌면
    //       payout 금액과 그 payout_item 합계가 어긋난 장부가 저장되고, @Immutable이라 고칠 수도 없음
    //   항목을 다시 더하면 누가 어떤 순서로 불러도 "payout 금액 = 자기 항목 합"이 항상 성립 (verify와 이중 방지)
    //   비용은 메모리 안의 덧셈뿐이라 거의 없음
    private void addItem(Long candidateId, PayoutEventType eventType, Long orderId, Long orderItemId, Long sellerId,
                         Long amount) {
        items.add(new PayoutItem(this, candidateId, eventType, orderId, orderItemId, sellerId, amount));
        this.amount += amount;
    }
}
