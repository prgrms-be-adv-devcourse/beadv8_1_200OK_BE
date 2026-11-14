package com.ok.refund.adapter.out.persistence;

import com.ok.common.exception.RestApiException;
import com.ok.refund.application.port.out.RefundPersistencePort;
import com.ok.refund.application.port.out.RefundStepPersistencePort;
import com.ok.refund.domain.CancelledQuantity;
import com.ok.refund.domain.Refund;
import com.ok.refund.domain.RefundStatus;
import com.ok.refund.domain.RefundStep;
import com.ok.refund.domain.RefundStepType;
import com.ok.refund.exception.RefundErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 출력 포트 {@link RefundPersistencePort}와 {@link RefundStepPersistencePort}를 JPA로 구현한다. 도메인과 {@link RefundJpaEntity}를 {@link RefundMapper}로 바꿔 주고받는다.
 * 트랜잭션은 따로 열지 않는다. 서비스가 연 트랜잭션 안에서 불려야 잠금과 {@link #update}의 확인이 그 트랜잭션 기준으로 맞는다.
 * DB에 묶인 지식(UNIQUE 제약 이름, SQLState)은 여기서 에러 코드로 바꿔, 서비스가 Spring DAO 예외를 알지 않게 한다.
 *
 * <p>{@code @Repository}가 아니라 {@code @Component}로 둔다. {@code @Repository}를 붙이면 스프링의 예외 변환이
 * {@link #update}가 던지는 {@link IllegalStateException}을 {@code InvalidDataAccessApiUsageException}으로 바꿔 포트의 약속과 달라진다.
 * DB 예외 변환은 안쪽의 {@link RefundJpaRepository} 프록시가 이미 해 주므로 여기서 다시 할 필요가 없다.
 *
 * <p>단계 행 포트도 이 클래스가 함께 구현한다(#574 결정 6). 단계 행을 저장할 때 "그 환불 행을 이 트랜잭션에서 잠갔는가"를
 * 환불 잠금 조회가 적어 두는 트랜잭션별 목록({@code LOCKED_IDS_KEY})으로 확인해야 하는데, 그 목록이 이 클래스 안에 있기 때문이다.
 * 어댑터를 나누면 목록을 둘이 함께 보도록 꺼내야 해서 잠금 확인이 두 곳으로 흩어진다.
 */
@Component
@RequiredArgsConstructor
public class RefundPersistenceAdapter implements RefundPersistencePort, RefundStepPersistencePort {

    // 마이그레이션의 제약 이름과 같아야 한다. 이 이름으로 "진행 중 요청 중복"과 다른 무결성 오류를 구분한다
    private static final String ACTIVE_ORDER_ITEM_UNIQUE_CONSTRAINT = "uk_refund_active_order_item_id";
    // 표준 SQLState의 "직렬화 실패". MySQL은 교착 상태(1213)를 이 값으로 알린다
    private static final String DEADLOCK_SQL_STATE = "40001";
    // 목록 한 쪽의 크기. 클라이언트가 바꿀 수 없는 고정 크기다
    private static final int PAGE_SIZE = 25;
    // 최신순. 같은 시각이면 나중에 만든 요청이 먼저 오도록 ID를 함께 쓴다(페이지 사이에 순서가 흔들리지 않게)
    private static final Sort LATEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    // 트랜잭션마다 이미 잠금 조회한 환불 ID를 담아 두는 자리의 열쇠(findByIdForUpdate 설명 참고)
    private static final Object LOCKED_IDS_KEY = new Object();

    private final RefundJpaRepository refundJpaRepository;
    private final RefundStepJpaRepository refundStepJpaRepository;
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
    // 불러왔더라도 잠금 조회(findByIdForUpdate)로 불러온 것이어야 한다. 같은 트랜잭션에서 잠그지 않는 findById로 읽은 행에
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
    // 같은 트랜잭션에서 같은 환불을 두 번 잠금 조회하면 막는다(#486 3번). 두 번째 조회는 같은 엔티티에서 새 도메인 객체를 만들어 주는데,
    // 앞의 객체를 계속 쥐고 있다가 나중에 update하면 뒤 객체로 바꾼 내용을 옛 값으로 덮어쓴다. 한 트랜잭션에서 한 환불은 한 번만 잠그고
    // 그 객체 하나로 바꾸고 저장하게 한다. update를 여러 번 부르는 것("잠금 → update → 집계 → 다시 update")과
    // 새로 저장한 뒤 잠금 조회하는 것("saveNew → findByIdForUpdate")은 첫 잠금 조회라 막지 않는다
    @Override
    public Optional<Refund> findByIdForUpdate(Long id) {
        rememberLockedOnce(id);
        return refundJpaRepository.findByIdForUpdate(id).map(RefundMapper::toDomain);
    }

    // 트랜잭션에 묶인 자리(TransactionSynchronizationManager)에 잠근 ID를 적고, 트랜잭션이 끝나면 지운다.
    // 트랜잭션 밖이면 적을 자리가 없어 검사하지 않는다(그때는 FOR UPDATE 자체가 실패한다).
    // 안에서 새 트랜잭션(REQUIRES_NEW)이 열리면 바깥 목록을 떼어 두고 돌아올 때 다시 붙여, 트랜잭션마다 따로 센다.
    // 떼어 두지 않으면 안쪽이 잠근 ID가 바깥 목록에 남아, 바깥에서 처음 잠그는 환불도 두 번째로 보고 막는다
    @SuppressWarnings("unchecked")
    private static void rememberLockedOnce(Long id) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        Set<Long> locked = (Set<Long>) TransactionSynchronizationManager.getResource(LOCKED_IDS_KEY);
        if (locked == null) {
            Set<Long> created = new HashSet<>();
            TransactionSynchronizationManager.bindResource(LOCKED_IDS_KEY, created);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                // 안에서 새 트랜잭션이 열리면 이 트랜잭션의 목록을 떼어 둔다. 안쪽은 빈 자리에서 자기 목록을 새로 만든다
                @Override
                public void suspend() {
                    TransactionSynchronizationManager.unbindResourceIfPossible(LOCKED_IDS_KEY);
                }

                // 안쪽이 끝나 이 트랜잭션으로 돌아오면 떼어 둔 목록을 다시 붙인다
                @Override
                public void resume() {
                    TransactionSynchronizationManager.bindResource(LOCKED_IDS_KEY, created);
                }

                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(LOCKED_IDS_KEY);
                }
            });
            locked = created;
        }
        if (!locked.add(id)) {
            throw new IllegalStateException("같은 트랜잭션에서 같은 환불을 두 번 잠금 조회할 수 없습니다. 처음 잠근 객체로 바꾸고 저장해야 합니다. refundId=" + id);
        }
    }

    @Override
    public List<Long> findLockTargetIds(Long orderId, Long sellerId, Long refundId, Collection<RefundStatus> statuses) {
        return refundJpaRepository.findLockTargetIds(orderId, sellerId, refundId, statuses);
    }

    @Override
    public Page<Refund> findAllByBuyerId(Long buyerId, int page) {
        return refundJpaRepository.findAllByBuyerId(buyerId, latestFirst(page)).map(RefundMapper::toDomain);
    }

    @Override
    public Page<Refund> findAllBySellerId(Long sellerId, int page) {
        return refundJpaRepository.findAllBySellerId(sellerId, latestFirst(page)).map(RefundMapper::toDomain);
    }

    @Override
    public Page<Refund> findAllBySellerIdAndStatus(Long sellerId, RefundStatus status, int page) {
        return refundJpaRepository.findAllBySellerIdAndStatus(sellerId, status, latestFirst(page)).map(RefundMapper::toDomain);
    }

    // 구매자·판매자 목록이 같은 크기와 순서를 쓰도록 한곳에서 만든다
    private static Pageable latestFirst(int page) {
        return PageRequest.of(page, PAGE_SIZE, LATEST_FIRST);
    }

    @Override
    public List<CancelledQuantity> sumCancelledByOrderItem(Long orderId, Long sellerId, Collection<RefundStatus> statuses) {
        return refundJpaRepository.sumCancelledByOrderItem(orderId, sellerId, statuses);
    }

    // 단계 행은 따로 잠그지 않고 환불 행 잠금으로 대신한다(결정 4). 그래서 저장 전에 그 환불 행을 이 트랜잭션에서 잠갔는지 본다.
    // saveAndFlush로 INSERT를 바로 보내 유일 제약(refund_id, step) 위반이 이 자리에서 나게 한다. 같은 단계 행을 두 번 만드는 것은
    // 부르는 쪽 버그라 에러 코드로 바꾸지 않고 DataIntegrityViolationException 그대로 던진다
    @Override
    public RefundStep saveNew(RefundStep step) {
        requireRefundLockedInThisTransaction(step.getRefundId());
        RefundStepJpaEntity saved = refundStepJpaRepository.saveAndFlush(RefundStepMapper.toNewEntity(step));
        return RefundStepMapper.toDomain(saved);
    }

    // 환불 update와 같은 방식(getReference + isLoaded)으로 이 트랜잭션에서 불러온 단계 행에만 바뀌는 칸을 옮긴다. 실제 UPDATE는 커밋 때 나간다.
    // 단계 행의 잠금 모드는 보지 않는다. 단계 행은 잠금 조회하지 않고, 환불 행을 먼저 잠근 것으로 한 환불의 일을 한 번에 하나로 만든다.
    // 환불 잠금을 먼저 보는 이유: 둘 다 어겼을 때 더 근본적인 버그(잠금 없이 고치려 함)를 알리려고
    @Override
    public void update(RefundStep step) {
        if (step.getId() == null) {
            throw new IllegalStateException("저장하지 않은 단계 행은 update할 수 없습니다. saveNew로 저장해야 합니다.");
        }
        requireRefundLockedInThisTransaction(step.getRefundId());
        RefundStepJpaEntity entity = entityManager.getReference(RefundStepJpaEntity.class, step.getId());
        if (!entityManager.getEntityManagerFactory().getPersistenceUnitUtil().isLoaded(entity)) {
            throw new IllegalStateException("이 트랜잭션에서 불러온 단계 행만 update할 수 있습니다. refundStepId=" + step.getId());
        }
        RefundStepMapper.applyMutableState(step, entity);
    }

    @Override
    public Optional<RefundStep> findByRefundIdAndType(Long refundId, RefundStepType type) {
        return refundStepJpaRepository.findByRefundIdAndStep(refundId, type).map(RefundStepMapper::toDomain);
    }

    @Override
    public List<RefundStep> findAllByRefundId(Long refundId) {
        return refundStepJpaRepository.findAllByRefundIdOrderByIdAsc(refundId).stream()
                .map(RefundStepMapper::toDomain)
                .toList();
    }

    // findByIdForUpdate가 적어 둔 이 트랜잭션의 잠근 환불 ID 목록으로 확인한다. 같은 목록을 쓰므로 REQUIRES_NEW 안쪽 트랜잭션이 잠근 환불은
    // 바깥에서 잠근 것으로 보지 않는다(트랜잭션마다 따로 센다). 트랜잭션 밖이면 잠금 자체가 없으므로 막는다
    @SuppressWarnings("unchecked")
    private static void requireRefundLockedInThisTransaction(Long refundId) {
        Set<Long> locked = TransactionSynchronizationManager.isSynchronizationActive()
                ? (Set<Long>) TransactionSynchronizationManager.getResource(LOCKED_IDS_KEY)
                : null;
        if (locked == null || !locked.contains(refundId)) {
            throw new IllegalStateException("단계 행은 같은 트랜잭션에서 환불을 잠금 조회한 뒤에만 저장할 수 있습니다. refundId=" + refundId);
        }
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
