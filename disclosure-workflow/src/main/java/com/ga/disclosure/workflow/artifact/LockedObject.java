package com.ga.disclosure.workflow.artifact;

import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.Sha256;

/**
 * Object Lock을 거는 저장 객체 1건(산출물·서명 증거, 4 계획 승인 Q2): 잔여물 정리·재적용이 같은 코드 경로로 다룬다. 둘 다 문서 키로 암호화되고
 * 보존 기록(첫 적용 시각 1회, 적용 기한 증가만)을 갖는다.
 */
public sealed interface LockedObject permits ArtifactRecord, SignatureEvidenceRecord {

    DisclosureId disclosureId();

    String storageKey();

    /** 평문 SHA-256·길이(열람 대조). */
    Sha256 sha256();

    long bytes();

    /** 감사 상세에 쓰는 종류 이름(산출물 종류 또는 서명 증거 종류). */
    String kindName();
}
