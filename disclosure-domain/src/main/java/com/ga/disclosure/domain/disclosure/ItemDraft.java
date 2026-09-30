package com.ga.disclosure.domain.disclosure;

import com.ga.disclosure.domain.vo.GroupCode;
import com.ga.disclosure.domain.vo.InsurerCode;
import com.ga.disclosure.domain.vo.ProductKey;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 비교 항목 1건의 입력(항목 교체 명령의 원소). 등급은 없다 — 등급은 스냅샷 적용이 채운다.
 * <ul>
 *   <li>카탈로그 상품: 상품키가 있고 접두가 보험사 코드와 같으며 발행번호가 없다.</li>
 *   <li>임시등록 상품({@code tempProduct}): 상품키가 없고(엔진 도메인 밖, 3A 계획 Q4) 가입설계서 발행번호가 <b>필수</b>다(3A W3).
 *       엔진 요청에서 빠지고, 스냅샷 적용 시 로컬 {@code UNAVAILABLE(TEMP_PRODUCT, LOCAL)}로 채워진다.</li>
 * </ul>
 */
public record ItemDraft(
        ProductKey productKeyOrNull,
        InsurerCode insurer,
        GroupCode group,
        String productName,
        boolean tempProduct,
        String quoteDocNoOrNull,
        boolean recommended,
        boolean requestedByCustomer,
        Map<String, FieldValue> fieldValues) {

    /** 발행번호 형식: 공백 없는 문자·숫자·구분자 1~64자(보험사 체계마다 달라 좁히지 않는다). */
    private static final Pattern QUOTE_DOC_NO = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,63}");
    private static final Pattern FIELD_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    public ItemDraft {
        Objects.requireNonNull(insurer, "insurer");
        Objects.requireNonNull(group, "group");
        if (productName == null || productName.isBlank() || productName.length() > 200) {
            throw new IllegalArgumentException("product name is required (≤ 200 chars)");
        }
        if (tempProduct) {
            if (productKeyOrNull != null) {
                throw new IllegalArgumentException("a temp product has no product key (it is not in the engine's domain)");
            }
            if (quoteDocNoOrNull == null || !QUOTE_DOC_NO.matcher(quoteDocNoOrNull).matches()) {
                throw new IllegalArgumentException("a temp product requires its quote document number");
            }
        } else {
            Objects.requireNonNull(productKeyOrNull, "a catalog product requires its product key");
            if (!productKeyOrNull.insurer().equals(insurer)) {
                throw new IllegalArgumentException("product key " + productKeyOrNull + " does not belong to insurer " + insurer);
            }
            if (quoteDocNoOrNull != null) {
                throw new IllegalArgumentException("only a temp product carries a quote document number");
            }
        }
        Map<String, FieldValue> copy = new LinkedHashMap<>();
        Objects.requireNonNull(fieldValues, "fieldValues").forEach((code, value) -> {
            if (code == null || !FIELD_CODE.matcher(code).matches()) {
                throw new IllegalArgumentException("invalid field code: " + code);
            }
            copy.put(code, Objects.requireNonNull(value, "value of " + code));
        });
        fieldValues = Collections.unmodifiableMap(copy);
    }

    public static ItemDraft catalog(ProductKey key, GroupCode group, String productName, boolean recommended, boolean requestedByCustomer,
                                    Map<String, FieldValue> fieldValues) {
        return new ItemDraft(key, key.insurer(), group, productName, false, null, recommended, requestedByCustomer, fieldValues);
    }

    public static ItemDraft temp(InsurerCode insurer, GroupCode group, String productName, String quoteDocNo, boolean recommended,
                                 boolean requestedByCustomer, Map<String, FieldValue> fieldValues) {
        return new ItemDraft(null, insurer, group, productName, true, quoteDocNo, recommended, requestedByCustomer, fieldValues);
    }

    public Optional<ProductKey> productKey() {
        return Optional.ofNullable(productKeyOrNull);
    }

    public Optional<String> quoteDocNo() {
        return Optional.ofNullable(quoteDocNoOrNull);
    }
}
