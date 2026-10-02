package com.ga.disclosure.workflow.sign;

import com.ga.disclosure.domain.vo.DisclosureId;

import java.time.LocalDate;
import java.util.List;

/**
 * 서명 레코드 저장소 포트(V8 {@code signature}, append-only — INSERT 트리거가 두 해시·부모 상태·세션·서명자 집합을 검사한다, GD021·022·102~104).
 */
public interface SignatureStore {

    void insert(StoredSignature signature);

    /** 확인서의 서명(서명 시각 순). */
    List<StoredSignature> signatures(DisclosureId disclosure);

    /**
     * 대리 서명 탐지 집계(4 계획 §5): 같은 테넌트의 REMOTE_LINK 고객 서명 중 기기 지문이 같고 서명일(Asia/Seoul)이 {@code day}인 서명의 서로 다른 고객 수.
     */
    int distinctCustomersByDevice(String fingerprint, LocalDate day);

    /** 같은 식, 키 = IP. */
    int distinctCustomersByIp(String ip, LocalDate day);
}
