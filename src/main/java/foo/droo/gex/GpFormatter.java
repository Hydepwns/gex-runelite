package foo.droo.gex;

public final class GpFormatter {

    private GpFormatter() {}

    public static String format(long value) {
        String prefix = value >= 0 ? "+" : "";
        long abs = Math.abs(value);

        if (abs >= 1_000_000) {
            return prefix + String.format("%.1fM", value / 1_000_000.0);
        } else if (abs >= 1_000) {
            return prefix + String.format("%.1fK", value / 1_000.0);
        }
        return prefix + value;
    }
}
