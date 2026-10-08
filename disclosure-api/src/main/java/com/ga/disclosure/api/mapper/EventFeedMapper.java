package com.ga.disclosure.api.mapper;

import com.ga.disclosure.api.dto.EventAckReceipt;
import com.ga.disclosure.api.dto.EventAckRequest;
import com.ga.disclosure.api.dto.EventFeedView;
import com.ga.disclosure.api.error.MalformedRequestException;
import com.ga.disclosure.workflow.feed.EventFeed;

import java.util.OptionalLong;

/** 피드 매개변수(계약 {@code disclosure-internal}): {@code afterSeq} ≥ 0(생략하면 ack한 지점), {@code limit} 1~1000(기본 100). */
public final class EventFeedMapper {

    public static final int DEFAULT_LIMIT = 100;
    public static final int SCHEMA_VERSION = 1;

    private EventFeedMapper() {
    }

    public static OptionalLong afterSeq(Long afterSeqOrNull) {
        if (afterSeqOrNull == null) {
            return OptionalLong.empty();
        }
        if (afterSeqOrNull < 0) {
            throw new MalformedRequestException("afterSeq");
        }
        return OptionalLong.of(afterSeqOrNull);
    }

    public static int limit(Integer limitOrNull) {
        int limit = limitOrNull == null ? DEFAULT_LIMIT : limitOrNull;
        if (limit < 1 || limit > EventFeed.MAX_LIMIT) {
            throw new MalformedRequestException("limit");
        }
        return limit;
    }

    public static long upToSeq(EventAckRequest request) {
        if (request == null || request.upToSeq() == null || request.upToSeq() < 0) {
            throw new MalformedRequestException("upToSeq");
        }
        return request.upToSeq();
    }

    public static EventFeedView view(EventFeed.FeedPage page) {
        return new EventFeedView(page.events(), page.nextSeq(), page.headSeq(), SCHEMA_VERSION);
    }

    public static EventAckReceipt receipt(EventFeed.AckResult result) {
        return new EventAckReceipt(result.ackedSeq(), result.headSeq());
    }
}
