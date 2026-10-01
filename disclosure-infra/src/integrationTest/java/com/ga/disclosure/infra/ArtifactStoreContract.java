package com.ga.disclosure.infra;

import com.ga.disclosure.workflow.artifact.ArtifactMissingException;
import com.ga.disclosure.workflow.artifact.ArtifactStore;
import com.ga.disclosure.workflow.artifact.ObjectLockedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 3B S9·승인 B2: 객체 저장소 계약 — 저장소를 교체할 때의 수용 기준이다. 다른 S3 호환 구현을 붙이면 이 클래스를 상속한 테스트를 만들어 같은 계약을
 * 통과시킨다(Phase 1 {@code DisclosurePolicyRepositoryContract}와 같은 패턴). Object Lock 5종은 2026-09-30 boto3 실측을 옮긴 것이다:
 * ① 잠긴 버전 삭제 거부 ② 거버넌스 우회 헤더로 삭제 거부 ③ 보존기한 단축 거부 ④ 연장 허용 ⑤ 잠기지 않은 객체 삭제 허용.
 * 하위 클래스는 새 버킷의 저장소와 원시 우회 삭제(포트에는 없는 경로)만 공급한다.
 */
abstract class ArtifactStoreContract {

    /** Object Lock 활성·버전 관리 Enabled·기본 규칙 없는 새 버킷의 저장소. */
    protected abstract ArtifactStore freshStore();

    /** 최신 버전을 거버넌스 우회 헤더({@code x-amz-bypass-governance-retention: true})로 지우려 한다. 저장소가 거부했으면 true. */
    protected abstract boolean deleteWithGovernanceBypassRejected(ArtifactStore store, String key);

    private ArtifactStore store;
    private final Clock clock = Clock.systemUTC();      // Object Lock 기한은 저장소의 실제 시각과 비교된다

    @BeforeEach
    void bucket() {
        store = freshStore();
    }

    private static String key() {
        return "T1/" + UUID.randomUUID() + "/PDF/" + "0".repeat(64);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private Instant in(Duration d) {
        return clock.instant().plus(d).truncatedTo(ChronoUnit.SECONDS);
    }

    @Test
    void putGetExistsAndList() {
        String key = key();
        assertThat(store.exists(key)).isFalse();
        store.put(key, bytes("암호문 자리"));
        assertThat(store.get(key)).isEqualTo(bytes("암호문 자리"));
        assertThat(store.exists(key)).isTrue();
        assertThat(store.list(key.substring(0, key.indexOf('/') + 1))).anySatisfy(o -> {
            assertThat(o.key()).isEqualTo(key);
            assertThat(o.size()).isEqualTo(bytes("암호문 자리").length);
        });
        assertThat(store.retention(key)).as("올린 직후에는 잠금이 없다(기본 규칙 없음)").isEmpty();
        assertThatThrownBy(() -> store.get(key() + "-none")).isInstanceOf(ArtifactMissingException.class);
    }

    /** ⑤ 잠기지 않은 객체는 지울 수 있다(커밋 실패 잔여물 정리 경로). 모든 버전이 사라진다. */
    @Test
    void unlockedObjectCanBeDeleted() {
        String key = key();
        store.put(key, bytes("v1"));
        store.put(key, bytes("v2"));
        store.delete(key);
        assertThat(store.exists(key)).isFalse();
    }

    /** ① 잠긴 버전은 보존기한 전에 지울 수 없다. */
    @Test
    void lockedVersionCannotBeDeleted() {
        String key = key();
        store.put(key, bytes("locked"));
        store.applyRetention(key, in(Duration.ofDays(1)));
        assertThatThrownBy(() -> store.delete(key)).isInstanceOf(ObjectLockedException.class);
        assertThat(store.get(key)).isEqualTo(bytes("locked"));
    }

    /** ② 거버넌스 우회 헤더도 COMPLIANCE 잠금을 풀지 못한다. */
    @Test
    void governanceBypassHeaderCannotDelete() {
        String key = key();
        store.put(key, bytes("locked"));
        store.applyRetention(key, in(Duration.ofDays(1)));
        assertThat(deleteWithGovernanceBypassRejected(store, key)).isTrue();
        assertThat(store.get(key)).isEqualTo(bytes("locked"));
    }

    /** ③ 보존기한은 앞당길 수 없다(승인 Q6 — 단축 경로는 코드에도 없다). */
    @Test
    void retentionCannotBeShortened() {
        String key = key();
        store.put(key, bytes("locked"));
        Instant far = in(Duration.ofDays(2));
        store.applyRetention(key, far);
        assertThatThrownBy(() -> store.applyRetention(key, in(Duration.ofDays(1)))).isInstanceOf(ObjectLockedException.class);
        assertThat(store.retention(key)).hasValueSatisfying(t -> assertThat(t.truncatedTo(ChronoUnit.SECONDS)).isEqualTo(far));
    }

    /** ④ 보존기한은 늘릴 수 있다(Phase 4 완료·계약 연결 시 연장). */
    @Test
    void retentionCanBeExtended() {
        String key = key();
        store.put(key, bytes("locked"));
        store.applyRetention(key, in(Duration.ofDays(1)));
        Instant later = in(Duration.ofDays(3));
        store.applyRetention(key, later);
        assertThat(store.retention(key)).hasValueSatisfying(t -> assertThat(t.truncatedTo(ChronoUnit.SECONDS)).isEqualTo(later));
    }

    /** 같은 키에 새로 올려도 잠긴 버전은 남고 지워지지 않는다. */
    @Test
    void overwritingDoesNotReleaseALockedVersion() {
        String key = key();
        store.put(key, bytes("v1"));
        store.applyRetention(key, in(Duration.ofDays(1)));
        store.put(key, bytes("v2"));
        assertThat(store.get(key)).isEqualTo(bytes("v2"));
        assertThatThrownBy(() -> store.delete(key)).isInstanceOf(ObjectLockedException.class);
    }
}
