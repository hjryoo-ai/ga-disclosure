package com.ga.disclosure.workflow.catalog;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** 상품 키 규칙(계약 1.2.0, 40자·패턴): 맞지 않는 키는 자르지 않고 거부하며 행 위치와 함께 보고한다(Phase 2 심사 §3-1). */
class CatalogFileParserTest {

    private static final String CODE31 = "P" + "1".repeat(30);

    private static String productsFile(String... keys) {
        StringBuilder rows = new StringBuilder();
        for (String key : keys) {
            String insurer = key.substring(0, key.indexOf(':'));
            if (!rows.isEmpty()) {
                rows.append(',');
            }
            rows.append("{\"productKey\":\"").append(key).append("\",\"insurerCode\":\"").append(insurer)
                    .append("\",\"groupCode\":\"PG-HEALTH\",\"productName\":\"x\",\"saleFrom\":\"2026-01-01\",\"saleTo\":null,\"defaults\":{}}");
        }
        return "{\"schemaVersion\":1,\"kind\":\"PRODUCTS\",\"source\":\"T\",\"asOf\":\"2026-09-01\",\"products\":[" + rows + "]}";
    }

    private static CatalogFile parse(String json) {
        return CatalogFileParser.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void fortyCharacterKeyIsImportedUnchanged() {
        String key = "ABCDEFGH:" + CODE31;
        assertThat(key).hasSize(40);
        assertThat(parse(productsFile("INS-A:PRD-1", key)).products()).extracting(p -> p.key().value())
                .containsExactly("INS-A:PRD-1", key);
    }

    @Test
    void overlongKeyIsRejectedWithItsRowNotTruncated() {
        String key41 = "ABCDEFGH:" + CODE31 + "2";
        InvalidCatalogFileException e = catchThrowableOfType(InvalidCatalogFileException.class,
                () -> parse(productsFile("INS-A:PRD-1", "INS-B:PRD-2", key41)));
        assertThat(e.problems()).isNotEmpty().allSatisfy(p -> assertThat(p).contains("/products/2/productKey"));
    }

    @Test
    void keyOutsideThePatternIsRejectedWithItsRow() {
        InvalidCatalogFileException e = catchThrowableOfType(InvalidCatalogFileException.class,
                () -> parse(productsFile("INS-A:PRD-1", "INS_B:PRD-2")));
        assertThat(e.problems()).isNotEmpty().anySatisfy(p -> assertThat(p).contains("/products/1/productKey"));
        assertThat(e.problems()).noneSatisfy(p -> assertThat(p).contains("/products/0/"));
    }
}
