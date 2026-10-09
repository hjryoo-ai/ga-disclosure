package com.ga.disclosure.api.mapper;

import com.ga.disclosure.api.dto.CollectionRateList;
import com.ga.disclosure.api.dto.CollectionRateView;
import com.ga.disclosure.api.error.MalformedRequestException;
import com.ga.disclosure.domain.vo.RuleVersionId;
import com.ga.disclosure.rules.metric.CollectionRates;
import com.ga.disclosure.workflow.rate.CollectionRateStore;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** 징구율 조회 DTO 변환. 질의 형식 오류는 400(유스케이스 전) — 필드 이름만. */
public final class CollectionRateMapper {

    private static final Pattern MONTH = Pattern.compile("\\d{4}-\\d{2}");
    private static final Pattern ORG = Pattern.compile("/|(/[A-Za-z0-9_-]+)+");
    private static final Pattern RULE = Pattern.compile("[A-Z0-9][A-Z0-9_-]{0,63}");

    private CollectionRateMapper() {
    }

    public static YearMonth month(String value, String field) {
        if (value == null || !MONTH.matcher(value).matches()) {
            throw new MalformedRequestException(field);
        }
        try {
            return YearMonth.parse(value);
        } catch (DateTimeParseException e) {
            throw new MalformedRequestException(field);
        }
    }

    public static Optional<String> orgPath(String valueOrNull) {
        if (valueOrNull != null && !ORG.matcher(valueOrNull).matches()) {
            throw new MalformedRequestException("orgPath");
        }
        return Optional.ofNullable(valueOrNull);
    }

    public static Optional<RuleVersionId> ruleVersionId(String valueOrNull) {
        if (valueOrNull != null && !RULE.matcher(valueOrNull).matches()) {
            throw new MalformedRequestException("ruleVersionId");
        }
        return Optional.ofNullable(valueOrNull).map(RuleVersionId::of);
    }

    public static CollectionRateList list(List<CollectionRateStore.Row> rows) {
        return new CollectionRateList(CollectionRates.DEFINITION, CollectionRates.DEFINITION_TEXT, rows.stream().map(CollectionRateMapper::view).toList());
    }

    private static CollectionRateView view(CollectionRateStore.Row r) {
        return new CollectionRateView(r.snapshotId().toString(), YearMonth.from(r.periodMonth()).toString(), r.orgPath(), r.formula(),
                CollectionRates.DEFINITION, r.ruleVersionId().value(), r.denominator(), r.numerator(),
                r.rateBp().isPresent() ? r.rateBp().getAsInt() : null, r.computedAt().toString(), r.inputsHash());
    }
}
