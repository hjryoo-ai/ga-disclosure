package com.ga.disclosure.rules.template;

/**
 * 서식 항목 ↔ 값의 결속({@code fields[].render.bind}, 3A 수용심사 §3-1). 코드는 항목 코드를 모르고 이 닫힌 어휘로만 분기한다 — 어느 항목이
 * 어느 결속인지는 서식 데이터다. 결속마다 허용되는 {@link FieldScope}·{@link FieldSource}가 정해져 있고 서식 스키마·{@link TemplateField}가
 * 강제한다. 값을 찾는 규칙은 {@link BindingResolver} 한 곳에 있다(렌더러와 R-FIELD-REQUIRED가 같은 함수를 쓴다).
 */
public enum Bind {
    /** 확인서 번호(봉인이 발급, canonical 밖). 검증 시점에는 발급 예정. */
    HEADER_DISCLOSURE_NO(FieldScope.PER_DOCUMENT, FieldSource.SYSTEM, true),
    /** 상담일({@code yyyy-MM-dd} 원문). */
    HEADER_CONSULT_DATE(FieldScope.PER_DOCUMENT, FieldSource.SYSTEM, true),
    /** 설계사. */
    HEADER_AGENT(FieldScope.PER_DOCUMENT, FieldSource.SYSTEM, true),
    /** 고객 성명(봉인 유스케이스가 복호화해 canonical에 싣는다). 검증 시점에는 봉인 예정. */
    HEADER_CUSTOMER_NAME(FieldScope.PER_DOCUMENT, FieldSource.SYSTEM, true),
    /** 유사상품군 이름(상담일 카탈로그). */
    HEADER_PRODUCT_GROUP(FieldScope.PER_DOCUMENT, FieldSource.CATALOG, false),
    /** 추천가능 보험사(상담일 위탁 패널) 이름 목록. */
    PANEL_INSURERS(FieldScope.PER_DOCUMENT, FieldSource.SYSTEM, false),
    /** 항목 보험사 이름(상담일 패널에 있을 때). */
    ITEM_INSURER_NAME(FieldScope.PER_ITEM, FieldSource.CATALOG, false),
    /** 항목 상품명. */
    ITEM_PRODUCT_NAME(FieldScope.PER_ITEM, FieldSource.CATALOG, false),
    /** 항목값 {@code fieldValues[code]} — 카탈로그 기본값 복사 또는 임시등록 입력(출처 무관). */
    CATALOG_DEFAULT(FieldScope.PER_ITEM, FieldSource.CATALOG, false),
    /** 설계사 입력 항목값 {@code fieldValues[code]} — 출처가 설계사인 값만. 추천사유로 대체되지 않는다. */
    AGENT_INPUT(FieldScope.PER_ITEM, FieldSource.AGENT, false),
    /** 엔진 등급 라벨, 산출불가면 서식의 {@code unavailableText}. */
    ENGINE_GRADE_LABEL(FieldScope.PER_ITEM, FieldSource.ENGINE, false),
    /** 엔진 세트 내 순위, 산출불가면 서식의 {@code unavailableText}. */
    ENGINE_RANK(FieldScope.PER_ITEM, FieldSource.ENGINE, false),
    /** 산출불가 문구와 사유. 산출된 항목은 빈 칸(값이 없다는 판단이 곧 값). */
    ENGINE_UNAVAILABLE_TEXT(FieldScope.PER_ITEM, FieldSource.ENGINE, false),
    /** 추천사유(라벨들과 텍스트). 비추천 항목은 빈 칸. */
    RECOMMENDATION(FieldScope.PER_ITEM, FieldSource.AGENT, false);

    private final FieldScope scope;
    private final FieldSource source;
    private final boolean identification;

    Bind(FieldScope scope, FieldSource source, boolean identification) {
        this.scope = scope;
        this.source = source;
        this.identification = identification;
    }

    public FieldScope scope() {
        return scope;
    }

    public FieldSource source() {
        return source;
    }

    /** 문서 식별부(서식 {@code section: HEADER}) 결속인가 — 비교 항목이 아니다(3B 계획 승인 Q2). */
    public boolean identification() {
        return identification;
    }

    /** 산출불가 문구({@code render.unavailableText})가 필요한 결속인가. */
    public boolean needsUnavailableText() {
        return source == FieldSource.ENGINE;
    }
}
