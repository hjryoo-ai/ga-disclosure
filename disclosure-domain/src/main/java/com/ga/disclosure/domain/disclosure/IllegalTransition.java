package com.ga.disclosure.domain.disclosure;

import com.ga.disclosure.domain.enums.DisclosureStatus;

import java.util.Objects;

/** 상태 × 명령 표 밖의 전이 시도(설계서 §6.1). 명령 거부이며 업무 트랜잭션은 롤백된다. */
public final class IllegalTransition extends RuntimeException {

    private final DisclosureStatus from;
    private final DisclosureCommand command;

    public IllegalTransition(DisclosureStatus from, DisclosureCommand command) {
        super(command + " is not allowed in " + from);
        this.from = Objects.requireNonNull(from, "from");
        this.command = Objects.requireNonNull(command, "command");
    }

    public DisclosureStatus from() {
        return from;
    }

    public DisclosureCommand command() {
        return command;
    }
}
