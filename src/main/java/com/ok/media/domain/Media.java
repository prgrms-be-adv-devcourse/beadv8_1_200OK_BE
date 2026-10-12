package com.ok.media.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "media")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Media {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

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

	private Media(MediaPurpose purpose, String objectKey, String contentType, long fileSize) {
		this.purpose = purpose;
		this.objectKey = objectKey;
		this.contentType = contentType;
		this.fileSize = fileSize;
	}

	public static Media of(MediaPurpose purpose, String objectKey, String contentType, long fileSize) {
		return new Media(purpose, objectKey, contentType, fileSize);
	}
}
