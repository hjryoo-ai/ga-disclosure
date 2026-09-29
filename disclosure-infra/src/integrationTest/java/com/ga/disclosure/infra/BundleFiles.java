package com.ga.disclosure.infra;

import com.ga.disclosure.rules.bundle.Bundle;
import com.ga.disclosure.rules.bundle.BundleLoader;
import com.ga.disclosure.rules.testing.Bundles;
import com.ga.platform.canonical.Sha256;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/** 번들 파일(정본·테스트 픽스처)과 "본문을 바꾼 번들"(ID를 규칙대로 재계산) 만들기. */
final class BundleFiles {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private BundleFiles() {
    }

    /** 정본 번들(클래스패스 ga-contracts). */
    static Bundle canonical(String relative) {
        return Bundles.load(relative);
    }

    /** 통합 테스트 픽스처 번들(src/integrationTest/resources/rule-as-data/…). */
    static Bundle fixture(String relative) {
        return BundleLoader.parse(relative, fixtureText(relative));
    }

    static String fixtureText(String relative) {
        try (InputStream in = BundleFiles.class.getResourceAsStream("/rule-as-data/" + relative)) {
            if (in == null) {
                throw new IllegalArgumentException("no fixture " + relative);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 정본 번들의 본문을 {@code edit}로 바꾸고 bundleId를 규칙대로 다시 계산한 번들(같은 룰 ID·다른 해시). */
    static Bundle editedCanonical(String relative, Consumer<ObjectNode> edit) {
        ObjectNode tree = (ObjectNode) JSON.readTree(Bundles.text(relative));
        edit.accept((ObjectNode) tree.get("body"));
        String hash = Sha256.ofCanonical(JSON.writeValueAsString(tree.get("body")));
        String id = tree.get("bundleId").asString();
        tree.put("bundleId", id.substring(0, id.indexOf('@') + 1) + hash.substring(0, 12));
        return BundleLoader.parse(relative + " (edited)", JSON.writeValueAsString(tree));
    }
}
