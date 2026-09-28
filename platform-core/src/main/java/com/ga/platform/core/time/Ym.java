package com.ga.platform.core.time;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 연월(YYYYMM). 정산·집계 기간 축에 쓴다.
 *
 * @param year  1000~9999
 * @param month 1~12
 */
public record Ym(int year, int month) implements Comparable<Ym> {

    private static final Pattern FORMAT = Pattern.compile("\\d{6}");

    public Ym {
        if (year < 1000 || year > 9999) {
            throw new IllegalArgumentException("year out of range: " + year);
        }
        if (month < 1 || month > 12) {
            throw new IllegalArgumentException("month out of range: " + month);
        }
    }

    public static Ym of(int year, int month) {
        return new Ym(year, month);
    }

    /** {@code "202609"} 형식만 받는다. */
    public static Ym parse(String yyyymm) {
        if (yyyymm == null || !FORMAT.matcher(yyyymm).matches()) {
            throw new IllegalArgumentException("expected YYYYMM: " + yyyymm);
        }
        return new Ym(Integer.parseInt(yyyymm.substring(0, 4)), Integer.parseInt(yyyymm.substring(4, 6)));
    }

    public Ym next() {
        return month == 12 ? new Ym(year + 1, 1) : new Ym(year, month + 1);
    }

    public Ym prev() {
        return month == 1 ? new Ym(year - 1, 12) : new Ym(year, month - 1);
    }

    /** {@code from}부터 {@code to}까지(양끝 포함) 오름차순. {@code from > to}이면 예외. */
    public static List<Ym> range(Ym from, Ym to) {
        if (from.compareTo(to) > 0) {
            throw new IllegalArgumentException("from > to: " + from + " > " + to);
        }
        List<Ym> out = new ArrayList<>();
        for (Ym cur = from; ; cur = cur.next()) {
            out.add(cur);
            if (cur.equals(to)) {
                return Collections.unmodifiableList(out);
            }
        }
    }

    public String value() {
        return String.format("%04d%02d", year, month);
    }

    @Override
    public int compareTo(Ym other) {
        return year != other.year ? Integer.compare(year, other.year) : Integer.compare(month, other.month);
    }

    @Override
    public String toString() {
        return value();
    }
}
