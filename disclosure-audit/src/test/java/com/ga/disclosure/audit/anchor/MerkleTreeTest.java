package com.ga.disclosure.audit.anchor;

import com.ga.platform.canonical.Sha256;
import com.ga.platform.core.testing.SeededCases;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** G2 머클(5 계획 §2): 경로 길이 = 깊이(테넌트 수 무관), 임의 잎 재계산 = 루트, 패딩 상수, 도메인 분리, 초과 거부. */
class MerkleTreeTest {

    static List<String> leaves(int n, long seed) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(MerkleTree.leaf(("tenant-" + seed + "-" + i).getBytes(StandardCharsets.UTF_8)));
        }
        return out;
    }

    @ParameterizedTest(name = "{0} tenants")
    @ValueSource(ints = {1, 2, 17})
    void everyPathHasTheFixedDepthAndReachesTheRoot(int tenants) {
        MerkleTree.Tree tree = MerkleTree.build(leaves(tenants, 7), 16);
        for (String leaf : tree.leaves()) {
            MerkleTree.Proof proof = tree.proof(leaf);
            assertThat(proof.siblings()).hasSize(16);
            assertThat(MerkleTree.rootOf(leaf, proof.leafIndex(), proof.siblings())).isEqualTo(tree.root());
        }
    }

    static Stream<Arguments> seededTrees() {
        return SeededCases.of(20261003L, 60, r -> new Object[]{1 + r.nextInt(300), 9 + r.nextInt(4), r.nextLong()});
    }

    @ParameterizedTest(name = "{0} leaves, depth {1}")
    @MethodSource("seededTrees")
    void anyLeafAndItsPathRecomputeTheRoot(int count, int depth, long seed) {
        MerkleTree.Tree tree = MerkleTree.build(leaves(count, seed), depth);
        String leaf = tree.leaves().get(Math.floorMod(seed, count));
        MerkleTree.Proof proof = tree.proof(leaf);
        assertThat(MerkleTree.rootOf(leaf, proof.leafIndex(), proof.siblings())).isEqualTo(tree.root());
        // 다른 색인(방향 비트)·다른 잎으로는 같은 루트가 나오지 않는다
        assertThat(MerkleTree.rootOf(leaf, proof.leafIndex() ^ 1, proof.siblings())).isNotEqualTo(tree.root());
    }

    @Test
    void leavesAreSortedUnsignedAndTheInputOrderDoesNotMatter() {
        List<String> l = leaves(17, 3);
        List<String> reversed = new ArrayList<>(l);
        java.util.Collections.reverse(reversed);
        MerkleTree.Tree a = MerkleTree.build(l, 8);
        assertThat(MerkleTree.build(reversed, 8).root()).isEqualTo(a.root());
        assertThat(a.leaves()).isSorted();
    }

    @Test
    void paddingIsAConstantOfItsOwnDomain() {
        String padLeaf = Sha256.of(concat(new byte[]{2}, "ga-disclosure/anchor/pad/v1".getBytes(StandardCharsets.US_ASCII)));
        assertThat(MerkleTree.padding(0)).isEqualTo(padLeaf);
        assertThat(MerkleTree.padding(1)).isEqualTo(MerkleTree.node(padLeaf, padLeaf));
        // 고정값(규격이 바뀌면 이 테스트와 설계서 블록을 함께 고친다)
        assertThat(MerkleTree.padding(0)).isEqualTo(padLeaf).hasSize(64);
        // 잎 하나짜리 트리의 경로는 레벨마다 전부-패딩 부분트리 상수
        MerkleTree.Tree one = MerkleTree.build(leaves(1, 1), 5);
        assertThat(one.proof(one.leaves().getFirst()).siblings())
                .containsExactly(MerkleTree.padding(0), MerkleTree.padding(1), MerkleTree.padding(2), MerkleTree.padding(3), MerkleTree.padding(4));
    }

    @Test
    void domainsAreSeparated() {
        byte[] record = "record".getBytes(StandardCharsets.UTF_8);
        assertThat(MerkleTree.leaf(record)).isEqualTo(Sha256.of(concat(new byte[]{0}, record))).isNotEqualTo(Sha256.of(record));
        MerkleTree.Tree tree = MerkleTree.build(leaves(4, 11), 2);
        String l0 = tree.leaves().get(0);
        String l1 = tree.leaves().get(1);
        java.util.HexFormat hex = java.util.HexFormat.of();
        byte[] children = concat(hex.parseHex(l0), hex.parseHex(l1));
        assertThat(MerkleTree.node(l0, l1)).isEqualTo(Sha256.of(concat(new byte[]{1}, children))).isNotEqualTo(Sha256.of(children));
        // 두 번째 원상 시도: 내부 노드 N = node(l0, l1)의 두 자식을 이어 붙인 64바이트를 "레코드"라고 내민다. 접두가 없다면 그 잎이 N과 같아
        // N 위의 짧은 경로로 루트에 닿는다. 접두가 있으므로 잎 ≠ N이고, 경로 길이도 깊이와 달라 검증은 실패한다.
        String n = tree.levels().get(1).getFirst();
        assertThat(n).isEqualTo(MerkleTree.node(l0, l1));
        assertThat(MerkleTree.leaf(children)).isNotEqualTo(n);
        List<String> above = tree.proof(l0).siblings().subList(1, 2);
        assertThat(MerkleTree.rootOf(n, 0, above)).as("the node itself does reach the root through the short path").isEqualTo(tree.root());
        assertThat(MerkleTree.verify(children, 0, above, 2, tree.root())).as("short path").isFalse();
        assertThat(MerkleTree.verify(children, 0, List.of(n, above.getFirst()), 2, tree.root())).as("full-length path").isFalse();
        // 정상 잎은 통과
        MerkleTree.Tree real = MerkleTree.build(List.of(MerkleTree.leaf(record), MerkleTree.leaf("other".getBytes(StandardCharsets.UTF_8))), 2);
        MerkleTree.Proof p = real.proof(MerkleTree.leaf(record));
        assertThat(MerkleTree.verify(record, p.leafIndex(), p.siblings(), 2, real.root())).isTrue();
        assertThat(MerkleTree.verify(record, p.leafIndex(), p.siblings(), 3, real.root())).as("declared depth must match").isFalse();
    }

    @Test
    void moreLeavesThanSlotsAreRefused() {
        assertThat(MerkleTree.build(leaves(8, 5), 3).leaves()).hasSize(8);
        assertThatThrownBy(() -> MerkleTree.build(leaves(9, 5), 3)).isInstanceOf(MerkleTree.TreeFullException.class);
        assertThatThrownBy(() -> MerkleTree.build(List.of(), 3)).isInstanceOf(IllegalArgumentException.class);
        List<String> dup = new ArrayList<>(leaves(2, 5));
        dup.add(dup.getFirst());
        assertThatThrownBy(() -> MerkleTree.build(dup, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MerkleTree.build(leaves(1, 5), 25)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MerkleTree.rootOf(leaves(1, 5).getFirst(), 8, List.of("a".repeat(64), "b".repeat(64), "c".repeat(64))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theSameTenantSetGivesDistinctLeavesAndAStableRoot() {
        Set<String> seen = new HashSet<>();
        IntStream.range(0, 3).forEach(i -> seen.add(MerkleTree.build(leaves(17, 99), 16).root()));
        assertThat(seen).hasSize(1);
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
