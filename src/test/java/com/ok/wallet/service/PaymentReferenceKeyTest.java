package com.ok.wallet.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentReferenceKeyTest {

    @Test
    void 주문_ID로_근거_키를_만들고_다시_꺼낼_수_있다() {
        String key = PaymentReferenceKey.of(1234L);

        assertThat(key).isEqualTo("PAYMENT:1234");
        assertThat(PaymentReferenceKey.orderIdOf(key)).isEqualTo(1234L);
    }

    @Test
    void 접두어가_다르면_null을_돌려준다() {
        assertThat(PaymentReferenceKey.orderIdOf("CHARGE:pay-1")).isNull();
    }

    @Test
    void 주문_ID가_숫자가_아니면_null을_돌려준다() {
        assertThat(PaymentReferenceKey.orderIdOf("PAYMENT:abc")).isNull();
        assertThat(PaymentReferenceKey.orderIdOf("PAYMENT:")).isNull();
    }

    @Test
    void 근거_키가_없으면_null을_돌려준다() {
        assertThat(PaymentReferenceKey.orderIdOf(null)).isNull();
    }
}
