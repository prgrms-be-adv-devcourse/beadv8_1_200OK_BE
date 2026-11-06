package com.ok.common.logging;

import java.nio.charset.StandardCharsets;

import net.logstash.logback.mask.ValueMasker;
import tools.jackson.core.TokenStreamContext;

/**
 * 문자열 값 하나가 상한(기본 1KB)을 넘으면 자른다.
 *
 * <p>개인정보는 필드 이름으로만 가린다({@link PersonalInfoFieldMasker}). 메시지 문장 속의 {@code 키=값}은 정규식으로 찾아 가리지 않는다.
 * 긴 문자열에서 정규식이 길이의 제곱에 비례해 느려지고, {@code NameNotFoundException: 원인}처럼 장애 원인까지 가렸기 때문이다.
 * 개인정보는 로그 문장에 섞어 쓰지 않는 것이 원칙이다.
 *
 * <p>상한은 UTF-8 바이트 기준이다. 한글은 글자당 3바이트라 글자 수로 세면 실제 크기가 3배가 된다.
 * 스택 트레이스 필드는 깊이 상한이 따로 있고 한 줄이 길어 더 큰 상한을 쓴다. 인코더의 글자 수 기준 상한만으로는 한글 예외 메시지가
 * 이벤트 상한(8KB)을 넘길 수 있어 여기서 바이트로 다시 자른다.
 */
public class SafeValueMasker implements ValueMasker {

    private static final String TRUNCATED = "…[잘림]";

    private int maxBytes = 1024;
    private String largeFieldName = "error";
    private int largeFieldMaxBytes = 6144;

    @Override
    public Object mask(TokenStreamContext context, Object value) {
        if (!(value instanceof String text)) {
            return null;
        }
        boolean largeField = context.hasCurrentName() && largeFieldName.equals(context.currentName());
        String result = truncate(text, largeField ? largeFieldMaxBytes : maxBytes);
        return result.equals(text) ? null : result;
    }

    private String truncate(String text, int maxBytes) {
        // UTF-16 문자 하나는 UTF-8로 최대 3바이트라 이 길이 이하면 바이트를 세지 않아도 상한 안이다. 대부분의 짧은 값이 여기서 끝난다
        if (text.length() <= maxBytes / 3) {
            return text;
        }
        int limit = maxBytes - TRUNCATED.getBytes(StandardCharsets.UTF_8).length;
        int bytes = 0;
        int end = 0;
        while (end < text.length()) {
            int codePoint = text.codePointAt(end);
            int size = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + size > limit) {
                break;
            }
            bytes += size;
            end += Character.charCount(codePoint);
        }
        if (end >= text.length()) {
            return text;
        }
        return text.substring(0, end) + TRUNCATED;
    }

    /** 필드 하나의 최대 크기(UTF-8 바이트). */
    public void setMaxBytes(int maxBytes) {
        this.maxBytes = maxBytes;
    }

    /** 스택 트레이스 필드의 이름. 이 필드는 더 큰 상한({@code largeFieldMaxBytes})을 쓴다. */
    public void setLargeFieldName(String largeFieldName) {
        this.largeFieldName = largeFieldName;
    }

    /** 스택 트레이스 필드의 최대 크기(UTF-8 바이트). */
    public void setLargeFieldMaxBytes(int largeFieldMaxBytes) {
        this.largeFieldMaxBytes = largeFieldMaxBytes;
    }
}
