package com.ga.disclosure.workflow.sign;

import java.util.Arrays;
import java.util.Objects;

/**
 * 종이 스캔 업로드 입력(설계서 §6.5 PAPER_SCAN, 4 계획 §7.2): 스캔 이미지(PNG 또는 JPEG)와 설계사가 스캔본 각주에서 읽어 입력한 확인서 번호·해시
 * 접두(canonical 해시 앞 {@value #HASH_PREFIX_LENGTH}자 — 봉인 PDF 각주와 같은 길이). OCR은 하지 않는다. 스캔은 문서 키로 암호화돼 서명 증거
 * 객체(SCAN)가 된다.
 */
public final class PaperScan {

    public static final int HASH_PREFIX_LENGTH = 12;
    /** 저장 안전 상한(규제 룰이 아니다). */
    public static final int MAX_SCAN_BYTES = 20 * 1024 * 1024;
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

    private final byte[] image;
    private final String enteredNo;
    private final String enteredHashPrefix;

    public PaperScan(byte[] image, String enteredNo, String enteredHashPrefix) {
        Objects.requireNonNull(image, "image");
        boolean jpeg = image.length > JPEG.length && Arrays.equals(image, 0, JPEG.length, JPEG, 0, JPEG.length);
        this.image = jpeg && image.length <= MAX_SCAN_BYTES ? image.clone() : SignatureCapture.requirePng(image, MAX_SCAN_BYTES);
        this.enteredNo = Objects.requireNonNull(enteredNo, "enteredNo").strip();
        this.enteredHashPrefix = Objects.requireNonNull(enteredHashPrefix, "enteredHashPrefix").strip().toLowerCase(java.util.Locale.ROOT);
    }

    public byte[] image() {
        return image.clone();
    }

    /** 원본과 대조한다(입력값은 돌려주지 않는다). */
    public ScanMatch matchAgainst(String disclosureNo, String canonicalHashHex) {
        boolean prefix = enteredHashPrefix.length() == HASH_PREFIX_LENGTH && canonicalHashHex.startsWith(enteredHashPrefix);
        return new ScanMatch(enteredNo.equals(disclosureNo), prefix, HASH_PREFIX_LENGTH);
    }

    @Override
    public String toString() {
        return "PaperScan[" + image.length + "B]";
    }
}
