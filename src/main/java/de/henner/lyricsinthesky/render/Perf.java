package de.henner.lyricsinthesky.render;

/**
 * How long the mod takes per frame, for the self and live tests only (off in normal play, costing nothing): laying out the
 * lyrics, drawing them, and the now-playing bar. Read and reset with {@link #report()}.
 */
public final class Perf {
    public static final int LAYOUT = 0;
    public static final int DRAW = 1;
    public static final int BAR = 2;
    private static final String[] NAMES = {"Aufbau", "Zeichnen", "Leiste"};
    private static final boolean ON = Boolean.getBoolean("lyricsinthesky.selftest")
            || Integer.getInteger("lyricsinthesky.livetest", 0) > 0;
    private static final long[] TOTAL = new long[NAMES.length];
    private static final long[] WORST = new long[NAMES.length];
    private static final int[] COUNT = new int[NAMES.length];

    private Perf() {}

    public static long start() {
        return ON ? System.nanoTime() : 0L;
    }

    public static void stop(int part, long started) {
        if (ON) {
            long took = System.nanoTime() - started;
            TOTAL[part] += took;
            WORST[part] = Math.max(WORST[part], took);
            COUNT[part]++;
        }
    }

    /** "Aufbau 0.21 ms (worst 0.9, 412×), …" since the last report; starts counting anew. */
    public static synchronized String report() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < NAMES.length; i++) {
            double avg = COUNT[i] == 0 ? 0 : TOTAL[i] / 1e6 / COUNT[i];
            out.append(i == 0 ? "" : ", ").append(String.format(java.util.Locale.ROOT, "%s %.3f ms (worst %.2f, %d×)",
                    NAMES[i], avg, WORST[i] / 1e6, COUNT[i]));
            TOTAL[i] = 0;
            WORST[i] = 0;
            COUNT[i] = 0;
        }
        return out.toString();
    }
}
