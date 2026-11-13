package com.ok.carriersimulator.core;

/**
 * 보낼 알림 한 건. 같은 변화를 다시 보내면 attemptNo가 늘어난 새 시도가 된다.
 *
 * @param attemptNo 1부터 시작하는 시도 번호
 */
public record CallbackAttempt(long id, ParcelKey key, String callbackUrl, int attemptNo) {
}
