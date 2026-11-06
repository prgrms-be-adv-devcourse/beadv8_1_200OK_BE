package com.ok.common.logging;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** 쉼표로 구분한 마스킹 키 설정을 소문자 목록으로 바꾼다. */
final class MaskKeys {

    private MaskKeys() {
    }

    static List<String> parse(String commaSeparated) {
        return Arrays.stream(commaSeparated.split(","))
                .map(key -> key.trim().toLowerCase(Locale.ROOT))
                .filter(key -> !key.isEmpty())
                .toList();
    }
}
