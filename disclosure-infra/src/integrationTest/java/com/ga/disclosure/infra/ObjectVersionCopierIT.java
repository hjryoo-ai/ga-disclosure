package com.ga.disclosure.infra;

import com.ga.disclosure.infra.storage.ObjectVersionCopier;
import com.ga.disclosure.infra.testing.SeaweedHarness;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ObjectLockLegalHold;
import software.amazon.awssdk.services.s3.model.ObjectLockLegalHoldStatus;
import software.amazon.awssdk.services.s3.model.ObjectLockRetention;
import software.amazon.awssdk.services.s3.model.ObjectLockRetentionMode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 객체 버전 전수 복제(Phase 8 ⑤): 원본(버전 둘 — 둘째는 COMPLIANCE 보존 + 법적 보류, 삭제 마커가 뒤에 오는 키, 보존 없는 키) → 백업 버킷의 {@code objects/}
 * → 새 버킷으로 되돌림. 세 곳의 모양(키·순서·바이트 해시·보존 모드·기한·보류)이 같고, 복제본은 실제로 잠겨 있다(보존 기한 전 버전 삭제 거부). 비어 있지
 * 않은 대상에는 복제하지 않는다.
 */
class ObjectVersionCopierIT {

    static final SeaweedHarness S3 = SeaweedHarness.get();

    static String put(S3Client s3, String bucket, String key, String body) {
        return s3.putObject(b -> b.bucket(bucket).key(key), RequestBody.fromString(body, StandardCharsets.UTF_8)).versionId();
    }

    @Test
    void everyVersionMarkerRetentionAndHoldSurvivesARoundTripThroughTheBackupBucket() {
        S3Client s3 = S3.client();
        String source = S3.freshBucket();
        String backup = S3.freshBucket();
        String restored = S3.freshBucket();
        Instant until = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

        put(s3, source, "T1/d1/PDF/aaa", "first");
        String second = put(s3, source, "T1/d1/PDF/aaa", "second");
        s3.putObjectRetention(b -> b.bucket(source).key("T1/d1/PDF/aaa").versionId(second)
                .retention(ObjectLockRetention.builder().mode(ObjectLockRetentionMode.COMPLIANCE).retainUntilDate(until).build()));
        s3.putObjectLegalHold(b -> b.bucket(source).key("T1/d1/PDF/aaa").versionId(second)
                .legalHold(ObjectLockLegalHold.builder().status(ObjectLockLegalHoldStatus.ON).build()));
        put(s3, source, "T1/d2/PDF/bbb", "gone");
        s3.deleteObject(b -> b.bucket(source).key("T1/d2/PDF/bbb"));
        put(s3, source, "T2/reports/j1", "report");

        ObjectVersionCopier.Report original = ObjectVersionCopier.describe(s3, source, "");
        assertThat(original.entries()).extracting(ObjectVersionCopier.Entry::key, ObjectVersionCopier.Entry::deleteMarker).containsExactly(
                org.assertj.core.groups.Tuple.tuple("T1/d1/PDF/aaa", false), org.assertj.core.groups.Tuple.tuple("T1/d1/PDF/aaa", false),
                org.assertj.core.groups.Tuple.tuple("T1/d2/PDF/bbb", false), org.assertj.core.groups.Tuple.tuple("T1/d2/PDF/bbb", true),
                org.assertj.core.groups.Tuple.tuple("T2/reports/j1", false));

        ObjectVersionCopier.Report exported = ObjectVersionCopier.copy(s3, source, "", s3, backup, "objects/");
        assertThat(exported).isEqualTo(original);
        assertThat(exported.keys()).isEqualTo(3);
        assertThat(exported.versions()).isEqualTo(4);
        assertThat(exported.deleteMarkers()).isEqualTo(1);
        assertThat(exported.retained()).isEqualTo(1);
        assertThat(exported.held()).isEqualTo(1);
        assertThat(exported.maxRetainUntil()).contains(until);
        assertThat(exported.entries().get(1).retentionMode()).contains("COMPLIANCE");
        assertThat(ObjectVersionCopier.describe(s3, backup, "objects/")).isEqualTo(original);

        ObjectVersionCopier.Report imported = ObjectVersionCopier.copy(s3, backup, "objects/", s3, restored, "");
        assertThat(imported).isEqualTo(original);
        assertThat(ObjectVersionCopier.describe(s3, restored, "")).isEqualTo(original);
        // 복제본은 실제로 잠겨 있다: 보존 기한 전 그 버전을 지울 수 없다
        String lockedVersion = s3.listObjectVersions(b -> b.bucket(restored).prefix("T1/d1/PDF/aaa")).versions().stream()
                .filter(v -> v.isLatest()).findFirst().orElseThrow().versionId();
        assertThatThrownBy(() -> s3.deleteObject(b -> b.bucket(restored).key("T1/d1/PDF/aaa").versionId(lockedVersion)))
                .isInstanceOf(software.amazon.awssdk.services.s3.model.S3Exception.class);
        // 복제본의 최신 바이트 = 원본의 최신 바이트, 마커 뒤 키는 최신이 없다
        assertThat(s3.getObjectAsBytes(b -> b.bucket(restored).key("T1/d1/PDF/aaa")).asUtf8String()).isEqualTo("second");
        assertThat(s3.listObjectsV2(b -> b.bucket(restored)).contents()).extracting(o -> o.key()).containsExactlyInAnyOrder("T1/d1/PDF/aaa", "T2/reports/j1");
        assertThat(imported.entries().get(0).retainUntil()).isEqualTo(Optional.empty());
    }

    @Test
    void aNonEmptyTargetIsRefused() {
        S3Client s3 = S3.client();
        String source = S3.freshBucket();
        String target = S3.freshBucket();
        put(s3, source, "k", "v");
        put(s3, target, "objects/old", "v");
        assertThatThrownBy(() -> ObjectVersionCopier.copy(s3, source, "", s3, target, "objects/")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is not empty");
        // 다른 접두는 비어 있으면 된다
        assertThat(ObjectVersionCopier.copy(s3, source, "", s3, target, "objects2/").versions()).isEqualTo(1);
    }
}
