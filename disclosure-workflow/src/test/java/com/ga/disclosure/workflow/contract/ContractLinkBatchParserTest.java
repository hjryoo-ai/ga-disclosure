package com.ga.disclosure.workflow.contract;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** 6B 계획 §4: 계약 스키마를 지난 배치만 받는다. 문제에는 위치·규칙 이름만 — 번호는 예외·문장 어디에도 없다. CSV는 같은 검증을 지난다. */
class ContractLinkBatchParserTest {

    static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void theContractSampleParses() throws Exception {
        byte[] sample = Files.readAllBytes(Path.of(System.getProperty("ga.repoRoot"), "contracts/contract-link/v1/samples/contract-link-batch.json"));
        ContractLinkBatch b = ContractLinkBatchParser.parse(sample);
        assertThat(b.source()).isEqualTo("INS_FEED_A");
        assertThat(b.items()).hasSize(2);
        assertThat(b.items().getFirst().applicationNo()).contains("APP-0000000001");
        assertThat(b.items().get(1).applicationNo()).isEmpty();
        assertThat(b.sourceRef(b.items().get(1))).isEqualTo("2026-10-09.001#2");
        assertThat(b.toString() + b.items()).doesNotContain("POL-").doesNotContain("APP-");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"schemaVersion\":1,\"source\":\"S\",\"batchId\":\"b\",\"items\":[{\"policyNo\":\"POL-SECRET-1\",\"contractDate\":\"2026-09-30\",\"insurerCode\":\"INS_A\"}]}",
            "{\"schemaVersion\":1,\"source\":\"S\",\"batchId\":\"b\",\"items\":[{\"policyNo\":\"POL-SECRET-1\",\"contractDate\":\"2026-09-30\",\"insurerCode\":\"INS-A\",\"customerName\":\"POL-SECRET-1\"}]}",
            "{\"schemaVersion\":1,\"source\":\"S\",\"batchId\":\"b\",\"items\":[{\"policyNo\":\"POL-SECRET-1\",\"contractDate\":\"2026-02-30\",\"insurerCode\":\"INS-A\"}]}",
            "{\"schemaVersion\":1,\"source\":\"S\",\"batchId\":\"b\",\"items\":[{\"policyNo\":\"POL-SECRET-1\",\"contractDate\":\"2026-09-30\",\"insurerCode\":\"INS-A\",\"insurerCode\":\"INS-B\"}]}",
            "not json POL-SECRET-1"})
    void violationsNameOnlyPlacesNeverValues(String json) {
        InvalidContractLinkBatchException e = catchThrowableOfType(InvalidContractLinkBatchException.class, () -> ContractLinkBatchParser.parse(bytes(json)));
        assertThat(e).isNotNull();
        assertThat(e.problems()).isNotEmpty();
        assertThat(e.getMessage() + e.problems()).doesNotContain("POL-SECRET-1");
    }

    @Test
    void csvBecomesTheSameContract() {
        String csv = ContractLinkCsv.HEADER + "\nPOL-1,APP-1,2026-09-30,INS-A,,INS-A:PRD-1001\nPOL-2,,2026-10-01,INS-B,C01,\n";
        ContractLinkBatch b = ContractLinkCsv.parse("INS_FEED_A", "csv-1", bytes(csv));
        assertThat(b.items()).hasSize(2);
        ContractLinkBatch.Item first = b.items().getFirst();
        assertThat(first.applicationNo()).contains("APP-1");
        assertThat(first.productKey()).contains("INS-A:PRD-1001");
        assertThat(first.customerRef()).isEmpty();
        assertThat(b.items().get(1).contractDate()).isEqualTo(LocalDate.parse("2026-10-01"));
        assertThat(b.items().get(1).customerRef()).isEqualTo(Optional.of("C01"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"policyNo,contractDate\nPOL-SECRET-1,2026-09-30\n", ContractLinkCsv.HEADER + "\n\"POL-SECRET-1\",,2026-09-30,INS-A,,\n",
            ContractLinkCsv.HEADER + "\nPOL-SECRET-1,2026-09-30,INS-A\n", ContractLinkCsv.HEADER + "\nPOL SECRET 1,,2026-09-30,INS-A,,\n"})
    void badCsvIsRejectedWithoutValues(String csv) {
        assertThatThrownBy(() -> ContractLinkCsv.parse("INS_FEED_A", "csv-1", bytes(csv))).isInstanceOf(InvalidContractLinkBatchException.class)
                .satisfies(e -> assertThat(e.getMessage() + ((InvalidContractLinkBatchException) e).problems()).doesNotContain("SECRET"));
    }
}
