package com.ok.delivery.domain;

import com.ok.delivery.DeliverySchema;
import com.ok.common.jpa.entity.BaseTime;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원의 기본 배송지. 회원당 1개이고 주문서의 초기 배송지로 쓴다.
 *
 * <p>주문과 shipment는 결제 시점의 배송지를 복사해 가지므로, 기본 배송지를 바꿔도 이미 만든 주문과 shipment는 바뀌지 않는다.
 */
@Entity
@Getter
@Table(name = "default_shipping_address", catalog = DeliverySchema.NAME)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DefaultShippingAddress extends BaseTime {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false, unique = true)
    private Long memberId;

    @Embedded
    private Address address;

    private DefaultShippingAddress(Long memberId, Address address) {
        if (memberId == null) {
            throw new IllegalArgumentException("memberId는 필수입니다.");
        }
        this.memberId = memberId;
        this.address = requireAddress(address);
    }

    public static DefaultShippingAddress create(Long memberId, Address address) {
        return new DefaultShippingAddress(memberId, address);
    }

    public void changeAddress(Address address) {
        this.address = requireAddress(address);
    }

    private static Address requireAddress(Address address) {
        if (address == null) {
            throw new IllegalArgumentException("배송지는 필수입니다.");
        }
        return address;
    }
}
