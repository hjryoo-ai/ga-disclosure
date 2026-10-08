package com.ga.disclosure.api.internal;

import com.ga.disclosure.api.dto.EventAckReceipt;
import com.ga.disclosure.api.dto.EventAckRequest;
import com.ga.disclosure.api.dto.EventFeedView;
import com.ga.disclosure.api.mapper.EventFeedMapper;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.feed.EventFeed;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 이벤트 피드(서비스 주체 FEED_CONSUMER — 6A 계획 §4.1, 승인 Q5). 인가는 유스케이스가 한다. */
@RestController
@RequestMapping("/internal/v1/events")
public class InternalEventsController {

    private final EventFeed feed;

    public InternalEventsController(EventFeed feed) {
        this.feed = feed;
    }

    @GetMapping
    public EventFeedView read(Caller caller, @RequestParam(name = "afterSeq", required = false) Long afterSeq,
                              @RequestParam(name = "limit", required = false) Integer limit) {
        return EventFeedMapper.view(feed.read(caller, EventFeedMapper.afterSeq(afterSeq), EventFeedMapper.limit(limit)));
    }

    @PostMapping("/ack")
    public EventAckReceipt ack(Caller caller, @RequestBody(required = false) EventAckRequest request) {
        return EventFeedMapper.receipt(feed.ack(caller, EventFeedMapper.upToSeq(request)));
    }
}
