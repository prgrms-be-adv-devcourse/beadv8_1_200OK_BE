package com.ok.delivery.service;

import com.ok.delivery.domain.Address;
import com.ok.delivery.domain.DefaultShippingAddress;
import com.ok.delivery.repository.DefaultShippingAddressRepository;
import com.ok.delivery.service.dto.AddressCommand;
import com.ok.delivery.service.dto.AddressResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 회원의 기본 배송지를 조회하고 등록·수정한다. 마이페이지에서만 바꾸고, 주문서는 초기값으로 읽기만 한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DefaultShippingAddressService {

    private final DefaultShippingAddressRepository defaultShippingAddressRepository;

    /**
     * 기본 배송지를 조회한다.
     *
     * @return 등록하지 않았으면 빈 값
     * @throws IllegalArgumentException 회원 ID가 없으면
     */
    public Optional<AddressResult> getDefaultAddress(Long memberId) {
        requireMemberId(memberId);
        return defaultShippingAddressRepository.findByMemberId(memberId)
                .map(defaultAddress -> AddressResult.from(defaultAddress.getAddress()));
    }

    /**
     * 기본 배송지를 등록하거나, 이미 있으면 새 배송지로 바꾼다.
     * 같은 회원의 최초 등록이 동시에 들어오면 늦은 쪽은 유니크 제약에 걸리므로, API는 {@link DefaultShippingAddressFacade}를 거쳐 호출한다.
     *
     * @throws IllegalArgumentException 회원 ID가 없으면
     * @throws com.ok.common.exception.RestApiException 배송지 검증 규칙은 {@link Address#of}를 따른다
     */
    @Transactional
    public AddressResult saveDefaultAddress(Long memberId, AddressCommand command) {
        requireMemberId(memberId);
        Address address = command.toAddress();
        DefaultShippingAddress defaultAddress = defaultShippingAddressRepository.findByMemberId(memberId)
                .map(existing -> {
                    existing.changeAddress(address);
                    return existing;
                })
                .orElseGet(() -> defaultShippingAddressRepository.save(DefaultShippingAddress.create(memberId, address)));
        return AddressResult.from(defaultAddress.getAddress());
    }

    private static void requireMemberId(Long memberId) {
        if (memberId == null) {
            throw new IllegalArgumentException("회원 ID는 필수입니다.");
        }
    }
}
