package com.ga.disclosure.workflow.contract;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 계약 스키마를 지난 배치(6B 계획 §4). {@code sha256}은 내용 해시 — 스키마를 지난 문서의 JCS 바이트(CSV는 같은 모양으로 바꾼 뒤라 같은 내용이면 형식과 무관하게 같다). 배치 원장의 같은 참조 판정과 작업 매개변수·감사에 번호 대신 싣는다(6B 중간 회신 ①). 항목 순번은 1부터이고 출처 참조
 * {@code batchId#순번}이 연결·보고 행의 재수입 멱등 키다.
 */
public record ContractLinkBatch(String source, String batchId, String sha256, List<Item> items) {

    public ContractLinkBatch {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(batchId, "batchId");
        Objects.requireNonNull(sha256, "sha256");
        items = List.copyOf(items);
    }

    public record Item(int index, String policyNo, Optional<String> applicationNo, LocalDate contractDate, String insurerCode,
                       Optional<String> customerRef, Optional<String> productKey) {

        public Item {
            Objects.requireNonNull(policyNo, "policyNo");
            Objects.requireNonNull(applicationNo, "applicationNo");
            Objects.requireNonNull(contractDate, "contractDate");
            Objects.requireNonNull(insurerCode, "insurerCode");
            Objects.requireNonNull(customerRef, "customerRef");
            Objects.requireNonNull(productKey, "productKey");
        }

        /** 번호를 싣지 않는다(로그·예외에 섞여도 안전하게). */
        @Override
        public String toString() {
            return "Item[" + index + "]";
        }
    }

    public String sourceRef(Item item) {
        return batchId + "#" + item.index();
    }

    @Override
    public String toString() {
        return "ContractLinkBatch[" + source + "/" + batchId + ", " + items.size() + " items]";
    }
}
