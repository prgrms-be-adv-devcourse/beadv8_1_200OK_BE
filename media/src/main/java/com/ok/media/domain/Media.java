package com.ok.media.domain;

import com.ok.media.MediaSchema;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "media", catalog = MediaSchema.NAME)
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Media {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	// 업로드 횟수 제한과 업로더 추적에 쓴다. 컬럼 추가 전에 올라온 미디어는 업로더를 알 수 없어 NULL이다
	@Column(name = "member_id")
	private Long memberId;

	@Enumerated(EnumType.STRING)
	@Column(name = "purpose", length = 30, nullable = false)
	private MediaPurpose purpose;

	@Column(name = "object_key", length = 255, nullable = false, unique = true)
	private String objectKey;

	@Column(name = "content_type", length = 40, nullable = false)
	private String contentType;

	@Column(name = "file_size", nullable = false)
	private long fileSize;

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	private Media(Long memberId, MediaPurpose purpose, String objectKey, String contentType, long fileSize) {
		this.memberId = memberId;
		this.purpose = purpose;
		this.objectKey = objectKey;
		this.contentType = contentType;
		this.fileSize = fileSize;
	}

	public static Media of(Long memberId, MediaPurpose purpose, String objectKey, String contentType, long fileSize) {
		return new Media(memberId, purpose, objectKey, contentType, fileSize);
	}
}
