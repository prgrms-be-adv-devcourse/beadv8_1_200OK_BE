package com.ok.delivery.application.service;

import com.ok.delivery.application.dto.AddressCommand;
import com.ok.delivery.application.dto.AddressResult;
import com.ok.delivery.application.port.in.DefaultShippingAddressUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 기본 배송지 등록·수정을 트랜잭션 밖에서 감싸, 같은 회원의 최초 등록이 동시에 들어와도 모두 성공하게 한다.
 * 제약 위반은 트랜잭션을 rollback-only로 만들어 서비스 안에서는 이어서 처리할 수 없으므로, 새 트랜잭션으로 다시 호출한다.
 * 조회는 서비스에 그대로 맡긴다. 입력 포트를 하나로 두려고 이 Facade가 함께 구현한다.
 */
@Component
@RequiredArgsConstructor
public class DefaultShippingAddressFacade implements DefaultShippingAddressUseCase {

    private final DefaultShippingAddressService defaultShippingAddressService;

    @Override
    public Optional<AddressResult> getDefaultAddress(Long memberId) {
        return defaultShippingAddressService.getDefaultAddress(memberId);
    }

    @Override
    public AddressResult saveDefaultAddress(Long memberId, AddressCommand command) {
        try {
            return defaultShippingAddressService.saveDefaultAddress(memberId, command);
        } catch (DataIntegrityViolationException e) {
            // 다른 요청이 먼저 행을 만들었으므로 다시 시도하면 수정 경로로 처리된다
            return defaultShippingAddressService.saveDefaultAddress(memberId, command);
        }
    }
}
