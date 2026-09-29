package com.ga.disclosure.rules.testing;

import com.ga.disclosure.domain.enums.TieBreak;
import com.ga.disclosure.domain.grade.GradeSnapshot;
import com.ga.disclosure.domain.grade.GradeSnapshotItem;
import com.ga.disclosure.domain.grade.RatioLabel;
import com.ga.disclosure.domain.vo.ProductKey;
import com.ga.disclosure.domain.vo.SnapshotId;

import java.util.List;

/** 테스트 픽스처: 엔진 스냅샷. 등급 코드·라벨은 서수에서 만든 자리값이다(도메인은 등급의 의미를 모른다). */
public final class Snapshots {

    public static final String GRADING = "GRADING-2026-07";
    public static final String RANKING = "RANK-2026-07";

    private Snapshots() {
    }

    public static GradeSnapshotItem ok(String productKey, int gradeOrdinal, int rankInSet, boolean tie) {
        return GradeSnapshotItem.ok(ProductKey.parse(productKey), "G" + gradeOrdinal, "등급" + gradeOrdinal, gradeOrdinal, rankInSet, tie,
                new RatioLabel("r" + gradeOrdinal));
    }

    public static GradeSnapshotItem unavailable(String productKey, String reason) {
        return GradeSnapshotItem.unavailable(ProductKey.parse(productKey), reason);
    }

    public static GradeSnapshot snapshot(TieBreak tieBreak, GradeSnapshotItem... items) {
        return new GradeSnapshot(SnapshotId.of("GRD-TEST"), GRADING, RANKING, tieBreak, List.of(items));
    }

    public static GradeSnapshot snapshot(String grading, String ranking, TieBreak tieBreak, List<GradeSnapshotItem> items) {
        return new GradeSnapshot(SnapshotId.of("GRD-TEST"), grading, ranking, tieBreak, items);
    }
}
