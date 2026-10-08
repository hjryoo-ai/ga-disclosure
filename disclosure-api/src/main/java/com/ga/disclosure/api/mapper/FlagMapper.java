package com.ga.disclosure.api.mapper;

import com.ga.disclosure.api.dto.FlagList;
import com.ga.disclosure.api.dto.FlagPage;
import com.ga.disclosure.api.dto.FlagView;
import com.ga.disclosure.api.error.MalformedRequestException;
import com.ga.disclosure.workflow.flag.FlagLookup;
import com.ga.disclosure.workflow.page.Page;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** 준법 플래그 DTO 변환. 필터 형식 오류는 400(유스케이스 전). */
public final class FlagMapper {

    private static final Pattern TYPE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    private FlagMapper() {
    }

    public static Optional<FlagLookup.FlagStatus> status(String statusOrNull) {
        if (statusOrNull == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(FlagLookup.FlagStatus.valueOf(statusOrNull));
        } catch (IllegalArgumentException e) {
            throw new MalformedRequestException("status");
        }
    }

    public static Optional<String> type(String typeOrNull) {
        if (typeOrNull != null && !TYPE.matcher(typeOrNull).matches()) {
            throw new MalformedRequestException("type");
        }
        return Optional.ofNullable(typeOrNull);
    }

    public static FlagPage page(Page<FlagLookup.Listed> page) {
        return new FlagPage(page.items().stream().map(FlagMapper::view).toList(), page.next().orElse(null));
    }

    public static FlagList list(List<FlagLookup.Listed> flags) {
        return new FlagList(flags.stream().map(FlagMapper::view).toList());
    }

    static FlagView view(FlagLookup.Listed f) {
        return new FlagView(f.flagId().toString(), f.type(), f.status().name(), f.raisedAt().toString(),
                f.disclosureId().map(d -> d.value().toString()).orElse(null), f.disclosureNo().orElse(null));
    }
}
