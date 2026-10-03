package com.ga.disclosure.infra.storage;

import com.ga.disclosure.workflow.artifact.ArtifactMissingException;
import com.ga.disclosure.workflow.artifact.ArtifactStore;
import com.ga.disclosure.workflow.artifact.ObjectLockedException;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry;
import software.amazon.awssdk.services.s3.model.GetObjectRetentionResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectLockLegalHold;
import software.amazon.awssdk.services.s3.model.ObjectLockLegalHoldStatus;
import software.amazon.awssdk.services.s3.model.ObjectLockRetention;
import software.amazon.awssdk.services.s3.model.ObjectLockRetentionMode;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link ArtifactStore}의 S3 어댑터(AWS SDK v2 표준 API만). 잠금은 {@code PutObjectRetention}(COMPLIANCE)으로 최신 버전에 걸고, 삭제는 키의
 * 모든 버전과 삭제 마커를 버전 ID로 지운다(버전 없는 삭제는 마커만 만들므로 보내지 않는다 — 잠기거나 보류된 버전은 저장소가 거부한다). legal hold는
 * 모든 버전에 건다. 거버넌스 우회 헤더는 보내지 않는다. 거부(403·Object Lock)는 {@link ObjectLockedException}으로 옮긴다.
 */
public final class S3ArtifactStore implements ArtifactStore, AutoCloseable {

    private final S3Client s3;
    private final String bucket;

    public S3ArtifactStore(S3Client s3, String bucket) {
        this.s3 = Objects.requireNonNull(s3, "s3");
        this.bucket = Objects.requireNonNull(bucket, "bucket");
    }

    /** 설정으로 클라이언트를 만든다: URLConnection HTTP 클라이언트, 경로 형식, 체크섬은 필요할 때만(호환 저장소 공통 분모). */
    public static S3Client client(S3StorageSettings settings) {
        return client(settings, List.of());
    }

    /** 위와 같되 SDK 실행 인터셉터를 붙인다(계약 테스트가 보낸 요청을 캡처한다 — 승인 B1). */
    public static S3Client client(S3StorageSettings settings, List<ExecutionInterceptor> interceptors) {
        return S3Client.builder()
                .overrideConfiguration(c -> interceptors.forEach(c::addExecutionInterceptor))
                .endpointOverride(settings.endpoint())
                .region(Region.of(settings.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(settings.accessKeyId(), settings.secretAccessKey())))
                .forcePathStyle(settings.pathStyle())
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(5)).socketTimeout(Duration.ofSeconds(30)))
                .build();
    }

    public static S3ArtifactStore of(S3StorageSettings settings) {
        return new S3ArtifactStore(client(settings), settings.bucket());
    }

    @Override
    public void put(String key, byte[] bytes) {
        s3.putObject(b -> b.bucket(bucket).key(key).contentType("application/octet-stream"), RequestBody.fromBytes(bytes));
    }

    @Override
    public byte[] get(String key) {
        try {
            return s3.getObjectAsBytes(b -> b.bucket(bucket).key(key)).asByteArray();
        } catch (NoSuchKeyException e) {
            throw new ArtifactMissingException(key);
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            s3.headObject(b -> b.bucket(bucket).key(key));
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public void applyRetention(String key, Instant until) {
        String version = latestVersion(key);
        try {
            s3.putObjectRetention(b -> b.bucket(bucket).key(key).versionId(version)
                    .retention(ObjectLockRetention.builder().mode(ObjectLockRetentionMode.COMPLIANCE).retainUntilDate(until).build()));
        } catch (S3Exception e) {
            throw locked(key, e);
        }
    }

    @Override
    public Optional<Instant> retention(String key) {
        String version = latestVersion(key);
        try {
            GetObjectRetentionResponse r = s3.getObjectRetention(b -> b.bucket(bucket).key(key).versionId(version));
            return Optional.ofNullable(r.retention()).map(ObjectLockRetention::retainUntilDate);
        } catch (S3Exception e) {
            if (e.statusCode() == 404 || e.statusCode() == 400) {
                return Optional.empty();                    // 보존 설정이 없는 버전
            }
            throw e;
        }
    }

    @Override
    public List<StoredObject> list(String prefix) {
        List<StoredObject> out = new ArrayList<>();
        for (S3Object o : s3.listObjectsV2Paginator(b -> b.bucket(bucket).prefix(prefix)).contents()) {
            out.add(new StoredObject(o.key(), o.lastModified(), o.size()));
        }
        return out;
    }

    @Override
    public void delete(String key) {
        Listing listing = listing(key);
        for (String versionId : listing.allIds()) {
            try {
                s3.deleteObject(b -> b.bucket(bucket).key(key).versionId(versionId));
            } catch (S3Exception e) {
                throw locked(key, e);
            }
        }
    }

    @Override
    public VersionCount versionCount(String key) {
        Listing listing = listing(key);
        return new VersionCount(listing.versions().size(), listing.markers().size());
    }

    /** Object Lock 버킷(기동 확인 {@link ArtifactStoreBootstrap})이면 legal hold는 표준 API의 일부다 — SeaweedFS 4.48 실측(5 계획 §6). */
    @Override
    public Capabilities capabilities() {
        return new Capabilities(Support.SUPPORTED);
    }

    @Override
    public void setLegalHold(String key, boolean on) {
        List<ObjectVersion> versions = listing(key).versions();
        if (versions.isEmpty()) {
            throw new ArtifactMissingException(key);
        }
        ObjectLockLegalHold hold = ObjectLockLegalHold.builder().status(on ? ObjectLockLegalHoldStatus.ON : ObjectLockLegalHoldStatus.OFF).build();
        for (ObjectVersion v : versions) {
            s3.putObjectLegalHold(b -> b.bucket(bucket).key(key).versionId(v.versionId()).legalHold(hold));
        }
    }

    @Override
    public boolean legalHold(String key) {
        List<ObjectVersion> versions = listing(key).versions();
        if (versions.isEmpty()) {
            throw new ArtifactMissingException(key);
        }
        for (ObjectVersion v : versions) {
            if (!holdOn(key, v.versionId())) {
                return false;
            }
        }
        return true;
    }

    private boolean holdOn(String key, String versionId) {
        try {
            ObjectLockLegalHold hold = s3.getObjectLegalHold(b -> b.bucket(bucket).key(key).versionId(versionId)).legalHold();
            return hold != null && hold.status() == ObjectLockLegalHoldStatus.ON;
        } catch (S3Exception e) {
            if (e.statusCode() == 404 || e.statusCode() == 400) {
                return false;                               // legal hold를 한 번도 설정하지 않은 버전
            }
            throw e;
        }
    }

    /** 키(정확히 일치)의 버전과 삭제 마커. 접두 나열이라 같은 접두의 다른 키는 거른다. */
    private Listing listing(String key) {
        List<ObjectVersion> versions = new ArrayList<>();
        List<DeleteMarkerEntry> markers = new ArrayList<>();
        for (ListObjectVersionsResponse page : s3.listObjectVersionsPaginator(b -> b.bucket(bucket).prefix(key))) {
            page.versions().stream().filter(v -> v.key().equals(key)).forEach(versions::add);
            page.deleteMarkers().stream().filter(m -> m.key().equals(key)).forEach(markers::add);
        }
        return new Listing(versions, markers);
    }

    private record Listing(List<ObjectVersion> versions, List<DeleteMarkerEntry> markers) {
        List<String> allIds() {
            List<String> ids = new ArrayList<>();
            versions.forEach(v -> ids.add(v.versionId()));
            markers.forEach(m -> ids.add(m.versionId()));
            return ids;
        }
    }

    private String latestVersion(String key) {
        return listing(key).versions().stream().filter(ObjectVersion::isLatest).map(ObjectVersion::versionId).findFirst()
                .orElseThrow(() -> new ArtifactMissingException(key));
    }

    private static RuntimeException locked(String key, S3Exception e) {
        return e.statusCode() == 403 || e.statusCode() == 400 ? new ObjectLockedException(key, e) : e;
    }

    public String bucket() {
        return bucket;
    }

    @Override
    public void close() {
        s3.close();
    }
}
