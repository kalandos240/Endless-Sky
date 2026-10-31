package io.github.kalandos240.endlesssky;

import java.util.function.LongSupplier;

public final class AdScheduleTest {
    private static final class Clock implements LongSupplier {
        long now;
        @Override public long getAsLong() { return now; }
        void advance(long millis) { now += millis; }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        Clock clock = new Clock();
        AdSchedule schedule = new AdSchedule(clock);
        clock.advance(1_000_000);
        check(schedule.tryBegin(true, true) == 0, "background launch");
        schedule.foreground(true);
        clock.advance(149_999);
        check(schedule.tryBegin(true, true) == 0, "early ad");
        clock.advance(1);
        check(schedule.tryBegin(false, true) == 0, "combat must defer ad");
        check(schedule.tryBegin(true, false) == 0, "no fill must not block game");
        long first = schedule.tryBegin(true, true);
        check(first != 0, "150 second boundary");
        check(schedule.tryBegin(true, true) == 0, "duplicate display");
        schedule.shown(first);
        schedule.foreground(false);
        clock.advance(1_000_000);
        schedule.foreground(true);
        schedule.finished(first);
        check(schedule.tryBegin(true, true) == 0, "time in ad must not accumulate");
        clock.advance(75_000);
        schedule.foreground(false);
        clock.advance(1_000_000);
        schedule.foreground(true);
        check(schedule.tryBegin(true, true) == 0, "background must not count");
        clock.advance(75_000);
        long second = schedule.tryBegin(true, true);
        check(second != 0 && second != first, "second display");
        schedule.finished(first);
        check(schedule.tryBegin(true, true) == 0, "stale dismissal");
        schedule.finished(second); // SDK failed before onAdShown.
        check(schedule.tryBegin(true, true) == 0, "failure retry loop");
        clock.advance(29_999);
        check(schedule.tryBegin(true, true) == 0, "retry too early");
        clock.advance(1);
        long third = schedule.tryBegin(true, true);
        check(third != 0, "retry after active cooldown");
        schedule.shown(second);
        schedule.finished(third);
        clock.advance(30_000);
        long fourth = schedule.tryBegin(true, true);
        check(fourth != 0, "stale shown callback must not reset budget");
        schedule.shown(fourth);
        schedule.finished(fourth);
        clock.advance(1_000_000);
        long fifth = schedule.tryBegin(true, true);
        check(fifth != 0, "long session opportunity");
        schedule.shown(fifth);
        schedule.finished(fifth);
        check(schedule.tryBegin(true, true) == 0, "no backlog after long session");
        schedule.finished(0);
        schedule.shown(0);
        System.out.println("Ad schedule: boundaries, background, no-fill, retry, stale callbacks and no backlog passed");
    }
}
