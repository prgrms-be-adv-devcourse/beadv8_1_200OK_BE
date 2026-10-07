package com.ok;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModulithStructureTest {

    private final ApplicationModules modules = ApplicationModules.of(PaldoGotganApplication.class);

    @Test
    void 도메인_모듈이_모두_인식된다() {
        Set<String> identifiers = modules.stream()
                .map(module -> module.getIdentifier().toString())
                .collect(Collectors.toSet());

        assertThat(identifiers).containsExactlyInAnyOrder(
                "member", "wallet", "refund", "order", "payout", "product", "delivery", "common", "config");
    }

    @Test
    void 모듈_간_순환_의존과_내부_접근이_없다() {
        modules.verify();
    }
}
