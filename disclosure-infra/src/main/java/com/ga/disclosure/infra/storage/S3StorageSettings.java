package com.ga.disclosure.infra.storage;

import java.net.URI;
import java.util.Objects;

/**
 * S3 호환 저장소 접속 설정. 자격 증명은 설정 파일·환경에서 주입하며 저장소에 커밋하지 않는다(로컬·CI는 허구 키). {@code toString}은 비밀 키를
 * 드러내지 않는다.
 *
 * @param pathStyle 경로 형식 주소(SeaweedFS·대부분의 자체 호스팅 S3 호환 저장소)
 */
public record S3StorageSettings(URI endpoint, String region, String bucket, String accessKeyId, String secretAccessKey, boolean pathStyle) {

    public S3StorageSettings {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(region, "region");
        if (bucket == null || !bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")) {
            throw new IllegalArgumentException("bucket name must be a DNS-compatible S3 bucket name");
        }
        Objects.requireNonNull(accessKeyId, "accessKeyId");
        Objects.requireNonNull(secretAccessKey, "secretAccessKey");
    }

    @Override
    public String toString() {
        return "S3StorageSettings[" + endpoint + ", " + region + ", " + bucket + ", accessKeyId=" + accessKeyId + ", secret=****]";
    }
}
