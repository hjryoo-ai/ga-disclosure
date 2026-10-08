package com.ga.disclosure.workflow.contract;

import com.ga.platform.canonical.Canonicalizer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * CSV 파일 어댑터(6B 계획 §4): 머리줄 {@value #HEADER}의 행을 계약 배치 JSON으로 바꾼다 — 그 뒤는 JSON과 같은 파서·검증을 지난다. 따옴표·이스케이프는
 * 없다(모든 값이 계약 pattern상 쉼표·공백을 담지 않는다). 빈 칸 = 선택 필드 없음. 문제에는 행 번호만 싣는다.
 */
public final class ContractLinkCsv {

    public static final String HEADER = "policyNo,applicationNo,contractDate,insurerCode,customerRef,productKey";
    private static final List<String> COLUMNS = List.of(HEADER.split(","));
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ContractLinkCsv() {
    }

    /** CSV → 계약 배치(출처·배치 ID는 파일 밖에서 — CLI 인자, 개인정보 아님). */
    public static ContractLinkBatch parse(String source, String batchId, byte[] csv) {
        return ContractLinkBatchParser.parse(toJson(source, batchId, csv));
    }

    static byte[] toJson(String source, String batchId, byte[] csv) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(csv)).toString();
        } catch (CharacterCodingException e) {
            throw new InvalidContractLinkBatchException(List.of("not valid UTF-8"));
        }
        List<String> lines = text.lines().toList();
        if (lines.isEmpty() || !lines.getFirst().equals(HEADER)) {
            throw new InvalidContractLinkBatchException(List.of("line 1: header must be " + HEADER));
        }
        ObjectNode batch = JSON.createObjectNode().put("schemaVersion", 1).put("source", source).put("batchId", batchId);
        ArrayNode items = batch.putArray("items");
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isEmpty()) {
                continue;
            }
            String[] cells = line.split(",", -1);
            if (cells.length != COLUMNS.size() || line.indexOf('"') >= 0) {
                throw new InvalidContractLinkBatchException(List.of("line " + (i + 1) + ": expected " + COLUMNS.size() + " unquoted cells"));
            }
            ObjectNode item = items.addObject();
            for (int c = 0; c < cells.length; c++) {
                if (!cells[c].isEmpty()) {
                    item.put(COLUMNS.get(c), cells[c]);
                }
            }
        }
        return Canonicalizer.canonicalize(batch);
    }
}
