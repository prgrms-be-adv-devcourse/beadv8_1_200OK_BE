package com.ok.delivery.application.port.in;

import com.ok.delivery.application.dto.SellerOriginPostalCodeCommand;

/**
 * member가 보낸 판매자 출고지 우편번호를 배송의 복제 테이블에 반영하는 유스케이스.
 */
public interface SellerOriginPostalCodeUseCase {

    /**
     * 판매자의 출고지 우편번호를 덮어쓴다. 처음 받는 판매자면 새로 저장한다.
     * 같은 메시지가 다시 오거나 저장된 값보다 오래된 메시지가 오면 값을 바꾸지 않는다.
     */
    void apply(SellerOriginPostalCodeCommand command);
}
