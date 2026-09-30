package com.ga.disclosure.workflow.customer;

import com.ga.disclosure.domain.pii.BirthDate;
import com.ga.disclosure.domain.pii.CustomerName;
import com.ga.disclosure.domain.pii.PhoneNumber;
import com.ga.platform.canonical.Canonicalizer;
import tools.jackson.databind.JsonNode;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 고객 등록 파일(데모 {@code customers.json}, CLAUDE.md 규칙 6: 개인정보는 CLI 인자·환경변수·셸 인라인이 아니라 파일 또는 API로만).
 * 형식: {@code {"schemaVersion":1,"source":"demo","customers":[{"id":"C01","name":…,"phone":…?,"birthDate":…?}]}}. 값은 읽는 즉시
 * {@code Sensitive}로 감싼다. 오류 메시지에는 행 위치·항목 이름만 싣고 <b>값을 싣지 않는다</b>. 등록 멱등 키는
 * {@code <source>:<파일 이름>#<id>}(데모 생성 규칙).
 */
public final class CustomerFileParser {

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,31}");
    private static final Pattern SOURCE = Pattern.compile("[a-z][a-z0-9-]{0,15}");

    private CustomerFileParser() {
    }

    public record Row(String id, RegistrationKey key, NewCustomer customer) {
    }

    public static List<Row> parse(String fileName, byte[] content) {
        JsonNode root;
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(content)).toString();
            root = Canonicalizer.parseStrict(text);
        } catch (CharacterCodingException | RuntimeException e) {
            throw new IllegalArgumentException("customer file is not strict UTF-8 JSON");
        }
        if (root.path("schemaVersion").asInt(-1) != 1 || !root.path("customers").isArray()
                || !SOURCE.matcher(root.path("source").asString("")).matches()) {
            throw new IllegalArgumentException("customer file needs schemaVersion 1, source and customers[]");
        }
        String source = root.get("source").asString();
        String base = fileName.replaceAll(".*[/\\\\]", "");
        List<Row> rows = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        List<String> problems = new ArrayList<>();
        JsonNode customers = root.get("customers");
        for (int i = 0; i < customers.size(); i++) {
            JsonNode c = customers.get(i);
            String at = "customers[" + i + "]";
            String id = c.path("id").asString("");
            if (!ID.matcher(id).matches() || !ids.add(id)) {
                problems.add(at + ".id missing, malformed or duplicated");
                continue;
            }
            for (String field : c.propertyNames()) {
                if (!Set.of("id", "name", "phone", "birthDate").contains(field)) {
                    problems.add(at + " has unknown field " + field);
                }
            }
            try {
                NewCustomer customer = new NewCustomer(CustomerName.of(c.path("name").asString(null)),
                        c.hasNonNull("phone") ? PhoneNumber.of(c.get("phone").asString()) : null,
                        c.hasNonNull("birthDate") ? BirthDate.parse(c.get("birthDate").asString()) : null);
                rows.add(new Row(id, new RegistrationKey(source + ":" + base + "#" + id), customer));
            } catch (RuntimeException e) {
                problems.add(at + " (" + id + ") has an invalid name, phone or birth date");   // 값은 싣지 않는다
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("customer file rejected: " + problems);
        }
        return List.copyOf(rows);
    }
}
