package com.ga.disclosure.audit.anchor;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 설계서 §6.7의 {@code merkle-spec} 블록(정본)과 코드 상수를 양방향으로 대조한다(G2) — 한쪽만 바뀌면 실패. */
class MerkleSpecTableTest {

    private static final Path DESIGN = Path.of(System.getProperty("ga.repoRoot"), "docs", "설계서.md");
    private static final Pattern BLOCK = Pattern.compile("```merkle-spec\\n(.*?)\\n```", Pattern.DOTALL);

    static Map<String, String> parse(String markdown) {
        Matcher m = BLOCK.matcher(markdown);
        if (!m.find()) {
            throw new IllegalStateException("설계서에 merkle-spec 블록이 없다");
        }
        Map<String, String> out = new LinkedHashMap<>();
        String[] lines = m.group(1).split("\n");
        if (!lines[0].equals("key,value")) {
            throw new IllegalStateException("header must be key,value");
        }
        for (int i = 1; i < lines.length; i++) {
            String[] kv = lines[i].split(",", -1);
            if (kv.length != 2 || out.put(kv[0], kv[1]) != null) {
                throw new IllegalStateException("bad or duplicate row: " + lines[i]);
            }
        }
        return out;
    }

    /** 코드가 말하는 규격(같은 키로). */
    static Map<String, String> code() {
        Map<String, String> c = new LinkedHashMap<>();
        HexFormat hex = HexFormat.of();
        c.put("hash", "SHA-256");
        c.put("leafPrefix", hex.toHexDigits(MerkleTree.LEAF_PREFIX));
        c.put("nodePrefix", hex.toHexDigits(MerkleTree.NODE_PREFIX));
        c.put("padPrefix", hex.toHexDigits(MerkleTree.PAD_PREFIX));
        c.put("padLabel", MerkleTree.PAD_LABEL);
        c.put("leafInput", "JCS(anchorDate|anchorSeq|auditHead|auditSeq|sealChainHead|sealChainSeq|tenantId|v)");
        c.put("recordVersion", Integer.toString(AnchorRecord.VERSION));
        c.put("leafOrder", "leafHashUnsignedAscending");
        c.put("depthKey", "anchoring.treeDepth");
        c.put("defaultDepth", "16");
        c.put("maxDepth", Integer.toString(MerkleTree.MAX_DEPTH));
        c.put("maxLeaves", "2^depth");
        c.put("pathOrder", "leafToRoot");
        c.put("pathSide", "bit(leafIndex;level)=0 -> sibling right");
        c.put("imprint", "root");
        return c;
    }

    @Test
    void designBlockEqualsTheCodeBothWays() throws IOException {
        Map<String, String> doc = parse(Files.readString(DESIGN, StandardCharsets.UTF_8));
        assertThat(doc).containsExactlyEntriesOf(code());
    }

    /** 블록의 leafInput 키 목록이 실제 레코드 JCS의 키 순서와 같다(키를 하나 빼거나 더하면 실패). */
    @Test
    void leafInputKeysAreTheRecordKeys() throws IOException {
        String keys = parse(Files.readString(DESIGN, StandardCharsets.UTF_8)).get("leafInput").replaceAll("^JCS\\(|\\)$", "");
        String json = new String(new AnchorRecord(com.ga.platform.core.tenant.TenantId.of("T1"), 1, java.time.LocalDate.parse("2026-10-03"),
                0, "0".repeat(64), 0, "0".repeat(64)).canonical(), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("\"([a-zA-Z]+)\":").matcher(json);
        StringBuilder found = new StringBuilder();
        while (m.find()) {
            found.append(found.isEmpty() ? "" : "|").append(m.group(1));
        }
        assertThat(found.toString()).isEqualTo(keys);
    }

    @Test
    void parserRejectsMalformedBlocks() {
        assertThatThrownBy(() -> parse("no block")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> parse("```merkle-spec\nkey,value\nhash,SHA-256\nhash,SHA-1\n```")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> parse("```merkle-spec\nk,v\nhash,SHA-256\n```")).isInstanceOf(IllegalStateException.class);
    }
}
