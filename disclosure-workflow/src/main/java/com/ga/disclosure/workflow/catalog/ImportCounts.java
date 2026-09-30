package com.ga.disclosure.workflow.catalog;

/**
 * 수입 결과 행 수. inserted: 새 키, updated: 내용 변경, closed: 파일에 없어 유효기간을 기준일로 닫음, unchanged: 내용 같음
 * (출처·동기 시각만 갱신).
 */
public record ImportCounts(int inserted, int updated, int closed, int unchanged) {

    public static final ImportCounts NONE = new ImportCounts(0, 0, 0, 0);

    public ImportCounts {
        if (inserted < 0 || updated < 0 || closed < 0 || unchanged < 0) {
            throw new IllegalArgumentException("counts must be >= 0");
        }
    }
}
