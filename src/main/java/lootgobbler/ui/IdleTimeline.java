package lootgobbler.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * A time axis for fame charts that leaves out the idle time: stretches of an hour or more without any fame change
 * shrink to a narrow break of fixed width, so the time with fame gets the room. Maps times to x positions.
 */
final class IdleTimeline {
    static final long IDLE = 60 * 60_000L;   // no fame change for this long: cut out
    static final long PAD = 10 * 60_000L;    // shown around fame changes next to a break
    final long from, to;
    final List<long[]> segments = new ArrayList<>();   // the time spans shown; a break between each two
    private final int breakWidth;
    private boolean leading, trailing;

    /** From the times fame changed (sorted), within from..to. */
    IdleTimeline(List<Long> changes, long from, long to, int breakWidth) {
        this.from = from;
        this.to = Math.max(from + 1, to);
        this.breakWidth = breakWidth;
        if (changes.isEmpty()) {
            segments.add(new long[]{from, this.to});
            return;
        }
        long first = changes.get(0);
        long start = first - from <= IDLE ? from : Math.max(from, first - PAD);
        long last = first;
        for (int i = 1; i < changes.size(); i++) {
            long t = changes.get(i);
            if (t - last > IDLE) {
                segments.add(new long[]{start, Math.min(this.to, last + PAD)});
                start = Math.max(from, t - PAD);
            }
            last = t;
        }
        segments.add(new long[]{start, this.to - last <= IDLE ? this.to : Math.min(this.to, last + PAD)});
        leading = segments.get(0)[0] > from;
        trailing = segments.get(segments.size() - 1)[1] < this.to;
    }

    /** The times a series of {time, fame} points changes fame (its first point counts). */
    static List<Long> changes(List<long[]> points) {
        List<Long> out = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            if (i == 0 || points.get(i)[1] != points.get(i - 1)[1]) out.add(points.get(i)[0]);
        }
        return out;
    }

    private int breaks() {
        return segments.size() - 1 + (leading ? 1 : 0) + (trailing ? 1 : 0);
    }

    /** Screen x of a time between left and right; inside a cut-out stretch, the middle of its break. */
    double x(long t, int left, int right) {
        long shown = 0;
        for (long[] s : segments) shown += Math.max(1, s[1] - s[0]);
        double perMs = Math.max(1, right - left - breaks() * breakWidth) / (double) shown;
        double x = left + (leading ? breakWidth : 0);
        if (t < segments.get(0)[0]) return left + (leading ? breakWidth / 2.0 : 0);
        for (long[] s : segments) {
            if (t <= s[1]) return t >= s[0] ? x + (t - s[0]) * perMs : x - breakWidth / 2.0;
            x += Math.max(1, s[1] - s[0]) * perMs + breakWidth;
        }
        return right - (trailing ? breakWidth / 2.0 : 0);
    }

    /** The cut-out stretches as {startTime, endTime, centerX}. */
    List<double[]> breaks(int left, int right) {
        List<double[]> out = new ArrayList<>();
        if (leading) out.add(new double[]{from, segments.get(0)[0], left + breakWidth / 2.0});
        for (int i = 1; i < segments.size(); i++) {
            long a = segments.get(i - 1)[1], b = segments.get(i)[0];
            out.add(new double[]{a, b, x(a, left, right) + breakWidth / 2.0});
        }
        if (trailing) out.add(new double[]{segments.get(segments.size() - 1)[1], to, right - breakWidth / 2.0});
        return out;
    }

    /** "14h", "1h 20m" or "54m". */
    static String gap(long ms) {
        long m = ms / 60_000, hours = m / 60;
        if (hours == 0) return m + "m";
        return hours >= 10 || m % 60 == 0 ? hours + "h" : hours + "h " + (m % 60) + "m";
    }
}
