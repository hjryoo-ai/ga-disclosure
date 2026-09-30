package com.ga.disclosure.workflow.customer;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 고객 파일: 값은 Sensitive로만 들고, 오류 메시지에 값이 나오지 않으며, 멱등 키는 출처·파일·행 ID에서 만든다. */
class CustomerFileParserTest {

    private static List<CustomerFileParser.Row> parse(String json) {
        return CustomerFileParser.parse("some/dir/customers.json", json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void rowsBecomeSensitiveCustomersWithRegistrationKeys() {
        List<CustomerFileParser.Row> rows = parse("""
                {"schemaVersion":1,"source":"demo","customers":[
                  {"id":"C01","name":"가상고객01","phone":"010-0000-0001","birthDate":"1900-01-01"},
                  {"id":"C02","name":"가상고객02"}]}""");
        assertThat(rows).extracting(r -> r.key().value()).containsExactly("demo:customers.json#C01", "demo:customers.json#C02");
        assertThat(rows.getFirst().customer().toString()).doesNotContain("가상고객01", "0001", "1900");
        assertThat(rows.get(1).customer().phone()).isEmpty();
    }

    @Test
    void invalidValuesAreReportedByPositionWithoutTheValue() {
        assertThatThrownBy(() -> parse("""
                {"schemaVersion":1,"source":"demo","customers":[{"id":"C01","name":"가상고객01","phone":"02-9999-12345"}]}"""))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("customers[0] (C01)")
                .hasMessageNotContaining("02-9999").hasMessageNotContaining("가상고객01");
        assertThatThrownBy(() -> parse("""
                {"schemaVersion":1,"source":"demo","customers":[{"id":"C01","name":"가"},{"id":"C01","name":"나"}]}"""))
                .hasMessageContaining("duplicated");
        assertThatThrownBy(() -> parse("""
                {"schemaVersion":1,"source":"demo","customers":[{"id":"C01","name":"가","address":"x"}]}"""))
                .hasMessageContaining("unknown field address");
        assertThatThrownBy(() -> parse("{\"schemaVersion\":2}")).hasMessageContaining("schemaVersion 1");
    }
}
