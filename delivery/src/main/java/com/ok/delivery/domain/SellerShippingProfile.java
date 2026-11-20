package com.ok.delivery.domain;

import com.ok.delivery.DeliverySchema;
import com.ok.common.jpa.entity.BaseTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalTime;

/**
 * 판매자가 저장한 배송 설정. 판매자당 1개이고, 없는 판매자는 설정 파일의 기본값을 쓴다.
 *
 * <p>값 검증은 {@link SellerShippingSetting} 생성자가 맡는다.
 * 저장된 값이 잘못됐으면 {@link #toSetting()}에서 같은 검증이 다시 동작한다.
 */
@Entity
@Getter
@Table(name = "seller_shipping_profile", catalog = DeliverySchema.NAME)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SellerShippingProfile extends BaseTime {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "seller_id", nullable = false, unique = true)
    private Long sellerId;

    @Column(name = "base_shipping_fee", nullable = false)
    private int baseShippingFee;

    // null이면 무료배송 없음, 0이면 항상 무료
    @Column(name = "free_shipping_threshold")
    private Integer freeShippingThreshold;

    @Column(name = "cutoff_time", nullable = false)
    private LocalTime cutoffTime;

    private SellerShippingProfile(Long sellerId, SellerShippingSetting setting) {
        if (sellerId == null) {
            throw new IllegalArgumentException("판매자 ID는 필수입니다.");
        }
        if (setting == null) {
            throw new IllegalArgumentException("배송 설정은 필수입니다.");
        }
        this.sellerId = sellerId;
        this.baseShippingFee = setting.baseShippingFee();
        this.freeShippingThreshold = setting.freeShippingThreshold();
        this.cutoffTime = setting.cutoffTime();
    }

    public static SellerShippingProfile create(Long sellerId, SellerShippingSetting setting) {
        return new SellerShippingProfile(sellerId, setting);
    }

    public SellerShippingSetting toSetting() {
        return new SellerShippingSetting(baseShippingFee, freeShippingThreshold, cutoffTime);
    }
}
