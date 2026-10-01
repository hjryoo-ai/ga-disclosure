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
