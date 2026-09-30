package com.ga.disclosure.workflow.catalog;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditEntry;
import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * 카탈로그 파일 수입(설계서 §4.2, Phase 2 지시문 §2). 스냅샷 교체가 아니라 <b>행 단위 upsert + 유효기간 닫기</b>다.
 * <ol>
 *   <li>파일 검증({@link CatalogFileParser}) — 실패하면 아무것도 하지 않는다.</li>
 *   <li>같은 (kind, SHA-256)을 이미 수입했으면 NOOP(감사 {@code outcome=NOOP} 1행).</li>
 *   <li>{@code asOf}가 같은 kind의 직전 수입보다 이르면 거부 — 옛 파일 재수입으로 캐시가 되돌아가지 않는다.</li>
 *   <li>파일의 행: 새 키 INSERT, 내용이 바뀐 행 UPDATE(상품의 보험사는 키 정체성이라 변경 거부), 같은 행은 출처만 갱신.</li>
 *   <li>파일에 없고 {@code asOf}에 열려 있는 행은 끝을 {@code asOf}로 닫는다(시작 전이면 빈 구간 [from, from)). 삭제하지 않는다.</li>
 *   <li>수입 이력 1행 + 감사 1행(변경 전후 포함), 전부 한 트랜잭션.</li>
 * </ol>
 */
public final class CatalogImportService {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final CatalogStore store;
    private final AuditPort audit;
    private final WorkflowTransactions transactions;
    private final Clock clock;

    public CatalogImportService(CatalogStore store, AuditPort audit, WorkflowTransactions transactions, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** @param fileName 출처 기록용 파일 이름(경로는 버리고 이름만 남긴다) */
    public CatalogImportOutcome importFile(TenantId tenant, Actor actor, String fileName, byte[] content) {
        String name = Path.of(Objects.requireNonNull(fileName, "fileName")).getFileName().toString();
        CatalogFile file = CatalogFileParser.parse(content);
        return transactions.inTenant(tenant, () -> {
            var existing = store.findImport(file.kind(), file.sha256());
            if (existing.isPresent()) {
                ObjectNode detail = header(file, name).put("outcome", "NOOP").put("originalImportId", existing.get().importId().toString());
                record(actor, existing.get().importId(), detail);
                return new CatalogImportOutcome(file.kind(), CatalogImportOutcome.Result.NOOP, existing.get().importId(), file.sha256(),
                        ImportCounts.NONE);
            }
            store.latestImport(file.kind()).filter(last -> file.asOf().isBefore(last.asOf())).ifPresent(last -> {
                throw new CatalogImportRejectedException(tenant + ": " + file.kind() + " file asOf " + file.asOf()
                        + " is earlier than the last import (" + last.asOf() + ", " + last.fileName() + ")");
            });
            Provenance provenance = new Provenance(file.source(), name + "@sha256:" + file.sha256(), clock.instant());
            ArrayNode changes = JSON.createArrayNode();
            ImportCounts counts = switch (file.kind()) {
                case PRODUCT_GROUPS -> reconcile(file.groups(), store.groups(), g -> g.code().value(), file.asOf(),
                        (g, end) -> new ProductGroup(g.code(), g.name(), g.line(), g.applyFrom(), end), ProductGroup::applyFrom,
                        ProductGroup::applyTo, (g, isNew) -> store.saveGroup(g, provenance, isNew), changes);
                case PRODUCTS -> {
                    checkProducts(tenant, file);
                    yield reconcile(file.products(), store.products(), p -> p.key().value(), file.asOf(),
                            (p, end) -> new CatalogProduct(p.key(), p.group(), p.name(), p.saleFrom(), end, p.defaults()),
                            CatalogProduct::saleFrom, CatalogProduct::saleTo, (p, isNew) -> store.saveProduct(p, provenance, isNew), changes);
                }
                case INSURER_PANEL -> reconcile(file.insurers(), store.panel(), e -> e.insurer().value() + "@" + e.activeFrom(), file.asOf(),
                        (e, end) -> new PanelEntry(e.insurer(), e.insurerName(), e.line(), e.activeFrom(), end), PanelEntry::activeFrom,
                        PanelEntry::activeTo, (e, isNew) -> store.savePanelEntry(e, provenance, isNew), changes);
            };
            UUID importId = UUID.randomUUID();
            store.recordImport(new ImportRecord(importId, file.kind(), name, file.sha256(), file.source(), file.asOf(),
                    provenance.syncedAt(), counts));
            ObjectNode detail = header(file, name).put("outcome", "IMPORTED");
            detail.putObject("counts").put("inserted", counts.inserted()).put("updated", counts.updated())
                    .put("closed", counts.closed()).put("unchanged", counts.unchanged());
            detail.set("changes", changes);
            record(actor, importId, detail);
            return new CatalogImportOutcome(file.kind(), CatalogImportOutcome.Result.IMPORTED, importId, file.sha256(), counts);
        });
    }

    /** 상품의 보험사는 키 정체성이다(DB도 GD071로 거부). 참조하는 상품군은 이미 수입돼 있어야 한다. */
    private void checkProducts(TenantId tenant, CatalogFile file) {
        Set<String> groups = new HashSet<>();
        store.groups().forEach(g -> groups.add(g.code().value()));
        for (CatalogProduct p : file.products()) {
            if (!groups.contains(p.group().value())) {
                throw new CatalogImportRejectedException(tenant + ": product " + p.key() + " refers to unknown group " + p.group()
                        + " — import the PRODUCT_GROUPS file first");
            }
        }
    }

    private interface Closer<R> {
        R withEnd(R row, LocalDate end);
    }

    private interface Saver<R> {
        void save(R row, boolean isNew);
    }

    private <R> ImportCounts reconcile(List<R> incoming, List<R> stored, Function<R, String> key, LocalDate asOf, Closer<R> closer,
                                       Function<R, LocalDate> from, Function<R, LocalDate> to, Saver<R> saver, ArrayNode changes) {
        Map<String, R> current = new HashMap<>();
        stored.forEach(r -> current.put(key.apply(r), r));
        int inserted = 0;
        int updated = 0;
        int closed = 0;
        int unchanged = 0;
        Set<String> seen = new HashSet<>();
        for (R row : incoming) {
            String k = key.apply(row);
            seen.add(k);
            R before = current.get(k);
            if (before == null) {
                saver.save(row, true);
                changes.addObject().put("key", k).put("change", "INSERTED").set("after", JSON.valueToTree(row));
                inserted++;
            } else if (before.equals(row)) {
                saver.save(row, false);
                unchanged++;
            } else {
                saver.save(row, false);
                ObjectNode change = changes.addObject().put("key", k).put("change", "UPDATED");
                change.set("before", JSON.valueToTree(before));
                change.set("after", JSON.valueToTree(row));
                updated++;
            }
        }
        for (R row : stored) {
            String k = key.apply(row);
            LocalDate end = to.apply(row);
            if (seen.contains(k) || (end != null && !end.isAfter(asOf))) {
                continue;
            }
            LocalDate start = from.apply(row);
            R closedRow = closer.withEnd(row, start.isAfter(asOf) ? start : asOf);
            saver.save(closedRow, false);
            ObjectNode change = changes.addObject().put("key", k).put("change", "CLOSED");
            change.put("end", String.valueOf(to.apply(closedRow)));
            closed++;
        }
        return new ImportCounts(inserted, updated, closed, unchanged);
    }

    private static ObjectNode header(CatalogFile file, String fileName) {
        return JSON.createObjectNode().put("kind", file.kind().name()).put("fileName", fileName).put("fileSha256", file.sha256())
                .put("source", file.source()).put("asOf", file.asOf().toString());
    }

    private void record(Actor actor, UUID importId, JsonNode detail) {
        audit.append(new AuditEntry(clock.instant(), actor.subject(), actor.role(), AuditAction.CATALOG_IMPORT, "CATALOG_IMPORT",
                importId.toString(), detail));
    }
}
