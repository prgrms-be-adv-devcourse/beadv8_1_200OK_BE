package com.ok.media.repository;

import com.ok.media.domain.Media;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;

public interface MediaRepository extends JpaRepository<Media, Long> {

	long countByMemberIdAndCreatedAtAfter(Long memberId, LocalDateTime createdAt);
}
