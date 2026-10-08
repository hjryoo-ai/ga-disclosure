package com.ga.disclosure.workflow.authz;

import com.ga.disclosure.domain.vo.DisclosureId;

import java.util.Objects;
import java.util.UUID;

/** 인가 대상. 범위 판정에 필요한 사실(확인서의 설계사·조직, 존재)은 어댑터가 RLS 아래에서 읽는다 — 다른 테넌트의 대상은 "없음"이다. */
public sealed interface Target {

    /** 대상 없음(테넌트 단위 행위: 초안 작성·배치·피드). */
    record None() implements Target {
    }

    record Disclosure(DisclosureId id) implements Target {
        public Disclosure {
            Objects.requireNonNull(id, "id");
        }
    }

    record Session(UUID id) implements Target {
        public Session {
            Objects.requireNonNull(id, "id");
        }
    }

    record Hold(UUID id) implements Target {
        public Hold {
            Objects.requireNonNull(id, "id");
        }
    }

    record Job(UUID id) implements Target {
        public Job {
            Objects.requireNonNull(id, "id");
        }
    }

    static Target none() {
        return new None();
    }

    static Target disclosure(DisclosureId id) {
        return new Disclosure(id);
    }

    static Target job(java.util.UUID id) {
        return new Job(id);
    }

    /** 감사용 종류·ID(응답에는 싣지 않는다). */
    default String kind() {
        return switch (this) {
            case None n -> "TENANT";
            case Disclosure d -> "DISCLOSURE";
            case Session s -> "SIGN_SESSION";
            case Hold h -> "LEGAL_HOLD";
            case Job j -> "JOB";
        };
    }

    default String idOrNull() {
        return switch (this) {
            case None n -> null;
            case Disclosure d -> d.id().toString();
            case Session s -> s.id().toString();
            case Hold h -> h.id().toString();
            case Job j -> j.id().toString();
        };
    }
}
