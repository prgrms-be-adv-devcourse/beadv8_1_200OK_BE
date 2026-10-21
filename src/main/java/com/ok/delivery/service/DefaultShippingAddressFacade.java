package com.ok.delivery.service;

import com.ok.delivery.service.dto.AddressCommand;
import com.ok.delivery.service.dto.AddressResult;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * 기본 배송지 등록·수정을 트랜잭션 밖에서 감싸, 같은 회원의 최초 등록이 동시에 들어와도 모두 성공하게 한다.
 * 제약 위반은 트랜잭션을 rollback-only로 만들어 서비스 안에서는 이어서 처리할 수 없으므로, 새 트랜잭션으로 다시 호출한다.
 */
@Component
@RequiredArgsConstructor
public class DefaultShippingAddressFacade {

    private final DefaultShippingAddressService defaultShippingAddressService;

    /**
     * 기본 배송지를 등록하거나 수정한다. 동시에 들어온 최초 등록과 겹쳐 유니크 제약에 걸리면 한 번 다시 시도한다.
     *
     * @throws IllegalArgumentException 회원 ID가 없으면
     * @throws com.ok.common.exception.RestApiException 배송지 검증 규칙은 {@link com.ok.delivery.domain.Address#of}를 따른다
     */
    public AddressResult saveDefaultAddress(Long memberId, AddressCommand command) {
        try {
            return defaultShippingAddressService.saveDefaultAddress(memberId, command);
        } catch (DataIntegrityViolationException e) {
            // 다른 요청이 먼저 행을 만들었으므로 다시 시도하면 수정 경로로 처리된다
            return defaultShippingAddressService.saveDefaultAddress(memberId, command);
        }
    }
}
