package com.ok.delivery.config;

import com.ok.delivery.domain.SellerShippingSetting;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalTime;

/**
 * 배송 설정을 등록하지 않은 판매자에게 적용할 기본 배송 설정이다.
 *
 * <p>application.yaml의 {@code delivery.default-shipping.*} 값이 바인딩된다.
 * <pre>
 * delivery:
 *   default-shipping:
 *     base-shipping-fee: 3000
 *     free-shipping-threshold: 50000
 *     cutoff-time: "13:00"
 * </pre>
 *
 * <p>세 값 모두 필수이고, 하나라도 없으면 기동 시점에 실패한다.
 * 판매자 설정에서는 무료배송 기준 {@code null}이 "무료배송 없음"이라서, 기본값에서 이 항목을 빠뜨리면
 * 모든 미설정 판매자가 조용히 무료배송 없음으로 계산된다. 오타나 누락을 그런 정책 변경으로 받아들이지 않으려고 막는다.
 */
@ConfigurationProperties(prefix = "delivery.default-shipping")
public record DefaultShippingProperties(Integer baseShippingFee, Integer freeShippingThreshold, LocalTime cutoffTime) {

    /**
     * 설정 바인딩 직후 값을 검증한다.
     *
     * @throws IllegalArgumentException 값이 없거나 금액이 음수면
     */
    public DefaultShippingProperties {
        if (baseShippingFee == null) {
            throw new IllegalArgumentException("delivery.default-shipping.base-shipping-fee는 필수입니다.");
        }
        if (freeShippingThreshold == null) {
            throw new IllegalArgumentException("delivery.default-shipping.free-shipping-threshold는 필수입니다.");
        }
        if (cutoffTime == null) {
            throw new IllegalArgumentException("delivery.default-shipping.cutoff-time은 필수입니다.");
        }
        // 금액 검증은 SellerShippingSetting 생성자가 한다. compact 생성자 안에서는 필드가 아직 대입되기 전이라 toSetting() 대신 매개변수로 만든다
        new SellerShippingSetting(baseShippingFee, freeShippingThreshold, cutoffTime);
    }

    public SellerShippingSetting toSetting() {
        return new SellerShippingSetting(baseShippingFee, freeShippingThreshold, cutoffTime);
    }
}
