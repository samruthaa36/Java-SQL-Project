package com.smartqueue.db;

import com.smartqueue.config.AppConfig;
import com.smartqueue.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Creates the sequences, tables and indexes of database/schema.sql inside the Oracle schema (user) the application
 * connects with. Safe to run on every start: objects that already exist are skipped.
 */
public final class SchemaInitializer {

    private SchemaInitializer() {
    }

    public static void createTables() {
        Path file = locateSchema();
        String sql;
        try {
            sql = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + file + ": " + e.getMessage(), e);
        }
        int created = 0;
        int existing = 0;
        for (String stmt : split(sql)) {
            if (Database.createIfMissing(stmt)) {
                created++;
            } else {
                existing++;
            }
        }
        if (created > 0) {
            Log.info("Oracle schema ready: " + created + " objects created, " + existing + " already existed (" + file.getFileName() + ")");
        } else {
            Log.info("Oracle schema verified: all " + existing + " objects exist (" + file.getFileName() + ")");
        }
    }

    private static Path locateSchema() {
        String custom = AppConfig.get("app.schema-file", null);
        if (custom != null) {
            return Paths.get(custom);
        }
        Path p = AppConfig.baseDir().resolve("database").resolve("schema.sql");
        if (Files.isRegularFile(p)) {
            return p;
        }
        Path q = Paths.get("database", "schema.sql");
        if (Files.isRegularFile(q)) {
            return q;
        }
        throw new IllegalStateException("database/schema.sql was not found. Run the application from the SmartQueue folder.");
    }

    private static List<String> split(String sql) {
        StringBuilder cleaned = new StringBuilder();
        for (String line : sql.split("\\r?\\n")) {
            String t = line.trim();
            if (t.startsWith("--") || t.isEmpty()) {
                continue;
            }
            cleaned.append(line).append('\n');
        }
        List<String> out = new ArrayList<>();
        for (String part : cleaned.toString().split(";\\s*\\n")) {
            String s = part.trim();
            if (s.endsWith(";")) {
                s = s.substring(0, s.length() - 1).trim();
            }
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }
}
