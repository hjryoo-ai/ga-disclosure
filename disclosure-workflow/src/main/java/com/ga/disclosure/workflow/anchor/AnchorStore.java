package com.ga.disclosure.workflow.anchor;

import com.ga.disclosure.audit.anchor.AnchorRecord;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 바인딩된 테넌트의 앵커·영수증 저장소(infra가 구현, RLS). append-only — 수정·삭제 경로는 없다(V9 GD030). */
public interface AnchorStore {

    Optional<StoredAnchor> onDate(LocalDate anchorDate);

    Optional<StoredAnchor> latest();

    /** 지금 스냅샷의 두 체인 머리. 봉인이 없으면 seq 0·64개 0, 감사가 없으면 seq 0·64개 0. */
    ChainHeads heads();

    /** DB가 잎·두 머리를 다시 계산해 대조한다(GD110). */
    void insert(AnchorRecord record, Instant createdAt);

    /** 영수증이 없는 앵커(앵커 순번 오름차순). */
    List<StoredAnchor> unstamped();

    /** DB가 경로로 루트를 다시 계산해 대조한다(GD111). 앵커당 최대 1행. */
    void insertReceipt(AnchorReceipt receipt);

    Optional<AnchorReceipt> receipt(long anchorSeq);

    record ChainHeads(long sealChainSeq, String sealChainHead, long auditSeq, String auditHead) {
    }

    record StoredAnchor(AnchorRecord record, String leafHash, Instant createdAt) {
    }
}
