package com.ok.refund.adapter.out.persistence;

import com.ok.common.exception.RestApiException;
import com.ok.refund.application.port.out.RefundRepository;
import com.ok.refund.domain.CancelledQuantity;
import com.ok.refund.domain.Refund;
import com.ok.refund.domain.RefundStatus;
import com.ok.refund.exception.RefundErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 출력 포트 {@link RefundRepository}를 JPA로 구현한다. 도메인과 {@link RefundJpaEntity}를 {@link RefundMapper}로 바꿔 주고받는다.
 * 트랜잭션은 따로 열지 않는다. 서비스가 연 트랜잭션 안에서 불려야 잠금과 {@link #update}의 확인이 그 트랜잭션 기준으로 맞는다.
 * DB에 묶인 지식(UNIQUE 제약 이름, SQLState)은 여기서 에러 코드로 바꿔, 서비스가 Spring DAO 예외를 알지 않게 한다.
 *
 * <p>{@code @Repository}가 아니라 {@code @Component}로 둔다. {@code @Repository}를 붙이면 스프링의 예외 변환이
 * {@link #update}가 던지는 {@link IllegalStateException}을 {@code InvalidDataAccessApiUsageException}으로 바꿔 포트의 약속과 달라진다.
 * DB 예외 변환은 안쪽의 {@link RefundJpaRepository} 프록시가 이미 해 주므로 여기서 다시 할 필요가 없다.
 */
@Component
@RequiredArgsConstructor
public class RefundPersistenceAdapter implements RefundRepository {

    // 마이그레이션의 제약 이름과 같아야 한다. 이 이름으로 "진행 중 요청 중복"과 다른 무결성 오류를 구분한다
    private static final String ACTIVE_ORDER_ITEM_UNIQUE_CONSTRAINT = "uk_refund_active_order_item_id";
    // 표준 SQLState의 "직렬화 실패". MySQL은 교착 상태(1213)를 이 값으로 알린다
    private static final String DEADLOCK_SQL_STATE = "40001";

    private final RefundJpaRepository refundJpaRepository;
    private final EntityManager entityManager;

    // saveAndFlush로 INSERT를 바로 보내야 UNIQUE 위반이 이 메서드 안에서 나서 에러 코드로 바꿀 수 있다.
    // save만 쓰면 커밋 때 터져 500이 된다.
    // 저장한 엔티티로 새 도메인을 만들어 돌려준다. ID(IDENTITY)와 생성 시각(감사 리스너)은 저장해야 생기기 때문이다
    @Override
    public Refund saveNew(Refund refund) {
        RefundJpaEntity entity = RefundMapper.toNewEntity(refund);
        try {
            RefundJpaEntity saved = refundJpaRepository.saveAndFlush(entity);
            return RefundMapper.toDomain(saved);
        } catch (DataIntegrityViolationException e) {
            if (String.valueOf(e.getMostSpecificCause().getMessage()).contains(ACTIVE_ORDER_ITEM_UNIQUE_CONSTRAINT)) {
                throw new RestApiException(RefundErrorCode.REFUND_ALREADY_IN_PROGRESS);
            }
            throw e;
        } catch (PessimisticLockingFailureException e) {
            // 철회·거절이 진행 중 칸을 비우고 커밋하는 순간 같은 상품의 새 요청이 둘 이상 오면, 두 INSERT가 비워지는 UNIQUE 항목의
            // 잠금을 함께 얻은 뒤 서로를 기다려 MySQL이 한쪽을 교착 상태로 되돌린다(직접 재현함). 되돌려진 쪽은 다른 요청이
            // 먼저 들어간 경우라 중복과 같게 409로 돌려준다.
            // 같은 예외에 잠금 대기 시간 초과도 들어오는데, 그건 DB가 느린 것이지 중복이 아니라 그대로 던져 500으로 드러낸다
            if (isDeadlock(e)) {
                throw new RestApiException(RefundErrorCode.REFUND_ALREADY_IN_PROGRESS);
            }
            throw e;
        }
    }

    // 이 트랜잭션에서 이미 불러온(잠금 조회한) 엔티티에만 바뀌는 칸을 옮긴다. 실제 UPDATE는 커밋 때 변경 감지로 나간다.
    // 엔티티가 도메인을 바꾸는 순간이 아니라 여기서만 바뀌므로, 그 사이의 조회(예: 승인 때 집계)가 자동 flush로
    // 바뀐 상태를 읽는 일이 구조적으로 생기지 않는다.
    //
    // "이미 불러왔는지"는 getReference + isLoaded로 확인한다. 둘 다 JPA 표준이라 Hibernate 내부 API에 기대지 않는다.
    // - getReference는 영속성 컨텍스트에 있으면 그 객체를 그대로 돌려주고, 없으면 쿼리 없이 초기화 안 된 프록시를 돌려준다.
    // - isLoaded는 앞의 경우 true, 뒤의 경우(프록시) false다.
    // find를 쓰지 않는 이유: 컨텍스트에 없으면 잠금 없이 새로 조회해 버려, 잠그지 않은 행을 덮어쓰는 것을 막지 못한다.
    // 없을 때 남는 빈 프록시는 이어서 던지는 예외로 트랜잭션이 되돌려지므로 따로 치우지 않는다.
    //
    // 불러왔더라도 잠금 조회(findByIdForUpdate)로 불러온 것이어야 한다(설계 Ruling 2). 같은 트랜잭션에서 잠그지 않는 findById로 읽은 행에
    // 쓰면, 다른 트랜잭션이 그 행을 잠그고 바꾸는 중에도 기다리지 않고 덮어쓸 수 있다. 잠금 모드는 JPA 표준 getLockMode로 본다
    // (Hibernate 7에서 findById로 불러오면 NONE, findByIdForUpdate면 PESSIMISTIC_WRITE임을 확인함).
    // PESSIMISTIC_FORCE_INCREMENT도 허용하는 이유: 이 트랜잭션에서 그 행의 INSERT나 UPDATE가 flush되면(집계 조회의 자동 flush,
    // saveNew의 saveAndFlush 등) Hibernate가 잠금 모드를 내부 WRITE로 바꾸고, getLockMode는 이를 PESSIMISTIC_FORCE_INCREMENT로 알린다.
    // 이 행에 쓰기를 보낸 트랜잭션은 커밋까지 그 행의 쓰기 잠금을 가지므로 PESSIMISTIC_WRITE와 같은 뜻이다.
    // 이것을 막으면 "잠금 → update → 집계 조회 → 다시 update"나 "saveNew → findByIdForUpdate → update"가 정상인데도 실패한다.
    // isLoaded 확인 뒤에 보는 이유: 초기화 안 된 프록시에 getLockMode를 부르면 프록시가 초기화되며 잠금 없는 조회가 나갈 수 있다.
    // 두 경우 모두 "이 트랜잭션에서 잠금 조회로 불러오지 않았다"는 같은 버그라 같은 메시지를 쓴다
    @Override
    public void update(Refund refund) {
        if (refund.getId() == null) {
            throw new IllegalStateException("저장하지 않은 환불은 update할 수 없습니다. saveNew로 저장해야 합니다.");
        }
        RefundJpaEntity entity = entityManager.getReference(RefundJpaEntity.class, refund.getId());
        if (!entityManager.getEntityManagerFactory().getPersistenceUnitUtil().isLoaded(entity)) {
            throw notLockedInThisTransaction(refund.getId());
        }
        if (!holdsWriteLock(entityManager.getLockMode(entity))) {
            throw notLockedInThisTransaction(refund.getId());
        }
        RefundMapper.applyMutableState(refund, entity);
    }

    private static boolean holdsWriteLock(LockModeType lockMode) {
        return lockMode == LockModeType.PESSIMISTIC_WRITE || lockMode == LockModeType.PESSIMISTIC_FORCE_INCREMENT;
    }

    private static IllegalStateException notLockedInThisTransaction(Long refundId) {
        return new IllegalStateException("이 트랜잭션에서 잠금 조회로 불러온 환불만 update할 수 있습니다. refundId=" + refundId);
    }

    @Override
    public Optional<Refund> findById(Long id) {
        return refundJpaRepository.findById(id).map(RefundMapper::toDomain);
    }

    // 트랜잭션에서 그 행의 첫 조회여야 한다. 앞서 읽어 둔 엔티티가 컨텍스트에 있으면 잠금은 걸려도 잠금 전 값이 그대로 쓰인다
    @Override
    public Optional<Refund> findByIdForUpdate(Long id) {
        return refundJpaRepository.findByIdForUpdate(id).map(RefundMapper::toDomain);
    }

    @Override
    public List<Long> findLockTargetIds(Long orderId, Long sellerId, Long refundId, Collection<RefundStatus> statuses) {
        return refundJpaRepository.findLockTargetIds(orderId, sellerId, refundId, statuses);
    }

    @Override
    public Page<Refund> findAllByBuyerId(Long buyerId, Pageable pageable) {
        return refundJpaRepository.findAllByBuyerId(buyerId, pageable).map(RefundMapper::toDomain);
    }

    @Override
    public Page<Refund> findAllBySellerId(Long sellerId, Pageable pageable) {
        return refundJpaRepository.findAllBySellerId(sellerId, pageable).map(RefundMapper::toDomain);
    }

    @Override
    public Page<Refund> findAllBySellerIdAndStatus(Long sellerId, RefundStatus status, Pageable pageable) {
        return refundJpaRepository.findAllBySellerIdAndStatus(sellerId, status, pageable).map(RefundMapper::toDomain);
    }

    @Override
    public List<CancelledQuantity> sumCancelledByOrderItem(Long orderId, Long sellerId, Collection<RefundStatus> statuses) {
        return refundJpaRepository.sumCancelledByOrderItem(orderId, sellerId, statuses);
    }

    // 교착 상태는 SQLState 40001(MySQL 1213), 잠금 대기 시간 초과는 HY000(MySQL 1205)이라 SQLState로 가른다.
    // 스프링은 둘 다 CannotAcquireLockException으로 바꾸므로 예외 종류로는 가를 수 없다(재현에서 확인)
    private static boolean isDeadlock(PessimisticLockingFailureException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException && DEADLOCK_SQL_STATE.equals(sqlException.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
