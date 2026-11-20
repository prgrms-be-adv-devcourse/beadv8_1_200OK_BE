package com.ok.member.wish.domain;

import com.ok.common.jpa.entity.BaseTime;
import com.ok.member.MemberSchema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

import static jakarta.persistence.GenerationType.IDENTITY;

/**
 * 회원이 찜한 상품 하나. 회원·상품 쌍마다 한 줄이다.
 * 상품 정보는 두지 않고 상품 ID만 저장한다. member가 product를 부르지 않도록 상품이 있는지도 검사하지 않는다(#817).
 * 해제해도 행을 지우지 않고, 다시 찜하면 같은 행을 되살린다.
 */
@Entity
@Table(name = "member_product_wish", catalog = MemberSchema.NAME)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberProductWish extends BaseTime {

    @Id
    @GeneratedValue(strategy = IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false, updatable = false)
    private Long memberId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    // 목록 정렬 기준. 다시 찜하면 그 시각으로 바꿔 목록 맨 앞에 오게 한다. 처음 찜한 시각은 created_at에 남는다
    @Column(name = "wished_at", nullable = false)
    private LocalDateTime wishedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    private MemberProductWish(Long memberId, Long productId, LocalDateTime wishedAt) {
        if (memberId == null) {
            throw new IllegalArgumentException("memberId는 필수입니다.");
        }
        if (productId == null) {
            throw new IllegalArgumentException("productId는 필수입니다.");
        }
        if (wishedAt == null) {
            throw new IllegalArgumentException("wishedAt은 필수입니다.");
        }
        this.memberId = memberId;
        this.productId = productId;
        this.wishedAt = wishedAt;
    }

    /**
     * 처음 찜한 상품의 행을 만든다.
     */
    public static MemberProductWish wish(Long memberId, Long productId, LocalDateTime wishedAt) {
        return new MemberProductWish(memberId, productId, wishedAt);
    }

    /**
     * 해제했던 찜을 되살린다. 이미 찜한 상태면 아무것도 바꾸지 않는다.
     * 같은 요청이 여러 번 와도 찜한 시각이 바뀌지 않아 목록 순서가 흔들리지 않는다.
     */
    public void rewish(LocalDateTime wishedAt) {
        if (wishedAt == null) {
            throw new IllegalArgumentException("wishedAt은 필수입니다.");
        }
        if (isWished()) {
            return;
        }
        this.deletedAt = null;
        this.wishedAt = wishedAt;
    }

    /**
     * 찜을 해제한다. 이미 해제했으면 처음 해제한 시각을 그대로 둔다.
     */
    public void unwish(LocalDateTime unwishedAt) {
        if (unwishedAt == null) {
            throw new IllegalArgumentException("unwishedAt은 필수입니다.");
        }
        if (!isWished()) {
            return;
        }
        this.deletedAt = unwishedAt;
    }

    public boolean isWished() {
        return deletedAt == null;
    }

}
