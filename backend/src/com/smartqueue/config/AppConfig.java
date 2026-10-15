package com.smartqueue.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Application configuration.
 * Order of precedence: environment variable (SQ_DB_PASSWORD ...) > -D system property >
 * config/application.properties > built-in default.
 */
public final class AppConfig {

    private static final Properties PROPS = new Properties();
    private static Path baseDir = Paths.get("").toAbsolutePath();

    private AppConfig() {
    }

    public static void load() {
        String custom = System.getProperty("smartqueue.config");
        Path file = null;
        if (custom != null && !custom.isEmpty()) {
            file = Paths.get(custom);
        } else {
            for (String candidate : new String[]{"config/application.properties", "../config/application.properties"}) {
                Path p = Paths.get(candidate);
                if (Files.isRegularFile(p)) {
                    file = p;
                    break;
                }
            }
        }
        if (file != null && Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                PROPS.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
                Path abs = file.toAbsolutePath().normalize();
                // project root = folder that contains the "config" directory
                Path parent = abs.getParent();
                if (parent != null && parent.getParent() != null) {
                    baseDir = parent.getParent();
                }
            } catch (IOException e) {
                System.err.println("Could not read " + file + ": " + e.getMessage());
            }
        }
    }

    /** Project root folder (the folder that contains config/, database/ and frontend/). */
    public static Path baseDir() {
        return baseDir;
    }

    public static String get(String key, String def) {
        String env = System.getenv("SQ_" + key.toUpperCase().replace('.', '_').replace('-', '_'));
        if (env != null && !env.isEmpty()) {
            return env;
        }
        String sys = System.getProperty(key);
        if (sys != null && !sys.isEmpty()) {
            return sys;
        }
        String v = PROPS.getProperty(key);
        if (v != null && !v.trim().isEmpty()) {
            return v.trim();
        }
        return def;
    }

    public static int getInt(String key, int def) {
        try {
            return Integer.parseInt(get(key, String.valueOf(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static boolean getBool(String key, boolean def) {
        String v = get(key, String.valueOf(def)).trim().toLowerCase();
        return v.equals("true") || v.equals("yes") || v.equals("1") || v.equals("on");
    }
}
