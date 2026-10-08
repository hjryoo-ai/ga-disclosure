package com.ga.disclosure.api.rest;

import com.ga.disclosure.api.dto.CreateDisclosureRequest;
import com.ga.disclosure.api.dto.DisclosureReceipt;
import com.ga.disclosure.api.dto.Download;
import com.ga.disclosure.api.dto.ExceptionApprovalReceipt;
import com.ga.disclosure.api.dto.ExceptionApprovalRequest;
import com.ga.disclosure.api.dto.ItemsRequest;
import com.ga.disclosure.api.dto.LifecycleReceipt;
import com.ga.disclosure.api.dto.LifecycleRequest;
import com.ga.disclosure.api.dto.RecommendationsRequest;
import com.ga.disclosure.api.dto.SealReceipt;
import com.ga.disclosure.api.dto.ValidateRequest;
import com.ga.disclosure.api.dto.ValidationReceipt;
import com.ga.disclosure.api.mapper.CommandMapper;
import com.ga.disclosure.api.mapper.DisclosureMapper;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.disclosure.ArtifactService;
import com.ga.disclosure.workflow.disclosure.DisclosureService;
import com.ga.disclosure.workflow.disclosure.LifecycleService;
import com.ga.disclosure.workflow.disclosure.SealService;
import com.ga.disclosure.workflow.verify.ReceiptExporter;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * 확인서 쓰기(6A 계획 §4.1): 초안·항목·비교·산출·추천사유·검증 미리보기·봉인·무효·재기준·예외 승인(정정은 HTTP에 없다 — 6B 승인 §2), 산출물·앵커 영수증 내보내기. 결과는 닫힌 영수증 200
 * (생성 201), 업무 거부는 범주로 409·422. 인가는 유스케이스가 한다.
 */
@RestController
@RequestMapping("/api/v1/disclosures")
public class DisclosureCommandsController {

    private final DisclosureService disclosures;
    private final SealService seals;
    private final LifecycleService lifecycle;
    private final ArtifactService artifacts;
    private final ReceiptExporter receipts;

    public DisclosureCommandsController(DisclosureService disclosures, SealService seals, LifecycleService lifecycle, ArtifactService artifacts,
                                        ReceiptExporter receipts) {
        this.disclosures = disclosures;
        this.seals = seals;
        this.lifecycle = lifecycle;
        this.artifacts = artifacts;
        this.receipts = receipts;
    }

    @PostMapping
    public ResponseEntity<DisclosureReceipt> create(Caller caller, @RequestBody CreateDisclosureRequest request) {
        DisclosureReceipt receipt = CommandMapper.created(disclosures.createDraft(caller, CommandMapper.customerRef(request),
                CommandMapper.groupCode(request), CommandMapper.consultDate(request), CommandMapper.templateType(request)));
        return ResponseEntity.created(URI.create("/api/v1/disclosures/" + receipt.disclosureId())).body(receipt);
    }

    @PostMapping("/{id}/items")
    public DisclosureReceipt items(Caller caller, @PathVariable("id") String id, @RequestBody ItemsRequest request) {
        return CommandMapper.receipt(disclosures.replaceItems(caller, DisclosureMapper.id(id), CommandMapper.items(request)));
    }

    @PostMapping("/{id}/compare")
    public DisclosureReceipt compare(Caller caller, @PathVariable("id") String id) {
        return CommandMapper.receipt(disclosures.compare(caller, DisclosureMapper.id(id)));
    }

    @PostMapping("/{id}/grades")
    public DisclosureReceipt grades(Caller caller, @PathVariable("id") String id) {
        return CommandMapper.receipt(disclosures.requestGrades(caller, DisclosureMapper.id(id)));
    }

    @PostMapping("/{id}/recommendations")
    public DisclosureReceipt recommendations(Caller caller, @PathVariable("id") String id, @RequestBody RecommendationsRequest request) {
        return CommandMapper.receipt(disclosures.setRecommendations(caller, DisclosureMapper.id(id), CommandMapper.reasons(request)));
    }

    @PostMapping("/{id}/validate")
    public ValidationReceipt validate(Caller caller, @PathVariable("id") String id, @RequestBody ValidateRequest request) {
        return CommandMapper.validation(DisclosureMapper.id(id), disclosures.validate(caller, DisclosureMapper.id(id),
                CommandMapper.stage(request == null ? null : request.stage())));
    }

    @PostMapping("/{id}/seal")
    public SealReceipt seal(Caller caller, @PathVariable("id") String id) {
        return CommandMapper.seal(seals.seal(caller, DisclosureMapper.id(id)));
    }

    @PostMapping("/{id}/void")
    public LifecycleReceipt voidDisclosure(Caller caller, @PathVariable("id") String id, @RequestBody LifecycleRequest request) {
        return CommandMapper.lifecycle(lifecycle.voidDisclosure(caller, DisclosureMapper.id(id), CommandMapper.reason(request)));
    }

    @PostMapping("/{id}/rebase")
    public LifecycleReceipt rebase(Caller caller, @PathVariable("id") String id) {
        return CommandMapper.lifecycle(lifecycle.rebase(caller, DisclosureMapper.id(id)));
    }

    @PostMapping("/{id}/exception-approvals")
    public ExceptionApprovalReceipt approve(Caller caller, @PathVariable("id") String id, @RequestBody ExceptionApprovalRequest request) {
        return CommandMapper.approval(disclosures.approveException(caller, DisclosureMapper.id(id),
                CommandMapper.required("ruleId", request == null ? null : request.ruleId()), CommandMapper.required("subjectHash", request.subjectHash()),
                CommandMapper.required("reason", request.reason())));
    }

    @GetMapping("/{id}/artifacts/{kind}")
    public ResponseEntity<byte[]> artifact(Caller caller, @PathVariable("id") String id, @PathVariable("kind") String kind) {
        Download d = CommandMapper.artifact(CommandMapper.artifactKind(kind),
                artifacts.view(caller, DisclosureMapper.id(id), CommandMapper.artifactKind(kind)));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(d.mediaType())).body(d.bytes());
    }

    @GetMapping("/{id}/anchor-receipt")
    public ResponseEntity<byte[]> anchorReceipt(Caller caller, @PathVariable("id") String id) {
        Download d = CommandMapper.anchorReceipt(receipts.export(caller, DisclosureMapper.id(id)));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(d.mediaType())).body(d.bytes());
    }
}
