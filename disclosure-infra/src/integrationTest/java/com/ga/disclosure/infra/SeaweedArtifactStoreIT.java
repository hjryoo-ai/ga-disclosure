package com.ga.disclosure.infra;

import com.ga.disclosure.infra.storage.ArtifactStoreBootstrap;
import com.ga.disclosure.infra.storage.S3ArtifactStore;
import com.ga.disclosure.infra.testing.SeaweedHarness;
import com.ga.disclosure.workflow.artifact.ArtifactStore;
import com.ga.disclosure.workflow.artifact.ArtifactStore.Capabilities;
import com.ga.disclosure.workflow.artifact.ArtifactStore.Support;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRetentionRequest;
import software.amazon.awssdk.services.s3.model.ObjectLockRetentionMode;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

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
        return governanceBypassRejected(store, key);
    }

    @Override
    protected Capabilities expectedCapabilities() {
        return new Capabilities(Support.SUPPORTED);
    }

    @Override
    protected void createDeleteMarker(ArtifactStore store, String key) {
        versionlessDelete(store, key);
    }

    @Override
    protected RecordingStore freshRecordingStore() {
        return recordingSeaweedStore();
    }

    /** 포장(FailingPorts.Store)을 벗겨 S3 어댑터의 버킷을 얻는다. */
    static String bucketOf(ArtifactStore store) {
        return store instanceof FailingPorts.Store failing ? bucketOf(failing.delegate) : ((S3ArtifactStore) store).bucket();
    }

    static void versionlessDelete(ArtifactStore store, String key) {
        try (S3Client raw = S3.client()) {
            raw.deleteObject(b -> b.bucket(bucketOf(store)).key(key));
        }
    }

    /** 어댑터 클라이언트에 실행 인터셉터를 붙여 보낸 요청을 기록한다(승인 B1). */
    static RecordingStore recordingSeaweedStore() {
        List<SentRequest> sent = new CopyOnWriteArrayList<>();
        ExecutionInterceptor capture = new ExecutionInterceptor() {
            @Override
            public void beforeExecution(Context.BeforeExecution context, ExecutionAttributes attributes) {
                sent.add(switch (context.request()) {
                    case DeleteObjectRequest d -> new SentRequest("DeleteObject", d.key(), d.versionId(), d.bypassGovernanceRetention());
                    case DeleteObjectsRequest d -> new SentRequest("DeleteObjects", null, null, d.bypassGovernanceRetention());
                    case PutObjectRetentionRequest r -> new SentRequest("PutObjectRetention", r.key(), r.versionId(), r.bypassGovernanceRetention());
                    default -> new SentRequest(context.request().getClass().getSimpleName(), null, null, null);
                });
            }
        };
        String bucket = S3.freshBucket();
        return new RecordingStore(new S3ArtifactStore(S3ArtifactStore.client(S3.settings(bucket), List.of(capture)), bucket), sent);
    }

    static boolean governanceBypassRejected(ArtifactStore store, String key) {
        String bucket = bucketOf(store);
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
