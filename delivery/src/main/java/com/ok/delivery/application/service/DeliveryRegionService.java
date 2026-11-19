package com.ok.delivery.application.service;

import com.ok.common.exception.RestApiException;
import com.ok.delivery.application.dto.PostalRegionRegisterCommand;
import com.ok.delivery.application.dto.PostalRegionResult;
import com.ok.delivery.application.dto.PostalRegionUpdateCommand;
import com.ok.delivery.application.dto.RegionResult;
import com.ok.delivery.application.port.in.DeliveryRegionUseCase;
import com.ok.delivery.application.port.out.RegionGroupRepository;
import com.ok.delivery.application.port.out.RemoteAreaPostalCodeHistoryRepository;
import com.ok.delivery.application.port.out.RemoteAreaPostalCodeRepository;
import com.ok.delivery.application.port.out.SellerOriginPostalCodeRepository;
import com.ok.delivery.domain.PostalCodeHistoryAction;
import com.ok.delivery.domain.RegionGroup;
import com.ok.delivery.domain.RemoteAreaPostalCode;
import com.ok.delivery.domain.RemoteAreaPostalCodeHistory;
import com.ok.delivery.domain.SellerOriginPostalCode;
import com.ok.delivery.exception.DeliveryErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 우편번호로 지역 그룹, 추가 운임, 추가 소요일을 판정하고, 관리자가 도서산간 우편번호를 등록·수정·삭제·조회하게 한다.
 * 견적, 배송 생성, 배송지 변경이 같은 판정을 쓰도록 한 곳에 둔다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DeliveryRegionService implements DeliveryRegionUseCase {

    private final RemoteAreaPostalCodeRepository postalCodeRepository;
    private final RegionGroupRepository regionGroupRepository;
    private final RemoteAreaPostalCodeHistoryRepository historyRepository;
    private final SellerOriginPostalCodeRepository sellerOriginPostalCodeRepository;
    private final Clock clock;

    @Override
    public RegionResult resolve(String postalCode) {
        if (postalCode == null) {
            throw new IllegalArgumentException("우편번호는 필수입니다.");
        }
        return postalCodeRepository.findActive(postalCode)
                .map(this::toResult)
                .orElseGet(RegionResult::general);
    }

    /**
     * 판매자별 도서산간 추가 운임을 판정한다.
     * 배송지 지역의 추가 운임을 그대로 쓰되, 출고지와 배송지가 모두 제주 본섬이면 바다를 건너지 않으므로 0원이다.
     * 출고지를 모르거나 출고지가 제주 본섬이 아닌 판매자는 면제하지 않는다. 부속 도서는 본섬과 그룹이 달라 면제 대상이 아니다.
     * 견적, 주문용 계산, 배송 생성, 배송지 변경이 모두 이 판정을 써야 금액이 어긋나지 않는다.
     *
     * @param destination 배송지 우편번호로 판정한 지역
     * @return 요청한 모든 판매자 ID를 키로 한 추가 운임(원)
     * @throws IllegalArgumentException 배송지 지역이나 판매자 ID 목록이 없거나 null인 ID가 있으면
     */
    public Map<Long, Integer> resolveSurcharges(RegionResult destination, Collection<Long> sellerIds) {
        if (destination == null) {
            throw new IllegalArgumentException("배송지 지역은 필수입니다.");
        }
        if (sellerIds == null || sellerIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("판매자 ID 목록은 필수이고 null인 ID를 포함할 수 없습니다.");
        }
        Set<Long> exemptSellerIds = jejuMainOriginSellerIds(destination, sellerIds);
        Map<Long, Integer> surcharges = new LinkedHashMap<>();
        for (Long sellerId : sellerIds) {
            surcharges.put(sellerId, exemptSellerIds.contains(sellerId) ? 0 : destination.surcharge());
        }
        return surcharges;
    }

    /**
     * 판매자 한 명의 도서산간 추가 운임을 판정한다. 규칙은 {@link #resolveSurcharges}와 같다.
     *
     * @throws IllegalArgumentException 배송지 지역이나 판매자 ID가 없으면
     */
    public int resolveSurcharge(RegionResult destination, Long sellerId) {
        if (sellerId == null) {
            throw new IllegalArgumentException("판매자 ID는 필수입니다.");
        }
        return resolveSurcharges(destination, List.of(sellerId)).get(sellerId);
    }

    @Override
    @Transactional
    public PostalRegionResult register(PostalRegionRegisterCommand command, Long adminId) {
        RemoteAreaPostalCode postalCode = postalCodeRepository.findByIdForUpdate(command.postalCode()).orElse(null);
        PostalCodeHistoryAction action;
        if (postalCode == null) {
            postalCode = RemoteAreaPostalCode.of(command.postalCode(), command.regionGroup(), command.extraDeliveryDays());
            requireRegionGroup(postalCode.getRegionGroup());
            insert(postalCode);
            action = PostalCodeHistoryAction.REGISTERED;
        } else {
            postalCode.reregister(command.regionGroup(), command.extraDeliveryDays());
            // 엔티티 검증을 통과한 뒤에 확인한다. 실패하면 트랜잭션이 롤백되어 위의 변경은 반영되지 않는다
            requireRegionGroup(postalCode.getRegionGroup());
            action = PostalCodeHistoryAction.RESTORED;
        }
        historyRepository.save(RemoteAreaPostalCodeHistory.of(action, postalCode, adminId));
        return PostalRegionResult.from(postalCode);
    }

    @Override
    @Transactional
    public PostalRegionResult update(PostalRegionUpdateCommand command, Long adminId) {
        RemoteAreaPostalCode postalCode = postalCodeRepository.findByIdForUpdate(command.postalCode())
                .orElseThrow(() -> new RestApiException(DeliveryErrorCode.POSTAL_REGION_NOT_FOUND));
        if (postalCode.update(command.regionGroup(), command.extraDeliveryDays())) {
            // 엔티티 검증을 통과한 뒤에 확인한다. 실패하면 트랜잭션이 롤백되어 위의 변경은 반영되지 않는다
            requireRegionGroup(postalCode.getRegionGroup());
            historyRepository.save(RemoteAreaPostalCodeHistory.of(PostalCodeHistoryAction.UPDATED, postalCode, adminId));
        }
        return PostalRegionResult.from(postalCode);
    }

    @Override
    @Transactional
    public void delete(String postalCode, Long adminId) {
        RemoteAreaPostalCode target = postalCodeRepository.findByIdForUpdate(postalCode)
                .orElseThrow(() -> new RestApiException(DeliveryErrorCode.POSTAL_REGION_NOT_FOUND));
        target.delete(LocalDateTime.now(clock));
        historyRepository.save(RemoteAreaPostalCodeHistory.of(PostalCodeHistoryAction.DELETED, target, adminId));
    }

    @Override
    public Page<PostalRegionResult> getPostalRegions(int page, int size) {
        return postalCodeRepository.findByDeletedAtIsNull(PageRequest.of(page, size, Sort.by("postalCode")))
                .map(PostalRegionResult::from);
    }

    // 배송지가 제주 본섬이 아니면 면제가 없으므로 출고지를 조회하지 않는다. 견적은 호출이 잦아 대부분의 요청에서 쿼리를 늘리지 않으려는 것이다
    private Set<Long> jejuMainOriginSellerIds(RegionResult destination, Collection<Long> sellerIds) {
        if (!RegionGroup.JEJU_MAIN_CODE.equals(destination.regionGroup()) || sellerIds.isEmpty()) {
            return Set.of();
        }
        // 출고지 우편번호가 null이면 출고지가 없는 판매자다
        Map<Long, String> originPostalCodes = sellerOriginPostalCodeRepository.findAllBySellerIdIn(sellerIds).stream()
                .filter(origin -> origin.getPostalCode() != null)
                .collect(Collectors.toMap(SellerOriginPostalCode::getSellerId, SellerOriginPostalCode::getPostalCode));
        if (originPostalCodes.isEmpty()) {
            return Set.of();
        }
        Set<String> jejuMainPostalCodes = postalCodeRepository
                .findActiveIn(new HashSet<>(originPostalCodes.values()), RegionGroup.JEJU_MAIN_CODE).stream()
                .map(RemoteAreaPostalCode::getPostalCode)
                .collect(Collectors.toSet());
        return originPostalCodes.entrySet().stream()
                .filter(origin -> jejuMainPostalCodes.contains(origin.getValue()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    private RegionResult toResult(RemoteAreaPostalCode postalCode) {
        RegionGroup group = regionGroupRepository.findById(postalCode.getRegionGroup())
                .orElseThrow(() -> new IllegalStateException(
                        "지역 그룹이 없습니다. postalCode=" + postalCode.getPostalCode() + ", regionGroup=" + postalCode.getRegionGroup()));
        return new RegionResult(group.getCode(), group.getSurcharge(), postalCode.getExtraDeliveryDays());
    }

    // GENERAL은 region_group에 행이 없어서 같이 걸러진다
    private void requireRegionGroup(String regionGroup) {
        if (!regionGroupRepository.existsById(regionGroup)) {
            throw new RestApiException(DeliveryErrorCode.REGION_GROUP_NOT_FOUND);
        }
    }

    // 잠금 조회에서 행이 없던 같은 번호를 동시에 등록하면 한쪽이 PK 중복이나 교착 상태로 실패한다.
    // 교착 상태는 MySQL이 없는 행을 FOR UPDATE로 읽을 때 잡는 gap 잠금끼리 INSERT에서 서로를 기다려 생긴다.
    // 그룹은 먼저 확인했으므로 이때의 무결성 위반은 PK 중복이다.
    private void insert(RemoteAreaPostalCode postalCode) {
        try {
            postalCodeRepository.saveAndFlush(postalCode);
        } catch (DataIntegrityViolationException | PessimisticLockingFailureException e) {
            throw new RestApiException(DeliveryErrorCode.POSTAL_REGION_DUPLICATED);
        }
    }
}
