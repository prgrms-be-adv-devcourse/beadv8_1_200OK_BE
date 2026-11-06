package com.ok.common.logging;

import java.util.List;
import java.util.Locale;

import net.logstash.logback.mask.FieldMasker;
import net.logstash.logback.mask.MaskingJsonGenerator;
import tools.jackson.core.TokenStreamContext;

/**
 * 이름에 개인정보 키 단어가 들어간 JSON 필드의 값을 통째로 가린다.
 *
 * <p>정확히 일치하는 이름만 가리면 {@code receiverName}, {@code buyerEmail}처럼 이름이 조금 다른 필드가 새므로
 * 필드 이름에 단어가 포함되기만 해도(대소문자 무시) 가린다. 안 가려서 새는 것보다 더 가리는 편이 낫다고 보았다.
 * 스키마 필드와 MDC 키에는 이 단어가 없다. logstash 기본 필드({@code logger_name}, {@code thread_name})는 걸리므로
 * 인코더 설정에서 {@code logger}, {@code thread}로 이름을 바꿔 둔다.
 */
public class PersonalInfoFieldMasker implements FieldMasker {

    private List<String> keys = List.of();

    @Override
    public Object mask(TokenStreamContext context) {
        if (!context.hasCurrentName()) {
            return null;
        }
        String name = context.currentName().toLowerCase(Locale.ROOT);
        for (String key : keys) {
            if (name.contains(key)) {
                return MaskingJsonGenerator.MASK;
            }
        }
        return null;
    }

    /** 쉼표로 구분한 키 단어. 예: {@code name,phone,address,email,receiver} */
    public void setKeys(String keys) {
        this.keys = MaskKeys.parse(keys);
    }
}
