package com.ok.common.jpa.entity;

import jakarta.persistence.*;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

import static jakarta.persistence.GenerationType.IDENTITY;

/**
 * ID와 생성/수정 시각을 함께 관리하는 베이스 엔티티.
 *
 * @deprecated ID 생성 전략이 모든 하위 엔티티에 강제되는 문제로 사용 중지.
 * 시간 필드는 {@link BaseTime}을 상속하고, ID는 각 엔티티에서 직접 선언할 것.
 * <pre>{@code
 * @Entity
 * public class Member extends BaseTime {
 *     @Id
 *     @GeneratedValue(strategy = IDENTITY)
 *     private Long id;
 * }
 * }</pre>
 * 각 모듈의 기존 엔티티 마이그레이션 완료 후 제거 예정.
 */
@Deprecated(forRemoval = true)
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
@Getter
public abstract class BaseIdAndTime extends BaseEntity{
    @Id
    @GeneratedValue(strategy = IDENTITY)
    private Long id;
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}