package com.ga.disclosure.infra;

import com.ga.disclosure.infra.testing.SeaweedHarness;
import com.ga.disclosure.workflow.artifact.ArtifactStore;
import com.ga.disclosure.workflow.artifact.ArtifactStore.Capabilities;
import com.ga.disclosure.workflow.artifact.ArtifactStore.Support;

/**
 * 5 계획 §6: legal hold 미지원 저장소의 계약 — 대역({@link FailingPorts.Store}, 보류만 미지원으로 선언하고 나머지는 SeaweedFS에 위임)이 같은 계약을
 * 통과한다. 미지원은 {@code capabilities()}에 드러나고 호출은 예외다(조용한 no-op 없음).
 */
class LegalHoldUnsupportedStoreIT extends ArtifactStoreContract {

    private static final SeaweedHarness S3 = SeaweedHarness.get();

    private static FailingPorts.Store unsupported(ArtifactStore delegate) {
        FailingPorts.Store store = new FailingPorts.Store(delegate);
        store.legalHoldUnsupported.set(true);
        return store;
    }

    @Override
    protected ArtifactStore freshStore() {
        return unsupported(S3.freshStore());
    }

    @Override
    protected boolean deleteWithGovernanceBypassRejected(ArtifactStore store, String key) {
        return SeaweedArtifactStoreIT.governanceBypassRejected(store, key);
    }

    @Override
    protected Capabilities expectedCapabilities() {
        return new Capabilities(Support.UNSUPPORTED);
    }

    @Override
    protected void createDeleteMarker(ArtifactStore store, String key) {
        SeaweedArtifactStoreIT.versionlessDelete(store, key);
    }

    @Override
    protected RecordingStore freshRecordingStore() {
        RecordingStore seaweed = SeaweedArtifactStoreIT.recordingSeaweedStore();
        return new RecordingStore(unsupported(seaweed.store()), seaweed.sent());
    }
}
