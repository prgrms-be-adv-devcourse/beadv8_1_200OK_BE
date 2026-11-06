package com.ok.common.logging;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

import net.logstash.logback.mask.MaskingJsonGenerator;
import net.logstash.logback.mask.ValueMasker;
import tools.jackson.core.TokenStreamContext;

/**
 * 문자열 값에서 개인정보를 가리고, 필드 하나가 상한(기본 1KB)을 넘으면 자른다.
 *
 * <p>개인정보는 필드 이름({@link PersonalInfoFieldMasker})뿐 아니라 메시지 문장 안의 {@code receiverName=홍길동} 같은
 * {@code 키=값}, {@code 키: 값}, JSON {@code "키":"값"} 모양으로도 남으므로 같은 키 단어로 문장 안의 값도 가린다.
 * 값은 쉼표, 닫는 괄호, 따옴표, 줄 끝까지 가린다. 주소처럼 공백이 든 값이 일부만 남는 것보다 더 가리는 편이 낫다. 가리는 일을 먼저 하고 자른다.
 * 자르고 나면 가릴 값의 앞부분만 남아 패턴에 걸리지 않을 수 있기 때문이다.
 *
 * <p>상한은 UTF-8 바이트 기준이다. 한글은 글자당 3바이트라 글자 수로 세면 실제 크기가 3배가 된다.
 * 스택 트레이스 필드는 깊이 상한이 따로 있고 한 줄이 길어 더 큰 상한을 쓴다. 인코더의 글자 수 기준 상한만으로는 한글 예외 메시지가
 * 이벤트 상한(8KB)을 넘길 수 있어 여기서 바이트로 다시 자른다.
 */
public class SafeValueMasker implements ValueMasker {

    private static final String TRUNCATED = "…[잘림]";

    private Pattern keyValuePattern = Pattern.compile("(?!)");
    private int maxBytes = 1024;
    private String largeFieldName = "error";
    private int largeFieldMaxBytes = 6144;

    @Override
    public Object mask(TokenStreamContext context, Object value) {
        if (!(value instanceof String text)) {
            return null;
        }
        String result = keyValuePattern.matcher(text).replaceAll("$1" + MaskingJsonGenerator.MASK);
        boolean largeField = context.hasCurrentName() && largeFieldName.equals(context.currentName());
        result = truncate(result, largeField ? largeFieldMaxBytes : maxBytes);
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

    /** 쉼표로 구분한 키 단어. 문장 안의 {@code 키=값}에서 키에 이 단어가 들어 있으면 값을 가린다. */
    public void setKeys(String keys) {
        List<String> words = MaskKeys.parse(keys);
        if (words.isEmpty()) {
            return;
        }
        String alternation = String.join("|", words.stream().map(Pattern::quote).toList());
        keyValuePattern = Pattern.compile(
                "([\\w.\\-]*(?:" + alternation + ")[\\w.\\-]*\"?\\s*[=:]\\s*\"?)[^,;\\]})\"\\r\\n]+", Pattern.CASE_INSENSITIVE);
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
