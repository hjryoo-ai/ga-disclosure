package com.ga.disclosure.infra.storage;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.GetObjectLockConfigurationResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.ObjectLockEnabled;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 기동 확인(3B 계획 §6): 버킷의 Object Lock 활성, 버전 관리 Enabled, **기본 보존 규칙 없음**. 기본 규칙이 있으면 업로드 즉시 잠겨 "커밋 전 잠금
 * 금지"가 깨지므로 기동을 실패시킨다. 개발·테스트용 {@link #createIfMissing}은 표준 {@code CreateBucket(ObjectLockEnabledForBucket=true)}만 쓴다.
 */
public final class ArtifactStoreBootstrap {

    private final S3Client s3;
    private final String bucket;

    public ArtifactStoreBootstrap(S3Client s3, String bucket) {
        this.s3 = Objects.requireNonNull(s3, "s3");
        this.bucket = Objects.requireNonNull(bucket, "bucket");
    }

    /** 버킷이 없으면 Object Lock 활성으로 만든다(개발·테스트 전용 — 운영 버킷은 인프라가 만든다). */
    public void createIfMissing() {
        try {
            s3.headBucket(b -> b.bucket(bucket));
        } catch (NoSuchBucketException e) {
            s3.createBucket(b -> b.bucket(bucket).objectLockEnabledForBucket(true));
        } catch (S3Exception e) {
            if (e.statusCode() != 404) {
                throw e;
            }
            s3.createBucket(b -> b.bucket(bucket).objectLockEnabledForBucket(true));
        }
    }

    /** 기대와 다른 점 목록(비어 있으면 통과). */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        BucketVersioningStatus versioning = s3.getBucketVersioning(b -> b.bucket(bucket)).status();
        if (versioning != BucketVersioningStatus.ENABLED) {
            problems.add("bucket versioning is " + versioning + ", expected Enabled");
        }
        try {
            GetObjectLockConfigurationResponse lock = s3.getObjectLockConfiguration(b -> b.bucket(bucket));
            if (lock.objectLockConfiguration() == null || lock.objectLockConfiguration().objectLockEnabled() != ObjectLockEnabled.ENABLED) {
                problems.add("object lock is not enabled on the bucket");
            } else if (lock.objectLockConfiguration().rule() != null && lock.objectLockConfiguration().rule().defaultRetention() != null) {
                problems.add("bucket has a default retention rule — uploads would lock before the database commit");
            }
        } catch (S3Exception e) {
            problems.add("object lock configuration unavailable (" + e.statusCode() + ")");
        }
        return problems;
    }

    /** 기대와 다르면 실패한다. */
    public void verify() {
        List<String> problems = problems();
        if (!problems.isEmpty()) {
            throw new IllegalStateException("artifact bucket " + bucket + " is not usable: " + problems);
        }
    }
}
