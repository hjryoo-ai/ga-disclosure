package com.ga.disclosure.infra;

import com.ga.disclosure.workflow.artifact.ArtifactMissingException;
import com.ga.disclosure.workflow.artifact.ArtifactStore;
import com.ga.disclosure.workflow.artifact.ArtifactStore.Capabilities;
import com.ga.disclosure.workflow.artifact.ArtifactStore.Support;
import com.ga.disclosure.workflow.artifact.ArtifactStore.VersionCount;
import com.ga.disclosure.workflow.artifact.ObjectLockedException;
import com.ga.disclosure.workflow.artifact.UnsupportedCapabilityException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 3B S9·승인 B2: 객체 저장소 계약 — 저장소를 교체할 때의 수용 기준이다. 다른 S3 호환 구현을 붙이면 이 클래스를 상속한 테스트를 만들어 같은 계약을
 * 통과시킨다(Phase 1 {@code DisclosurePolicyRepositoryContract}와 같은 패턴). Object Lock 5종은 2026-09-30 boto3 실측을 옮긴 것이다:
 * ① 잠긴 버전 삭제 거부 ② 거버넌스 우회 헤더로 삭제 거부 ③ 보존기한 단축 거부 ④ 연장 허용 ⑤ 잠기지 않은 객체 삭제 허용.
 * 하위 클래스는 새 버킷의 저장소와 원시 우회 삭제(포트에는 없는 경로)만 공급한다.
 *
 * <p>Phase 5(5 계획 §6, 승인 B1 — 2026-10-03 SeaweedFS 실측을 옮긴 것): 삭제는 모든 버전과 삭제 마커를 버전 ID로 지운다, 없는 키 삭제는 아무것도
 * 만들지 않는다, 어댑터는 버전 없는 삭제·거버넌스 우회 헤더를 보내지 않는다(요청 캡처), legal hold 설정·조회·해제와 보류 중 삭제 거부, 보류는 보존
 * 만료 뒤에도 삭제를 막는다, 능력은 명시적이다(미지원이면 예외 — 조용한 no-op 없음).
 */
abstract class ArtifactStoreContract {

    /** Object Lock 활성·버전 관리 Enabled·기본 규칙 없는 새 버킷의 저장소. */
    protected abstract ArtifactStore freshStore();

    /** 최신 버전을 거버넌스 우회 헤더({@code x-amz-bypass-governance-retention: true})로 지우려 한다. 저장소가 거부했으면 true. */
    protected abstract boolean deleteWithGovernanceBypassRejected(ArtifactStore store, String key);

    /** 이 저장소 구성이 선언해야 하는 능력(5 계획 §6). */
    protected abstract Capabilities expectedCapabilities();

    /** 원시 클라이언트로 버전 없는 삭제를 보내 삭제 마커를 만든다(포트에는 없는 경로 — 마커 정리를 시험하려고). */
    protected abstract void createDeleteMarker(ArtifactStore store, String key);

    /** 새 버킷의 저장소 + 그 어댑터가 보낸 S3 요청의 기록(승인 B1 — SDK 실행 인터셉터로 캡처). */
    protected abstract RecordingStore freshRecordingStore();

    /** 어댑터가 보낸 요청 하나: 작업 이름, 키·버전 ID(해당 없으면 null), 거버넌스 우회 헤더(해당 없으면 null). */
    record SentRequest(String operation, String key, String versionId, Boolean bypassGovernance) {
    }

    record RecordingStore(ArtifactStore store, List<SentRequest> sent) {
    }

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

    // ------------------------------------------------------------------ Phase 5: 파기·보류 (5 계획 §6, 승인 B1)

    @Test
    void capabilitiesAreExplicit() {
        assertThat(store.capabilities()).isEqualTo(expectedCapabilities());
        if (store.capabilities().legalHold() == Support.UNSUPPORTED) {
            String key = key();
            store.put(key, bytes("v1"));
            assertThatThrownBy(() -> store.setLegalHold(key, true)).isInstanceOf(UnsupportedCapabilityException.class);
            assertThatThrownBy(() -> store.legalHold(key)).isInstanceOf(UnsupportedCapabilityException.class);
        }
    }

    @Test
    void deleteRemovesEveryVersionAndMarker() {
        String key = key();
        store.put(key, bytes("v1"));
        store.put(key, bytes("v2"));
        createDeleteMarker(store, key);
        store.put(key, bytes("v3"));
        assertThat(store.versionCount(key)).isEqualTo(new VersionCount(3, 1));

        store.delete(key);

        assertThat(store.versionCount(key)).isEqualTo(new VersionCount(0, 0));
        assertThat(store.exists(key)).isFalse();
    }

    /** 없는 키를 버전 없이 지우면 마커가 생긴다(실측) — 어댑터는 나열한 버전 ID로만 지우므로 아무것도 생기지 않는다. */
    @Test
    void deletingAMissingKeyCreatesNothing() {
        String key = key();
        store.delete(key);
        assertThat(store.versionCount(key)).isEqualTo(new VersionCount(0, 0));
    }

    @Test
    void adapterNeverSendsAVersionlessDelete() {
        RecordingStore recording = freshRecordingStore();
        ArtifactStore s = recording.store();
        String key = key();
        s.put(key, bytes("v1"));
        s.put(key, bytes("v2"));
        createDeleteMarker(s, key);
        s.delete(key);
        s.delete(key() + "-missing");
        String locked = key();
        s.put(locked, bytes("locked"));
        s.applyRetention(locked, in(Duration.ofDays(1)));
        assertThatThrownBy(() -> s.delete(locked)).isInstanceOf(ObjectLockedException.class);

        List<SentRequest> deletes = recording.sent().stream().filter(r -> r.operation().startsWith("DeleteObject")).toList();
        assertThat(deletes).as("어댑터가 실제로 삭제를 보냈다(검사가 빈 집합에 대해 통과하지 않게)").hasSizeGreaterThanOrEqualTo(4);
        assertThat(deletes).allSatisfy(r -> {
            assertThat(r.operation()).as("일괄 삭제(DeleteObjects)도 쓰지 않는다").isEqualTo("DeleteObject");
            assertThat(r.versionId()).as("버전 없는 삭제는 마커만 만든다").isNotBlank();
        });
        assertThat(recording.sent()).noneMatch(r -> Boolean.TRUE.equals(r.bypassGovernance()));
    }

    @Test
    void legalHoldSetGetRelease() {
        if (store.capabilities().legalHold() == Support.UNSUPPORTED) {
            return;                                         // 미지원 분기는 capabilitiesAreExplicit가 단언한다
        }
        String key = key();
        store.put(key, bytes("v1"));
        store.put(key, bytes("v2"));
        assertThat(store.legalHold(key)).isFalse();

        store.setLegalHold(key, true);
        assertThat(store.legalHold(key)).as("모든 버전에 걸린다").isTrue();
        assertThatThrownBy(() -> store.delete(key)).isInstanceOf(ObjectLockedException.class);
        assertThat(store.versionCount(key).versions()).isEqualTo(2);

        store.setLegalHold(key, false);
        assertThat(store.legalHold(key)).isFalse();
        store.delete(key);
        assertThat(store.versionCount(key)).isEqualTo(new VersionCount(0, 0));
        assertThatThrownBy(() -> store.setLegalHold(key, true)).isInstanceOf(ArtifactMissingException.class);
    }

    /** 보류는 보존과 독립이다: 보존기한이 지나도 보류 중이면 삭제가 거부된다(승인 B1). */
    @Test
    void heldVersionCannotBeDeletedEvenAfterRetention() throws InterruptedException {
        if (store.capabilities().legalHold() == Support.UNSUPPORTED) {
            return;
        }
        String key = key();
        store.put(key, bytes("held"));
        Instant until = in(Duration.ofSeconds(3));
        store.applyRetention(key, until);
        store.setLegalHold(key, true);
        while (clock.instant().isBefore(until.plusSeconds(1))) {
            Thread.sleep(200);
        }

        assertThatThrownBy(() -> store.delete(key)).isInstanceOf(ObjectLockedException.class);
        assertThat(store.get(key)).isEqualTo(bytes("held"));

        store.setLegalHold(key, false);
        store.delete(key);
        assertThat(store.versionCount(key)).isEqualTo(new VersionCount(0, 0));
    }
}
