package com.ga.disclosure.audit.verify;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 증거 패키지 ZIP의 자체 리더(생산자 코드 seal과 독립). 엔트리 이름 → 바이트(ZIP 순서 유지). 손상(CRC·형식)·중복 이름·빈 ZIP은 입력 오류(종료 3)다.
 */
final class EvidenceZip {

    private EvidenceZip() {
    }

    static Map<String, byte[]> read(byte[] zip) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                if (out.put(e.getName(), in.readAllBytes()) != null) {
                    throw new VerifyInputException("ZIP_CORRUPT", "duplicate entry in the package");
                }
            }
        } catch (IOException e) {
            throw new VerifyInputException("ZIP_CORRUPT", "the package is not a readable ZIP", e);
        }
        if (out.isEmpty()) {
            throw new VerifyInputException("ZIP_CORRUPT", "the package holds no entry");
        }
        return out;
    }
}
