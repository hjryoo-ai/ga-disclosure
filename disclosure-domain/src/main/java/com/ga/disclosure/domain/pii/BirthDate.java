package com.ga.disclosure.domain.pii;

import com.ga.disclosure.domain.enums.PiiField;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Arrays;
import java.util.Objects;

/**
 * 고객 생년월일(원격 서명 본인확인, D-10). 정규형은 {@code yyyy-MM-dd}. 1900-01-01 이후만 받는다.
 * 본인확인 대조는 {@link #matches(Sensitive, String)} — 두 값을 8바이트 {@code yyyyMMdd}로 맞춰 상수 시간 비교하고
 * 결과(boolean)만 돌려준다. 입력값은 어떤 메시지·로그에도 남기지 않는다.
 */
public final class BirthDate implements SensitiveValue {

    private static final LocalDate EARLIEST = LocalDate.of(1900, 1, 1);
    private static final DateTimeFormatter COMPACT = DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);
    /** 형식이 틀린 입력도 같은 길이로 비교하기 위한 자리값. 유효한 생년월일의 정규형과 같을 수 없다. */
    private static final byte[] INVALID = "????????".getBytes(StandardCharsets.US_ASCII);

    private final LocalDate value;

    private BirthDate(LocalDate value) {
        this.value = value;
    }

    public static Sensitive<BirthDate> of(LocalDate date) {
        Objects.requireNonNull(date, "birth date");
        if (date.isBefore(EARLIEST)) {
            throw new IllegalArgumentException("birth date must be on or after " + EARLIEST);
        }
        return Sensitive.of(new BirthDate(date));
    }

    /** {@code yyyy-MM-dd} 또는 {@code yyyyMMdd}. */
    public static Sensitive<BirthDate> parse(String raw) {
        byte[] compact = compact(raw);
        if (compact == INVALID) {
            throw new IllegalArgumentException("birth date must be yyyy-MM-dd or yyyyMMdd");
        }
        return of(LocalDate.parse(new String(compact, StandardCharsets.US_ASCII), COMPACT));
    }

    /**
     * 본인확인 대조. 입력을 정규화(형식이 틀리면 자리값)한 뒤 저장값과 {@link MessageDigest#isEqual}로 비교한다 —
     * 형식 오류와 불일치가 같은 비교 경로를 지난다. 입력값·저장값은 반환하지도 기록하지도 않는다.
     */
    public static boolean matches(Sensitive<BirthDate> stored, String input) {
        Objects.requireNonNull(stored, "stored");
        byte[] candidate = compact(input);
        byte[] expected = stored.reveal(BirthDate::compactBytes);
        boolean equal = MessageDigest.isEqual(expected, candidate);
        Arrays.fill(expected, (byte) 0);
        return equal && candidate != INVALID;
    }

    private static byte[] compact(String raw) {
        if (raw == null) {
            return INVALID;
        }
        String digits = raw.strip();
        if (digits.length() == 10 && digits.charAt(4) == '-' && digits.charAt(7) == '-') {
            digits = digits.substring(0, 4) + digits.substring(5, 7) + digits.substring(8, 10);
        }
        if (digits.length() != 8 || !digits.chars().allMatch(ch -> ch >= '0' && ch <= '9')) {
            return INVALID;
        }
        try {
            LocalDate.parse(digits, COMPACT);
        } catch (DateTimeException e) {
            return INVALID;
        }
        return digits.getBytes(StandardCharsets.US_ASCII);
    }

    private byte[] compactBytes() {
        return value.format(COMPACT).getBytes(StandardCharsets.US_ASCII);
    }

    @Override
    public PiiField field() {
        return PiiField.BIRTH_DATE;
    }

    @Override
    public String canonical() {
        return value.toString();
    }

    @Override
    public String toString() {
        return "BirthDate[****]";
    }
}
