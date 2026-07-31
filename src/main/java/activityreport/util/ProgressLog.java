package activityreport.util;

public class ProgressLog {

    private static int depth = 0;

    private static final boolean COLORS;
    static {
        if (System.getenv("NO_COLOR") != null) {
            COLORS = false;
        } else if ("true".equals(System.getenv("FORCE_COLOR"))) {
            COLORS = true;
        } else {
            COLORS = System.console() != null;
        }
    }

    static final String RESET = "\033[0m";
    static final String BOLD = "\033[1m";
    static final String DIM = "\033[2m";
    static final String CYAN = "\033[36m";
    static final String GREEN = "\033[32m";
    static final String YELLOW = "\033[33m";
    static final String RED = "\033[31m";

    @FunctionalInterface
    public interface Block {
        void run() throws Exception;
    }

    public static void indented(Block body) throws Exception {
        depth++;
        try {
            body.run();
        } finally {
            if (depth > 0) depth--;
        }
    }

    static int getDepth() {
        return depth;
    }

    static void resetDepth() {
        depth = 0;
    }

    static String formatLine(boolean colors, int level, String prefix, String colorCode,
                             String format, Object... args) {
        String message = args.length > 0 ? String.format(format, args) : format;
        String indent = "  ".repeat(level);
        String c = colors && !colorCode.isEmpty() ? colorCode : "";
        String r = colors && !colorCode.isEmpty() ? RESET : "";
        if (prefix.isEmpty()) {
            return indent + c + message + r;
        }
        return indent + c + prefix + " " + message + r;
    }

    public static void blank() {
        System.out.println();
    }

    public static void header(String message) {
        String c = COLORS ? BOLD : "";
        String r = COLORS ? RESET : "";
        System.out.println(c + message + r);
    }

    public static void section(String format, Object... args) {
        System.out.println(formatLine(COLORS, depth, "▶", BOLD + CYAN, format, args));
    }

    public static void detail(String format, Object... args) {
        System.out.println(formatLine(COLORS, depth, "", DIM, format, args));
    }

    public static void result(String format, Object... args) {
        System.out.println(formatLine(COLORS, depth, "✓", GREEN, format, args));
    }

    public static void resultHighlight(String format, Object... args) {
        System.out.println(formatLine(COLORS, depth, "✓", BOLD + GREEN, format, args));
    }

    public static void info(String format, Object... args) {
        System.out.println(formatLine(false, depth, "", "", format, args));
    }

    public static void warn(String format, Object... args) {
        System.out.println(formatLine(COLORS, depth, "⚠", YELLOW, format, args));
    }

    public static void error(String format, Object... args) {
        System.out.println(formatLine(COLORS, depth, "✗", RED, format, args));
    }

    public static void error(String message, Throwable t) {
        if (!message.isEmpty()) {
            System.out.println(formatLine(COLORS, depth, "✗", RED, message));
        }
        t.printStackTrace(System.out);
    }
}
