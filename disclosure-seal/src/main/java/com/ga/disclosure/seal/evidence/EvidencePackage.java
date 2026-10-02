package com.ga.disclosure.seal.evidence;

import java.util.Arrays;

/** 증거 패키지 결과: ZIP 바이트, 그 SHA-256({@code document_artifact} EVIDENCE_ZIP의 평문 해시), 매니페스트(JCS) SHA-256. */
public record EvidencePackage(byte[] zip, String sha256, String manifestSha256) {

    public EvidencePackage {
        zip = zip.clone();
    }

    @Override
    public byte[] zip() {
        return zip.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof EvidencePackage p && Arrays.equals(zip, p.zip);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(zip);
    }

    @Override
    public String toString() {
        return "EvidencePackage[" + zip.length + " bytes, " + sha256 + "]";
    }
}
