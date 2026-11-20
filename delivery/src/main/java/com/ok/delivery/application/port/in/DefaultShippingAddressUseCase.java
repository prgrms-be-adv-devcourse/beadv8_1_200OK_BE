package com.ok.delivery.application.port.in;

import com.ok.delivery.application.dto.AddressCommand;
import com.ok.delivery.application.dto.AddressResult;

import java.util.Optional;

/**
 * 회원의 기본 배송지를 조회하고 등록·수정하는 유스케이스. 마이페이지에서만 바꾸고, 주문서는 초기값으로 읽기만 한다.
 */
public interface DefaultShippingAddressUseCase {

    /**
     * 기본 배송지를 조회한다.
     *
     * @return 등록하지 않았으면 빈 값
     * @throws IllegalArgumentException 회원 ID가 없으면
     */
    Optional<AddressResult> getDefaultAddress(Long memberId);

    /**
     * 기본 배송지를 등록하거나 수정한다. 동시에 들어온 최초 등록과 겹쳐 유니크 제약에 걸리면 한 번 다시 시도한다.
     *
     * @throws IllegalArgumentException 회원 ID가 없으면
     * @throws com.ok.common.exception.RestApiException 배송지 검증 규칙은 {@link com.ok.delivery.domain.Address#of}를 따른다
     */
    AddressResult saveDefaultAddress(Long memberId, AddressCommand command);
}
