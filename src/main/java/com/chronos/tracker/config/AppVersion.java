package com.chronos.tracker.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Versão do Chronos, a mesma do {@code <version>} do pom.xml. */
public final class AppVersion {

    private static final String CURRENT = read();

    private AppVersion() {
    }

    public static String current() {
        return CURRENT;
    }

    private static String read() {
        try (InputStream in = AppVersion.class.getResourceAsStream("/com/chronos/tracker/version.properties")) {
            if (in == null) {
                return "dev";
            }
            Properties properties = new Properties();
            properties.load(in);
            String version = properties.getProperty("version", "dev").replace("-SNAPSHOT", "");
            return version.startsWith("${") ? "dev" : version;
        } catch (IOException e) {
            return "dev";
        }
    }

    /**
     * Se {@code candidate} é mais nova que {@code installed}, comparando número a número ("0.10.0" é mais nova
     * que "0.9.3"). Um "v" na frente é ignorado; versão "dev" nunca é considerada instalada.
     */
    public static boolean isNewer(String candidate, String installed) {
        int[] a = numbers(candidate);
        int[] b = numbers(installed);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) {
                return x > y;
            }
        }
        return false;
    }

    private static int[] numbers(String version) {
        String clean = version.strip().replaceFirst("^[vV]", "").replaceAll("[-+].*$", "");
        if (clean.isEmpty() || !clean.matches("[0-9.]+")) {
            return new int[0];
        }
        return java.util.Arrays.stream(clean.split("\\.")).filter(p -> !p.isEmpty()).mapToInt(Integer::parseInt).toArray();
    }
}
