package com.ok.delivery.application.service;

import com.ok.common.exception.RestApiException;
import com.ok.delivery.application.dto.PostalRegionRegisterCommand;
import com.ok.delivery.application.dto.PostalRegionResult;
import com.ok.delivery.application.dto.PostalRegionUpdateCommand;
import com.ok.delivery.application.dto.RegionResult;
import com.ok.delivery.application.port.out.RegionGroupRepository;
import com.ok.delivery.application.port.out.RemoteAreaPostalCodeHistoryRepository;
import com.ok.delivery.application.port.out.RemoteAreaPostalCodeRepository;
import com.ok.delivery.domain.PostalCodeHistoryAction;
import com.ok.delivery.domain.RegionGroup;
import com.ok.delivery.domain.RemoteAreaPostalCode;
import com.ok.delivery.domain.RemoteAreaPostalCodeHistory;
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

/**
 * 우편번호로 지역 그룹, 추가 운임, 추가 소요일을 판정하고, 관리자가 도서산간 우편번호를 등록·수정·삭제·조회하게 한다.
 * 견적, 배송 생성, 배송지 변경이 같은 판정을 쓰도록 한 곳에 둔다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DeliveryRegionService {

    private final RemoteAreaPostalCodeRepository postalCodeRepository;
    private final RegionGroupRepository regionGroupRepository;
    private final RemoteAreaPostalCodeHistoryRepository historyRepository;
    private final Clock clock;

    /**
     * 등록되지 않았거나 삭제된 우편번호는 일반 지역({@code GENERAL}, 0원, 0일)으로 판정한다.
     * 입력은 {@code Address}나 요청 DTO에서 이미 검증된 우편번호다. 형식은 다시 검사하지 않으며, 형식이 틀린 값은 등록되지 않은 번호로 본다.
     * {@code null}은 호출한 코드의 버그이고, 일반 지역(추가 운임 0원)으로 숨기면 결제 금액이 틀려지므로 예외로 드러낸다.
     *
     * @throws IllegalArgumentException 우편번호가 {@code null}이면
     */
    public RegionResult resolve(String postalCode) {
        if (postalCode == null) {
            throw new IllegalArgumentException("우편번호는 필수입니다.");
        }
        return postalCodeRepository.findByPostalCodeAndDeletedAtIsNull(postalCode)
                .map(this::toResult)
                .orElseGet(RegionResult::general);
    }

    /**
     * 도서산간 우편번호를 등록한다. 삭제된 번호면 새 행을 만들지 않고 그 행을 요청값으로 되살린다.
     * 등록은 {@code REGISTERED}, 되살림은 {@code RESTORED} 이력을 같은 트랜잭션에 남긴다.
     *
     * @param adminId 등록하는 관리자의 회원 ID
     * @throws RestApiException 이미 등록된 번호면 {@code POSTAL_REGION_DUPLICATED},
     *                          없는 그룹이거나 {@code GENERAL}이면 {@code REGION_GROUP_NOT_FOUND},
     *                          값 검증은 {@link RemoteAreaPostalCode#of}를 따른다
     */
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

    /**
     * 도서산간 우편번호의 그룹과 추가 소요일을 바꾸고 {@code UPDATED} 이력을 같은 트랜잭션에 남긴다.
     * 삭제 후 재등록과 달리 일반 지역으로 판정되는 순간이 없다. 같은 값이면 바꾸지 않고 이력도 남기지 않는다.
     *
     * @param adminId 수정하는 관리자의 회원 ID
     * @throws RestApiException 없거나 삭제된 번호면 {@code POSTAL_REGION_NOT_FOUND},
     *                          없는 그룹이거나 {@code GENERAL}이면 {@code REGION_GROUP_NOT_FOUND},
     *                          값 검증은 {@link RemoteAreaPostalCode#update}를 따른다
     */
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

    /**
     * 도서산간 우편번호를 삭제(soft delete)하고 {@code DELETED} 이력을 같은 트랜잭션에 남긴다. 삭제한 번호는 일반 지역으로 판정된다.
     *
     * @param adminId 삭제하는 관리자의 회원 ID
     * @throws RestApiException 없거나 이미 삭제된 번호면 {@code POSTAL_REGION_NOT_FOUND}
     */
    @Transactional
    public void delete(String postalCode, Long adminId) {
        RemoteAreaPostalCode target = postalCodeRepository.findByIdForUpdate(postalCode)
                .orElseThrow(() -> new RestApiException(DeliveryErrorCode.POSTAL_REGION_NOT_FOUND));
        target.delete(LocalDateTime.now(clock));
        historyRepository.save(RemoteAreaPostalCodeHistory.of(PostalCodeHistoryAction.DELETED, target, adminId));
    }

    /**
     * 삭제되지 않은 도서산간 우편번호를 우편번호 순으로 조회한다.
     *
     * @param page 0부터 시작하는 페이지 번호
     */
    public Page<PostalRegionResult> getPostalRegions(int page, int size) {
        return postalCodeRepository.findByDeletedAtIsNull(PageRequest.of(page, size, Sort.by("postalCode")))
                .map(PostalRegionResult::from);
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
