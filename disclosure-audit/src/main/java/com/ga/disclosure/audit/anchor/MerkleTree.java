package com.ga.disclosure.audit.anchor;

import com.ga.platform.canonical.Sha256;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 일일 앵커 머클 트리(5 계획 §2, 설계서 §6.7 {@code merkle-spec} 블록이 정본 — {@code MerkleSpecTableTest}가 이 상수와 양방향 대조).
 * <ul>
 *   <li>잎 = SHA-256(0x00 ‖ 레코드 바이트), 노드 = SHA-256(0x01 ‖ 왼쪽 ‖ 오른쪽), 패딩 잎 = SHA-256(0x02 ‖ ASCII({@link #PAD_LABEL})).
 *       세 접두가 달라 잎을 노드로·노드를 잎으로 내미는 두 번째 원상 공격이 성립하지 않는다(RFC 6962 방식 + 패딩 도메인).</li>
 *   <li>잎은 해시 바이트의 부호 없는 사전순(= 소문자 hex 문자열 순)으로 왼쪽부터 채우고, 깊이 {@code d}는 고정이며 빈 자리는 패딩 잎이다
 *       — 경로 길이는 언제나 {@code d}이고 테넌트 수와 무관하다. 잎이 {@code 2^d}를 넘으면 거부한다.</li>
 *   <li>경로는 잎 → 루트 순서의 형제 해시 {@code d}개. 레벨 i에서 형제의 방향은 색인의 i번째 비트(0이면 형제가 오른쪽).</li>
 *   <li>한계: 패딩이 상수라서 경로의 형제 중 "전부-패딩 부분트리" 상수의 위치로 점유 슬롯 수의 상한을 추정할 수 있다. 정확한 수와 다른
 *       테넌트의 잎·머리 값은 드러나지 않는다.</li>
 * </ul>
 */
public final class MerkleTree {

    public static final byte LEAF_PREFIX = 0x00;
    public static final byte NODE_PREFIX = 0x01;
    public static final byte PAD_PREFIX = 0x02;
    public static final String PAD_LABEL = "ga-disclosure/anchor/pad/v1";
    public static final int MAX_DEPTH = 24;

    private static final HexFormat HEX = HexFormat.of();
    private static final Pattern HEX64 = Pattern.compile("^[0-9a-f]{64}$");

    private MerkleTree() {
    }

    /** 잎이 {@code 2^depth}를 넘는다(fail-fast, 배치 보고 {@code TREE_FULL}). */
    public static final class TreeFullException extends IllegalArgumentException {
        public TreeFullException(int leaves, int depth) {
            super(leaves + " leaves do not fit a tree of depth " + depth);
        }
    }

    public record Proof(int leafIndex, List<String> siblings) {
        public Proof {
            siblings = List.copyOf(siblings);
        }
    }

    public record Tree(int depth, List<String> leaves, String root, List<List<String>> levels) {

        public Tree {
            leaves = List.copyOf(leaves);
            levels = levels.stream().map(List::copyOf).toList();
        }

        public Proof proof(String leaf) {
            int index = leaves.indexOf(leaf);
            if (index < 0) {
                throw new IllegalArgumentException("leaf is not in the tree");
            }
            List<String> siblings = new ArrayList<>(depth);
            int i = index;
            for (int level = 0; level < depth; level++) {
                List<String> nodes = levels.get(level);
                int sibling = i ^ 1;
                siblings.add(sibling < nodes.size() ? nodes.get(sibling) : padding(level));
                i >>= 1;
            }
            return new Proof(index, siblings);
        }
    }

    public static String leaf(byte[] record) {
        return hash(LEAF_PREFIX, record);
    }

    public static String node(String left, String right) {
        byte[] l = HEX.parseHex(left);
        byte[] r = HEX.parseHex(right);
        byte[] both = new byte[l.length + r.length];
        System.arraycopy(l, 0, both, 0, l.length);
        System.arraycopy(r, 0, both, l.length, r.length);
        return hash(NODE_PREFIX, both);
    }

    /** 레벨 {@code level}의 전부-패딩 부분트리 해시(레벨 0 = 패딩 잎). */
    public static String padding(int level) {
        String h = hash(PAD_PREFIX, PAD_LABEL.getBytes(StandardCharsets.US_ASCII));
        for (int k = 0; k < level; k++) {
            h = node(h, h);
        }
        return h;
    }

    public static Tree build(Collection<String> leafHashes, int depth) {
        if (depth < 1 || depth > MAX_DEPTH) {
            throw new IllegalArgumentException("tree depth must be 1.." + MAX_DEPTH);
        }
        List<String> leaves = new ArrayList<>(Objects.requireNonNull(leafHashes, "leafHashes"));
        if (leaves.isEmpty()) {
            throw new IllegalArgumentException("a tree needs at least one leaf");
        }
        if (leaves.size() > (1 << depth)) {
            throw new TreeFullException(leaves.size(), depth);
        }
        if (!leaves.stream().allMatch(l -> HEX64.matcher(l).matches()) || new HashSet<>(leaves).size() != leaves.size()) {
            throw new IllegalArgumentException("leaves are distinct lowercase SHA-256 hex");
        }
        leaves.sort(null);                                               // 소문자 hex 문자열 순 = 바이트의 부호 없는 사전순
        List<List<String>> levels = new ArrayList<>();
        List<String> current = leaves;
        for (int level = 0; level < depth; level++) {
            levels.add(current);
            List<String> next = new ArrayList<>((current.size() + 1) / 2);
            for (int i = 0; i < current.size(); i += 2) {
                next.add(node(current.get(i), i + 1 < current.size() ? current.get(i + 1) : padding(level)));
            }
            current = next;
        }
        return new Tree(depth, leaves, current.getFirst(), levels);
    }

    /** 잎 + 경로로 루트를 다시 계산한다(영수증 검증 — DB V9 GD111과 같은 식). */
    public static String rootOf(String leaf, int leafIndex, List<String> siblings) {
        if (leafIndex < 0 || siblings.size() > MAX_DEPTH || leafIndex >= (1 << siblings.size())) {
            throw new IllegalArgumentException("leaf index outside a tree of depth " + siblings.size());
        }
        String node = leaf;
        for (int level = 0; level < siblings.size(); level++) {
            String sibling = siblings.get(level);
            node = ((leafIndex >> level) & 1) == 0 ? node(node, sibling) : node(sibling, node);
        }
        return node;
    }

    /**
     * 영수증 검증: 잎은 주어진 해시가 아니라 **레코드에서** 다시 만들고(0x00 접두), 경로 길이는 트리 깊이와 같아야 한다. 둘 중 하나라도
     * 빠지면 내부 노드를 잎이라고 내미는 두 번째 원상 공격(짧은 경로 + 접두 없는 잎)이 열린다.
     */
    public static boolean verify(byte[] record, int leafIndex, List<String> siblings, int depth, String root) {
        if (siblings.size() != depth || depth < 1 || depth > MAX_DEPTH || leafIndex < 0 || leafIndex >= (1 << depth)
                || !siblings.stream().allMatch(h -> HEX64.matcher(h).matches())) {
            return false;
        }
        return rootOf(leaf(record), leafIndex, siblings).equals(root);
    }

    private static String hash(byte prefix, byte[] data) {
        byte[] input = new byte[data.length + 1];
        input[0] = prefix;
        System.arraycopy(data, 0, input, 1, data.length);
        return Sha256.of(input);
    }
}
