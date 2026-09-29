package com.ga.disclosure.rules.validation;

import com.ga.disclosure.domain.enums.SignerRole;
import com.ga.disclosure.domain.grade.GradeSnapshot;
import com.ga.disclosure.domain.vo.GroupCode;
import com.ga.disclosure.domain.vo.InsurerCode;
import com.ga.disclosure.domain.vo.ProductKey;
import com.ga.disclosure.domain.vo.ReasonCode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 검증 규칙이 보는 확인서의 단면. 검증이 필요로 하는 것만 노출한다 — 워크플로 애그리게이트가 Phase 3에서 구현하고,
 * 그 전까지 구현체는 테스트 픽스처에만 둔다(Phase 1 지시문 "하지 말 것").
 */
public interface ValidationSubject {

    /** 헤더의 유사상품군. */
    GroupCode groupCode();

    /** 상담일 = 룰·서식 기준일. */
    LocalDate consultDate();

    /** 테넌트 플래그: 설계사 500인 이상. */
    boolean largeGa();

    /** 비교 항목(표의 열), 입력 순서. */
    List<Item> items();

    /** 엔진 등급·순위 스냅샷(산출 전이면 비어 있음). */
    Optional<GradeSnapshot> gradeSnapshot();

    /** 확인서당 한 값인 서식 항목({@code render.scope = PER_DOCUMENT})의 값. */
    Map<String, String> documentFieldValues();

    /** 받은 서명(서명 시각 순). */
    List<SignatureMark> signatures();

    /** 서명 기한(봉인 시 확정, 봉인 전이면 비어 있음). */
    Optional<Instant> signDeadline();

    /** 보험사가 기준일에 추천가능 보험사(위탁 패널)인가. Phase 2 카탈로그 전이므로 주입된 판정 함수다. */
    boolean isInsurerOnPanel(InsurerCode insurer, LocalDate date);

    /** 비교 항목 1건. */
    interface Item {

        ProductKey productKey();

        InsurerCode insurerCode();

        GroupCode groupCode();

        /** 설계사가 추천한 항목인가(추천사유 대상). */
        boolean isRecommended();

        /** 고객이 넣어 달라고 요청한 보험사 상품인가. */
        boolean requestedByCustomer();

        /** 카탈로그에 없어 임시등록한 상품인가. */
        boolean tempProduct();

        /** 임시등록 상품의 발행(견적서) 번호. */
        Optional<String> quoteDocNo();

        /** 항목(열)마다 한 값인 서식 항목({@code render.scope = PER_ITEM})의 값. */
        Map<String, String> fieldValues();

        /** 추천사유 코드(설계사 선택 + 시스템 자동 부가). */
        List<ReasonCode> reasonCodes();

        /** 추천사유 텍스트. */
        Optional<String> reasonText();
    }

    /** 서명 1건: 역할과 서명 시각. */
    record SignatureMark(SignerRole role, Instant signedAt) {
        public SignatureMark {
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(signedAt, "signedAt");
        }
    }
}
