package com.ga.disclosure.workflow.gate;

import com.ga.disclosure.domain.enums.SignerRole;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.workflow.contract.ContractLinkStore;

import java.util.List;

/**
 * 게이트 조회(읽기 전용, 잠그지 않는다). 후보 규칙은 계약 연결 매칭과 <b>같은 문장</b>이다 — 어댑터가 {@link ContractLinkStore}의 후보 조회를 그대로 쓴다
 * (무효·정정·폐기 상태와 파기된 확인서는 후보가 아니다, 증권번호는 활성 연결만).
 */
public interface GateLookup {

    List<ContractLinkStore.Candidate> byApplicationNo(String applicationNo);

    List<ContractLinkStore.Candidate> byActivePolicy(String policyNo);

    /** 그 확인서에 서명한 역할(서명은 지금 문서 해시에 귀속 — DB 불변식). */
    List<SignerRole> signedRoles(DisclosureId id);
}
