package com.ok.testsupport;

/**
 * 테스트 리스너({@link OrderKafkaTestListener})가 받는 메시지. {@code mode}로 처리 결과를 고른다.
 *
 * @param id   메시지를 구분하는 값. Kafka 키로도 쓴다
 * @param mode {@code ok}(성공), {@code invalid}(값이 틀림), {@code fail}(계속 실패), {@code flaky}(처음 한 번만 실패)
 */
public record OrderKafkaTestMessage(String id, String mode) {
}
