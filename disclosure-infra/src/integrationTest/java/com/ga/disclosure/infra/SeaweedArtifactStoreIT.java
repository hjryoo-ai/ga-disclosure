package com.ga.disclosure.infra;

import com.ga.disclosure.infra.storage.ArtifactStoreBootstrap;
import com.ga.disclosure.infra.storage.S3ArtifactStore;
import com.ga.disclosure.infra.testing.SeaweedHarness;
import com.ga.disclosure.workflow.artifact.ArtifactStore;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ObjectLockRetentionMode;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 3B S9: SeaweedFS(digest 고정)에 대한 객체 저장소 계약({@link ArtifactStoreContract}) + 이 구성만의 확인 — S3 인증이 켜져 있어 서명 없는 요청은
 * 거부되고, 기동 확인이 기본 보존 규칙이 있는 버킷을 잡는다.
 */
class SeaweedArtifactStoreIT extends ArtifactStoreContract {

    private static final SeaweedHarness S3 = SeaweedHarness.get();

    @Override
    protected ArtifactStore freshStore() {
        return S3.freshStore();
    }

    @Override
    protected boolean deleteWithGovernanceBypassRejected(ArtifactStore store, String key) {
        String bucket = ((S3ArtifactStore) store).bucket();
        try (S3Client raw = S3.client()) {
            ObjectVersion latest = raw.listObjectVersions(b -> b.bucket(bucket).prefix(key)).versions().stream()
                    .filter(ObjectVersion::isLatest).findFirst().orElseThrow();
            raw.deleteObject(b -> b.bucket(bucket).key(key).versionId(latest.versionId()).bypassGovernanceRetention(true));
            return false;
        } catch (S3Exception e) {
            return e.statusCode() == 403 || e.statusCode() == 400;
        }
    }

    /** 이미지 digest는 하네스·버전 카탈로그·로컬 compose에서 같다(한 곳만 바뀌면 실패). */
    @Test
    void imageDigestIsPinnedTheSameEverywhere() throws Exception {
        java.nio.file.Path root = java.nio.file.Path.of(System.getProperty("ga.repoRoot"));
        assertThat(SeaweedHarness.IMAGE).matches("chrislusf/seaweedfs@sha256:[0-9a-f]{64}");
        assertThat(java.nio.file.Files.readString(root.resolve("gradle/libs.versions.toml"))).contains("\"" + SeaweedHarness.IMAGE + "\"");
        assertThat(java.nio.file.Files.readString(root.resolve("docker-compose.yml"))).contains("image: " + SeaweedHarness.IMAGE);
    }

    /** 볼륨 한도는 하네스와 compose가 같고 디스크 크기에서 자동 산정되지 않는다(CI 러너에서 버킷이 늘면 쓰기 500이 났던 원인). */
    @Test
    void volumeLimitsArePinnedTheSameInHarnessAndCompose() throws Exception {
        java.nio.file.Path root = java.nio.file.Path.of(System.getProperty("ga.repoRoot"));
        String compose = java.nio.file.Files.readString(root.resolve("docker-compose.yml"));
        assertThat(SeaweedHarness.VOLUME_LIMITS).anyMatch(f -> f.matches("-volume\\.max=[1-9][0-9]*"))
                .anyMatch(f -> f.startsWith("-master.volumeSizeLimitMB="));
        for (String flag : SeaweedHarness.VOLUME_LIMITS) {
            assertThat(compose).contains("\"" + flag + "\"");
        }
    }

    /** 버킷을 여럿 만들어도 각 버킷의 첫 쓰기가 성공한다(버킷마다 볼륨을 새로 잡는다). */
    @Test
    void manyFreshBucketsStayWritable() {
        for (int i = 0; i < 20; i++) {
            S3ArtifactStore store = S3.freshStore();
            store.put("T1/many/" + i, new byte[] {1, 2, 3});
            assertThat(store.get("T1/many/" + i)).containsExactly(1, 2, 3);
        }
    }

    @Test
    void unsignedRequestsAreRejected() throws Exception {
        String bucket = S3.freshBucket();
        HttpResponse<String> list = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(S3.endpoint() + "/" + bucket + "?list-type=2")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(list.statusCode()).isEqualTo(403);
        HttpResponse<String> put = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(S3.endpoint() + "/" + bucket + "/T1/x")).PUT(HttpRequest.BodyPublishers.ofString("x")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(put.statusCode()).isEqualTo(403);
    }

    @Test
    void bootstrapRejectsABucketWithADefaultRetentionRule() {
        String bucket = S3.freshBucket();
        try (S3Client raw = S3.client()) {
            raw.putObjectLockConfiguration(b -> b.bucket(bucket).objectLockConfiguration(c -> c.objectLockEnabled("Enabled")
                    .rule(r -> r.defaultRetention(d -> d.mode(ObjectLockRetentionMode.COMPLIANCE).days(1)))));
            assertThat(new ArtifactStoreBootstrap(raw, bucket).problems()).anyMatch(p -> p.contains("default retention"));
        }
    }
}
