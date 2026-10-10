package com.ga.disclosure.infra.testing;

import com.ga.disclosure.infra.storage.ArtifactStoreBootstrap;
import com.ga.disclosure.infra.storage.S3ArtifactStore;
import com.ga.disclosure.infra.storage.S3StorageSettings;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 통합 테스트용 S3 호환 저장소(SeaweedFS, 3B 계획 승인 Q1). JVM당 1회 기동·재사용. 이미지는 태그가 아니라 <b>digest로 고정</b>한다(태그는 사라질 수
 * 있다 — MinIO가 실례). S3 인증을 켠다(허구 키, {@code seaweedfs/s3.json}) — 서명 없는 요청은 거부된다. 테스트마다 새 버킷을 Object Lock
 * 활성으로 만든다(표준 {@code CreateBucket}만). Docker가 없으면 {@link #get()}이 예외로 테스트를 <b>실패</b>시킨다(스킵하지 않는다).
 */
public final class SeaweedHarness {

    /** chrislusf/seaweedfs:4.48 — digest는 2026-09-30 실측·2026-10-01 재확인(다중 아키텍처 인덱스). */
    public static final String IMAGE = "chrislusf/seaweedfs@sha256:4e61d15fd35994cb1e43e1e553dff106794841fd9a99ade2fc8c8bfce4d7872d";
    public static final String ACCESS_KEY = "ga-local-test-access";
    public static final String SECRET_KEY = "ga-local-test-secret-not-a-real-key";   // 허구 키(seaweedfs/s3.json)
    /**
     * 볼륨 한도는 디스크와 무관하게 고정한다. 기본값({@code server}의 볼륨 수 = 여유 디스크 ÷ 볼륨 크기 1 GiB)에서는 버킷(= 컬렉션)마다 볼륨 7개를 잡아
     * 여유 디스크가 작은 CI 러너에서 십수 번째 버킷부터 쓰기가 500 {@code InternalError}가 됐다(로컬 재현: {@code -volume.max=14}면 세 번째 버킷).
     * 볼륨은 미리 할당하지 않으므로 한도가 커도 디스크를 잡지 않는다. compose도 같은 값을 쓴다({@code SeaweedArtifactStoreIT}가 대조).
     */
    public static final List<String> VOLUME_LIMITS = List.of("-volume.max=2000", "-master.volumeSizeLimitMB=64");
    private static final int S3_PORT = 8333;
    /**
     * 컨테이너에 복사하는 파일의 모드(0644 — 컨테이너의 비루트 프로세스가 읽는다). 지정하지 않으면 원본 파일 모드를 따른다 — umask 077로 뜬 Gradle 데몬이
     * 처리한 자원은 0600이 되고 SeaweedFS가 {@code s3.json}을 못 읽어 종료했다(11단계 — kind.sh 안에서 데몬이 떴다). 다른 하네스도 이 값을 쓴다.
     */
    public static final int FILE_MODE = 0644;

    private static SeaweedHarness instance;

    private final GenericContainer<?> container;

    @SuppressWarnings("resource")
    private SeaweedHarness() {
        container = new GenericContainer<>(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("chrislusf/seaweedfs"))
                .withCopyFileToContainer(MountableFile.forClasspathResource("seaweedfs/s3.json", FILE_MODE), "/etc/seaweedfs/s3.json")
                .withCommand(command().toArray(String[]::new))
                .withExposedPorts(S3_PORT)
                .waitingFor(Wait.forListeningPorts(S3_PORT).withStartupTimeout(Duration.ofMinutes(2)));
        container.start();
    }

    private static List<String> command() {
        List<String> command = new ArrayList<>(List.of("server", "-dir=/data"));
        command.addAll(VOLUME_LIMITS);
        command.addAll(List.of("-s3", "-s3.port=" + S3_PORT, "-s3.config=/etc/seaweedfs/s3.json"));
        return command;
    }

    public static synchronized SeaweedHarness get() {
        if (instance == null) {
            instance = new SeaweedHarness();
        }
        return instance;
    }

    public URI endpoint() {
        return URI.create("http://" + container.getHost() + ":" + container.getMappedPort(S3_PORT));
    }

    public S3StorageSettings settings(String bucket) {
        return new S3StorageSettings(endpoint(), "us-east-1", bucket, ACCESS_KEY, SECRET_KEY, true);
    }

    public S3Client client() {
        return S3ArtifactStore.client(settings("unused-bucket"));
    }

    /** Object Lock 활성 버킷을 새로 만들고 기동 확인을 통과시킨 저장소. */
    public S3ArtifactStore freshStore() {
        String bucket = freshBucket();
        return new S3ArtifactStore(client(), bucket);
    }

    public String freshBucket() {
        String bucket = "ga-art-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        ArtifactStoreBootstrap bootstrap = new ArtifactStoreBootstrap(client(), bucket);
        bootstrap.createIfMissing();
        bootstrap.verify();
        return bucket;
    }
}
