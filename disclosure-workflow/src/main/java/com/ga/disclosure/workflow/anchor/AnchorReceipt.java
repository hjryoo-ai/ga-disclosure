package com.ga.disclosure.workflow.anchor;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 테넌트 영수증 1행({@code anchor_receipt}): 자기 잎의 경로와 그 날짜 배치의 루트·TSA 토큰. 같은 배치의 영수증끼리 {@code batchId}·루트·토큰이 같다
 * (테넌트 없는 배치 테이블은 없다 — 4 수용심사 결정 2). DB가 경로로 루트를 다시 계산한다(GD111).
 */
public record AnchorReceipt(long anchorSeq, UUID batchId, String rootHash, int treeDepth, int leafIndex, List<String> merklePath, byte[] tsaToken,
                            Instant tsaGenTime, String tsaPolicyOid, String tsaSerial, Instant createdAt) {

    public AnchorReceipt {
        Objects.requireNonNull(batchId, "batchId");
        Objects.requireNonNull(rootHash, "rootHash");
        merklePath = List.copyOf(merklePath);
        tsaToken = Objects.requireNonNull(tsaToken, "tsaToken").clone();
        Objects.requireNonNull(tsaGenTime, "tsaGenTime");
        Objects.requireNonNull(tsaPolicyOid, "tsaPolicyOid");
        Objects.requireNonNull(tsaSerial, "tsaSerial");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    @Override
    public byte[] tsaToken() {
        return tsaToken.clone();
    }
}
