package com.ga.disclosure.api.rest;

import com.ga.disclosure.api.dto.CollectionRateList;
import com.ga.disclosure.api.mapper.CollectionRateMapper;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.rate.CollectionRateService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 징구율(6B 계획 §5 — 내부 지표, 규제 정의 없음): 준법은 테넌트 전체(테넌트 행 포함), 관리자는 조직 아래 행만, 설계사는 404. 달마다 기본은 그 달 마지막 날(KST)에
 * 시행 중이던 GLOBAL 룰 버전의 행, {@code ruleVersionId}로 다른 버전. 스냅샷 계산은 작업 {@code COLLECTION_RATE_SNAPSHOT}.
 */
@RestController
@RequestMapping("/api/v1/collection-rates")
public class CollectionRatesController {

    private final CollectionRateService rates;

    public CollectionRatesController(CollectionRateService rates) {
        this.rates = rates;
    }

    @GetMapping
    public CollectionRateList list(Caller caller, @RequestParam(name = "from", required = false) String from,
                                   @RequestParam(name = "to", required = false) String to,
                                   @RequestParam(name = "orgPath", required = false) String orgPath,
                                   @RequestParam(name = "ruleVersionId", required = false) String ruleVersionId) {
        return CollectionRateMapper.list(rates.read(caller, CollectionRateMapper.month(from, "from"), CollectionRateMapper.month(to, "to"),
                CollectionRateMapper.orgPath(orgPath), CollectionRateMapper.ruleVersionId(ruleVersionId)));
    }
}
