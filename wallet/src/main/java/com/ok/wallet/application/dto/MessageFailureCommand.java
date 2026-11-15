package com.ok.wallet.application.dto;

/**
 * 끝내 처리하지 못한 메시지를 실패 기록으로 남길 때 넘기는 값이다.
 *
 * @param key           메시지 키. 없을 수 있다
 * @param payload       메시지 원문을 UTF-8로 읽은 값. 없을 수 있다
 * @param failureReason 마지막 실패의 예외 종류와 메시지
 */
public record MessageFailureCommand(
        String topic,
        int partition,
        long offset,
        String key,
        String payload,
        String failureReason
) {
}
