package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.domain.vo.DisclosureId;

import java.util.List;

/** 예외 승인 저장소 포트(append-only — 수정·삭제 메서드 없음, DB GD030·GD080이 이중으로 막는다). */
public interface ReviewStore {

    void append(Review review);

    List<Review> findFor(DisclosureId disclosureId);
}
