package com.ga.disclosure.infra.storage;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry;
import software.amazon.awssdk.services.s3.model.GetObjectRetentionResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ObjectLockLegalHold;
import software.amazon.awssdk.services.s3.model.ObjectLockLegalHoldStatus;
import software.amazon.awssdk.services.s3.model.ObjectLockRetention;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 객체 버전 전수 복제(Phase 8 ⑤, 8 계획 "저장소 — 객체 버전 전수 복제"): 원본 버킷의 접두 아래 키마다 <b>모든 버전과 삭제 마커</b>를 시간 순서대로 대상
 * 버킷에 다시 만들고, 버전마다 보존 모드·기한과 법적 보류를 그대로 건다. 대상의 버전 ID는 새로 생긴다 — DB는 버전 ID를 저장하지 않는다(키 + 바이트 해시로 대조).
 * 산출물은 이미 문서 DEK로 암호화된 바이트라 복제본도 평문이 아니다. 대상 접두는 비어 있어야 한다(두 번 복제하면 버전이 겹친다).
 * 표준 S3 API만(ListObjectVersions·GetObject(versionId)·PutObject·Put/GetObjectRetention·Put/GetObjectLegalHold·DeleteObject).
 */
public final class ObjectVersionCopier {

    /** 복제한 항목 하나(키는 원본 접두를 뗀 상대 키). 삭제 마커는 바이트·보존이 없다. */
    public record Entry(String key, boolean deleteMarker, String sha256, Optional<String> retentionMode, Optional<Instant> retainUntil, boolean legalHold) {
    }

    /** 결과: 항목(시간 순)과 집계. {@code maxRetainUntil}은 복제본 중 가장 늦은 보존 기한(DB 백업의 보존 기한 하한 — 운영 문서). */
    public record Report(List<Entry> entries) {
        public long keys() {
            return entries.stream().map(Entry::key).distinct().count();
        }

        public long versions() {
            return entries.stream().filter(e -> !e.deleteMarker()).count();
        }

        public long deleteMarkers() {
            return entries.stream().filter(Entry::deleteMarker).count();
        }

        public long retained() {
            return entries.stream().filter(e -> e.retainUntil().isPresent()).count();
        }

        public long held() {
            return entries.stream().filter(Entry::legalHold).count();
        }

        public Optional<Instant> maxRetainUntil() {
            return entries.stream().flatMap(e -> e.retainUntil().stream()).max(Comparator.naturalOrder());
        }
    }

    /**
     * {@code latest}: S3가 키마다 하나에만 다는 최신 표지. {@code order}: 목록 응답 안 위치(키마다 최신부터). 같은 초에 쓴 항목(SeaweedFS는 초 단위 —
     * 버전 다음 곧바로 삭제)의 순서는 최신 표지가 마지막, 나머지는 응답 역순 — SDK는 버전과 마커를 다른 목록으로 나눠 응답의 섞인 순서를 잃는다.
     */
    private record Item(String key, Instant lastModified, boolean latest, int order, ObjectVersion version, DeleteMarkerEntry marker) {
    }

    private ObjectVersionCopier() {
    }

    public static Report copy(S3Client from, String fromBucket, String fromPrefix, S3Client to, String toBucket, String toPrefix) {
        Objects.requireNonNull(fromPrefix, "fromPrefix");
        Objects.requireNonNull(toPrefix, "toPrefix");
        if (!items(to, toBucket, toPrefix).isEmpty()) {
            throw new IllegalStateException("target " + toBucket + "/" + toPrefix + " is not empty — copy into an empty prefix");
        }
        List<Entry> entries = new ArrayList<>();
        for (Item item : items(from, fromBucket, fromPrefix)) {
            String relative = item.key().substring(fromPrefix.length());
            String target = toPrefix + relative;
            if (item.marker() != null) {
                to.deleteObject(b -> b.bucket(toBucket).key(target));
                entries.add(new Entry(relative, true, "", Optional.empty(), Optional.empty(), false));
                continue;
            }
            String versionId = item.version().versionId();
            byte[] bytes = from.getObjectAsBytes(b -> b.bucket(fromBucket).key(item.key()).versionId(versionId)).asByteArray();
            String newVersion = to.putObject(b -> b.bucket(toBucket).key(target).contentType("application/octet-stream"), RequestBody.fromBytes(bytes)).versionId();
            Optional<ObjectLockRetention> retention = retention(from, fromBucket, item.key(), versionId);
            retention.ifPresent(r -> to.putObjectRetention(b -> b.bucket(toBucket).key(target).versionId(newVersion).retention(r)));
            boolean hold = holdOn(from, fromBucket, item.key(), versionId);
            if (hold) {
                to.putObjectLegalHold(b -> b.bucket(toBucket).key(target).versionId(newVersion)
                        .legalHold(ObjectLockLegalHold.builder().status(ObjectLockLegalHoldStatus.ON).build()));
            }
            entries.add(new Entry(relative, false, sha256(bytes), retention.map(r -> r.modeAsString()), retention.map(ObjectLockRetention::retainUntilDate), hold));
        }
        return new Report(List.copyOf(entries));
    }

    /** 대상의 지금 상태를 같은 모양으로 읽는다(복제 뒤 대조 — 버전·마커 순서, 바이트 해시, 보존, 보류). */
    public static Report describe(S3Client s3, String bucket, String prefix) {
        List<Entry> entries = new ArrayList<>();
        for (Item item : items(s3, bucket, prefix)) {
            String relative = item.key().substring(prefix.length());
            if (item.marker() != null) {
                entries.add(new Entry(relative, true, "", Optional.empty(), Optional.empty(), false));
                continue;
            }
            String versionId = item.version().versionId();
            byte[] bytes = s3.getObjectAsBytes(b -> b.bucket(bucket).key(item.key()).versionId(versionId)).asByteArray();
            Optional<ObjectLockRetention> retention = retention(s3, bucket, item.key(), versionId);
            entries.add(new Entry(relative, false, sha256(bytes), retention.map(r -> r.modeAsString()), retention.map(ObjectLockRetention::retainUntilDate),
                    holdOn(s3, bucket, item.key(), versionId)));
        }
        return new Report(List.copyOf(entries));
    }

    /** 접두 아래 버전·마커 전부(오래된 것부터). */
    private static List<Item> items(S3Client s3, String bucket, String prefix) {
        List<Item> items = new ArrayList<>();
        int[] order = {0};
        for (ListObjectVersionsResponse page : s3.listObjectVersionsPaginator(b -> b.bucket(bucket).prefix(prefix))) {
            page.versions().forEach(v -> items.add(new Item(v.key(), v.lastModified(), Boolean.TRUE.equals(v.isLatest()), order[0]++, v, null)));
            page.deleteMarkers().forEach(m -> items.add(new Item(m.key(), m.lastModified(), Boolean.TRUE.equals(m.isLatest()), order[0]++, null, m)));
        }
        // 키 → 시각 → 같은 시각이면 최신 표지가 마지막 → 응답 역순 — 오래된 것부터 다시 만든다
        items.sort(Comparator.comparing(Item::key).thenComparing(Item::lastModified).thenComparing(Item::latest)
                .thenComparing(Item::order, Comparator.reverseOrder()));
        return items;
    }

    private static Optional<ObjectLockRetention> retention(S3Client s3, String bucket, String key, String versionId) {
        try {
            GetObjectRetentionResponse r = s3.getObjectRetention(b -> b.bucket(bucket).key(key).versionId(versionId));
            return Optional.ofNullable(r.retention()).filter(x -> x.retainUntilDate() != null);
        } catch (S3Exception e) {
            if (e.statusCode() == 404 || e.statusCode() == 400) {
                return Optional.empty();
            }
            throw e;
        }
    }

    private static boolean holdOn(S3Client s3, String bucket, String key, String versionId) {
        try {
            ObjectLockLegalHold hold = s3.getObjectLegalHold(b -> b.bucket(bucket).key(key).versionId(versionId)).legalHold();
            return hold != null && hold.status() == ObjectLockLegalHoldStatus.ON;
        } catch (S3Exception e) {
            if (e.statusCode() == 404 || e.statusCode() == 400) {
                return false;
            }
            throw e;
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
