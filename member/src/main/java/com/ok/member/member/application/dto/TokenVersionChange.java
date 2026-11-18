package com.ok.member.member.application.dto;

import java.time.LocalDateTime;

/**
 * 회원의 토큰 버전을 올린 결과. 토큰 검증기가 읽는 저장소에 남길 값이다.
 *
 * @param tokenVersion 올린 뒤의 버전. 이보다 낮은 버전의 토큰은 거부된다
 * @param changedAt    버전을 올린 시각. 기록을 남길 시간의 기준이다
 */
public record TokenVersionChange(
        Long memberId,
        long tokenVersion,
        LocalDateTime changedAt
) {
}
