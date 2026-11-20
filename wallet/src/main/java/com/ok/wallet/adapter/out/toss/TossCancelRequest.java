package com.ok.wallet.adapter.out.toss;

// 토스 결제 취소 요청 본문. 금액(cancelAmount)을 보내지 않으면 전액 취소된다
record TossCancelRequest(String cancelReason) {
}
