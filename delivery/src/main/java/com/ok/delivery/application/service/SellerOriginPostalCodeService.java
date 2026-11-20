package com.ok.delivery.application.service;

import com.ok.delivery.application.dto.SellerOriginPostalCodeCommand;
import com.ok.delivery.application.port.in.SellerOriginPostalCodeUseCase;
import com.ok.delivery.application.port.out.SellerOriginPostalCodeRepository;
import com.ok.delivery.domain.SellerOriginPostalCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SellerOriginPostalCodeService implements SellerOriginPostalCodeUseCase {

    private final SellerOriginPostalCodeRepository sellerOriginPostalCodeRepository;

    @Override
    @Transactional
    public void apply(SellerOriginPostalCodeCommand command) {
        Optional<SellerOriginPostalCode> stored = sellerOriginPostalCodeRepository.findBySellerId(command.sellerId());
        if (stored.isEmpty()) {
            sellerOriginPostalCodeRepository.save(
                    SellerOriginPostalCode.create(command.sellerId(), command.postalCode(), command.occurredAt()));
            return;
        }
        // 엔티티를 바꾸면 변경 감지로 저장된다
        if (!stored.get().update(command.postalCode(), command.occurredAt())) {
            log.info("저장된 값보다 오래된 판매자 출고지 우편번호 메시지, 무시. sellerId={}, occurredAt={}",
                    command.sellerId(), command.occurredAt());
        }
    }
}
