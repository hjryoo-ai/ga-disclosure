package com.ga.platform.core.arch.fixtures.ordering;

import java.util.Comparator;
import java.util.List;

/** ArchRulesTest 전용 표본. {@link Item}은 GradeSnapshotItem, {@link Label}은 RatioLabel 역할이다. */
public final class OrderingFixtures {

    private OrderingFixtures() {
    }

    public record Item(int rank, Label label) {
    }

    public record Label(String value) {
    }

    public record ComparableLabel(String value) implements Comparable<ComparableLabel> {
        @Override
        public int compareTo(ComparableLabel o) {
            return value.compareTo(o.value);
        }
    }

    public static final class SortsItemsByComparator {
        List<Item> sort(List<Item> items) {
            return items.stream().sorted(Comparator.comparingInt(Item::rank)).toList();
        }
    }

    public static final class MaxOfItems {
        Item max(List<Item> items) {
            return items.stream().max((a, b) -> Integer.compare(a.rank(), b.rank())).orElseThrow();
        }
    }

    public static final class SortsLabelStrings {
        List<String> sort(List<Item> items) {
            return items.stream().map(i -> i.label().value()).sorted().toList();
        }
    }

    public static final class AllowedChecker {
        boolean monotonic(List<Item> items) {
            return items.stream().sorted(Comparator.comparingInt(Item::rank)).count() == items.size();
        }
    }

    public static final class UnrelatedSorter {
        List<String> sort(List<String> names) {
            return names.stream().sorted().toList();
        }
    }

    public static final class ReadsLabelByReference {
        List<String> read(List<Label> labels) {
            return labels.stream().map(Label::value).toList();
        }
    }
}
