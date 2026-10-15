package com.smartqueue.db;

import com.smartqueue.util.Log;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * JDBC access layer for ORACLE: a small connection pool, transactions and helpers.
 * EVERY statement is a PreparedStatement with bound parameters (SQL-injection safe).
 *
 * Oracle specifics handled here (so the rest of the application stays database-neutral):
 *  - paging / "top N" with the classic ROWNUM idiom  (works on Oracle 11g ... 23ai)
 *  - generated keys are fetched by column name ("ID") because Oracle would otherwise return a ROWID
 *  - explicitly typed parameter binding (also for NULLs, which matters for Oracle batch inserts)
 *  - column labels come back in UPPER CASE -> converted to the camelCase names used by the app
 *  - NUMBER(1) columns are the booleans of the schema, DATE/TIMESTAMP are mapped to java.time
 */
public final class Database {

    private static String url;
    private static String user;
    private static String password;
    private static Semaphore permits;
    private static final Deque<Connection> IDLE = new ArrayDeque<>();
    private static final ThreadLocal<Connection> TX = new ThreadLocal<>();

    private Database() {
    }

    public static void init(String jdbcUrl, String dbUser, String dbPassword, int poolSize) {
        // Oracle JDBC: send the time zone as a GMT offset instead of a region name. Some Oracle servers do not
        // know every region name the JVM uses and would answer ORA-01882 "timezone region not found".
        if (System.getProperty("oracle.jdbc.timezoneAsRegion") == null) {
            System.setProperty("oracle.jdbc.timezoneAsRegion", "false");
        }
        DriverManager.setLoginTimeout(15);
        url = jdbcUrl;
        user = dbUser;
        password = dbPassword;
        permits = new Semaphore(Math.max(2, poolSize), true);
        // fail fast if the database is unreachable
        try (Connection c = DriverManager.getConnection(url, user, password)) {
            if (!c.isValid(5)) {
                throw new SQLException("Connection is not valid");
            }
        } catch (SQLException e) {
            throw new DbException("Cannot connect to the database: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // Pool
    // ------------------------------------------------------------------
    private static Connection acquire() throws SQLException {
        try {
            if (!permits.tryAcquire(15, TimeUnit.SECONDS)) {
                throw new SQLException("Database busy: no free connection");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while waiting for a connection");
        }
        try {
            while (true) {
                Connection c;
                synchronized (IDLE) {
                    c = IDLE.pollFirst();
                }
                if (c == null) {
                    Connection created = DriverManager.getConnection(url, user, password);
                    created.setAutoCommit(true);
                    return created;
                }
                boolean ok;
                try {
                    ok = !c.isClosed() && c.isValid(2);
                } catch (SQLException e) {
                    ok = false;
                }
                if (ok) {
                    return c;
                }
                closeQuietly(c);
            }
        } catch (SQLException | RuntimeException e) {
            permits.release();
            throw e;
        }
    }

    private static void release(Connection c) {
        try {
            if (!c.isClosed()) {
                if (!c.getAutoCommit()) {
                    try {
                        c.rollback();
                    } catch (SQLException ignored) {
                        // ignore
                    }
                    c.setAutoCommit(true);
                }
                synchronized (IDLE) {
                    IDLE.addFirst(c);
                }
            }
        } catch (SQLException e) {
            closeQuietly(c);
        } finally {
            permits.release();
        }
    }

    private static void closeQuietly(Connection c) {
        try {
            c.close();
        } catch (SQLException ignored) {
            // ignore
        }
    }

    public static void shutdown() {
        synchronized (IDLE) {
            for (Connection c : IDLE) {
                closeQuietly(c);
            }
            IDLE.clear();
        }
    }

    // ------------------------------------------------------------------
    // Functional helper
    // ------------------------------------------------------------------
    private interface SqlFn<T> {
        T apply(Connection c) throws SQLException;
    }

    private static <T> T withConnection(String sql, SqlFn<T> fn) {
        Connection tx = TX.get();
        try {
            if (tx != null) {
                return fn.apply(tx);
            }
            Connection c = acquire();
            try {
                return fn.apply(c);
            } finally {
                release(c);
            }
        } catch (SQLException e) {
            throw new DbException("SQL error: " + e.getMessage() + "  [" + shorten(sql) + "]", e);
        }
    }

    private static String shorten(String sql) {
        String s = sql.replaceAll("\\s+", " ").trim();
        return s.length() > 160 ? s.substring(0, 160) + "..." : s;
    }

    // ------------------------------------------------------------------
    // Transactions
    // ------------------------------------------------------------------

    /** Runs the work in one transaction (joins an existing one if already inside a transaction). */
    public static <T> T tx(Callable<T> work) {
        if (TX.get() != null) {
            return callUnchecked(work);
        }
        Connection c;
        try {
            c = acquire();
            c.setAutoCommit(false);
        } catch (SQLException e) {
            throw new DbException("Cannot start transaction: " + e.getMessage(), e);
        }
        TX.set(c);
        try {
            T result = work.call();
            c.commit();
            return result;
        } catch (Exception e) {
            try {
                c.rollback();
            } catch (SQLException re) {
                Log.warn("Rollback failed: " + re.getMessage());
            }
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new DbException("Transaction failed: " + e.getMessage(), e);
        } finally {
            TX.remove();
            release(c);
        }
    }

    private static <T> T callUnchecked(Callable<T> work) {
        try {
            return work.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new DbException(e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------
    public static List<Row> query(String sql, Object... params) {
        return withConnection(sql, c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setFetchSize(FETCH_SIZE);   // Oracle's default is only 10 rows per round trip
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    return readAll(rs);
                }
            }
        });
    }

    public static Row queryOne(String sql, Object... params) {
        List<Row> rows = query(sql, params);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Paging: rows offset+1 ... offset+limit of the (ordered) query.  Oracle 11g-compatible ROWNUM idiom
     * (Oracle 12c+ could use OFFSET/FETCH, but this form works everywhere).
     */
    public static List<Row> queryPage(String sql, int offset, int limit, Object... params) {
        Object[] p = Arrays.copyOf(params, params.length + 2);
        p[params.length] = (long) offset + (long) limit;
        p[params.length + 1] = (long) offset;
        return query("SELECT * FROM (SELECT q_.*, ROWNUM rn_ FROM (" + sql + ") q_ WHERE ROWNUM <= ?) WHERE rn_ > ?", p);
    }

    /** "Top N": the first {@code limit} rows of the (ordered) query. */
    public static List<Row> queryTop(String sql, int limit, Object... params) {
        Object[] p = Arrays.copyOf(params, params.length + 1);
        p[params.length] = (long) limit;
        return query("SELECT * FROM (" + sql + ") WHERE ROWNUM <= ?", p);
    }

    /** The first row of the (ordered) query, or null. */
    public static Row queryFirst(String sql, Object... params) {
        List<Row> rows = queryTop(sql, 1, params);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public static long scalarLong(String sql, Object... params) {
        Row r = queryOne(sql, params);
        if (r == null || r.isEmpty()) {
            return 0L;
        }
        return r.lng(r.keySet().iterator().next());
    }

    public static int update(String sql, Object... params) {
        return withConnection(sql, c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, params);
                return ps.executeUpdate();
            }
        });
    }

    /**
     * INSERT and return the primary key (column ID). The statement itself takes the id from the table's
     * sequence, e.g. INSERT INTO users (id, ...) VALUES (users_seq.NEXTVAL, ...).
     */
    public static long insert(String sql, Object... params) {
        return withConnection(sql, c -> {
            try (PreparedStatement ps = c.prepareStatement(sql, new String[]{"ID"})) {
                bind(ps, params);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        return keys.getLong(1);
                    }
                }
                return 0L;
            }
        });
    }

    /** Executes the same statement for many parameter sets (used by the demo-data seeder). */
    public static void batch(String sql, List<Object[]> paramSets) {
        if (paramSets.isEmpty()) {
            return;
        }
        // Oracle needs the same SQL type for a column in every row of a batch, also for NULLs:
        // find a real value for every column and use its type for the NULLs of that column.
        int cols = paramSets.get(0).length;
        int[] nullTypes = new int[cols];
        Arrays.fill(nullTypes, Types.VARCHAR);
        for (int i = 0; i < cols; i++) {
            for (Object[] row : paramSets) {
                if (row[i] != null) {
                    nullTypes[i] = sqlTypeOf(row[i]);
                    break;
                }
            }
        }
        withConnection(sql, c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                int n = 0;
                for (Object[] params : paramSets) {
                    for (int i = 0; i < params.length; i++) {
                        setParam(ps, i + 1, params[i], nullTypes[i]);
                    }
                    ps.addBatch();
                    if (++n % 500 == 0) {
                        ps.executeBatch();
                    }
                }
                ps.executeBatch();
                return null;
            }
        });
    }

    /** Execute a raw DDL statement (schema creation only - never with user input). */
    public static void execute(String ddl) {
        withConnection(ddl, c -> {
            try (Statement st = c.createStatement()) {
                st.execute(ddl);
                return null;
            }
        });
    }

    /**
     * Executes a CREATE statement unless the object already exists
     * (Oracle ORA-00955 "name is already used by an existing object", ORA-01408 "column list already indexed").
     *
     * @return true when the object was created, false when it was already there
     */
    public static boolean createIfMissing(String ddl) {
        Connection c = null;
        try {
            c = acquire();
            try (Statement st = c.createStatement()) {
                st.execute(ddl);
                return true;
            }
        } catch (SQLException e) {
            String msg = String.valueOf(e.getMessage());
            if (e.getErrorCode() == 955 || e.getErrorCode() == 1408 || msg.contains("ORA-00955") || msg.contains("ORA-01408")
                    || msg.toLowerCase(Locale.ROOT).contains("already exists")) {
                return false;
            }
            throw new DbException("SQL error: " + e.getMessage() + "  [" + shorten(ddl) + "]", e);
        } finally {
            if (c != null) {
                release(c);
            }
        }
    }

    // ------------------------------------------------------------------
    // Binding / reading
    // ------------------------------------------------------------------
    private static final int FETCH_SIZE = 200;

    private static void bind(PreparedStatement ps, Object[] params) throws SQLException {
        if (params == null) {
            return;
        }
        for (int i = 0; i < params.length; i++) {
            setParam(ps, i + 1, params[i], Types.VARCHAR);
        }
    }

    /** JDBC type used to bind a NULL for a column that otherwise holds this kind of value. */
    private static int sqlTypeOf(Object p) {
        if (p instanceof Boolean || p instanceof Number) {
            return Types.NUMERIC;
        }
        if (p instanceof LocalDateTime || p instanceof java.sql.Timestamp) {
            return Types.TIMESTAMP;
        }
        if (p instanceof LocalDate || p instanceof java.sql.Date) {
            return Types.DATE;
        }
        return Types.VARCHAR;
    }

    /** Explicitly typed binding (works the same on every Oracle JDBC driver version). */
    private static void setParam(PreparedStatement ps, int idx, Object p, int nullType) throws SQLException {
        if (p == null) {
            ps.setNull(idx, nullType);
        } else if (p instanceof String) {
            ps.setString(idx, (String) p);
        } else if (p instanceof Boolean) {
            ps.setInt(idx, ((Boolean) p) ? 1 : 0);
        } else if (p instanceof Long) {
            ps.setLong(idx, (Long) p);
        } else if (p instanceof Integer || p instanceof Short || p instanceof Byte) {
            ps.setInt(idx, ((Number) p).intValue());
        } else if (p instanceof Double || p instanceof Float) {
            double d = ((Number) p).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                ps.setNull(idx, Types.NUMERIC);
            } else {
                ps.setBigDecimal(idx, BigDecimal.valueOf(d));
            }
        } else if (p instanceof BigDecimal) {
            ps.setBigDecimal(idx, (BigDecimal) p);
        } else if (p instanceof Enum) {
            ps.setString(idx, ((Enum<?>) p).name());
        } else if (p instanceof LocalDateTime) {
            ps.setTimestamp(idx, java.sql.Timestamp.valueOf((LocalDateTime) p));
        } else if (p instanceof LocalDate) {
            ps.setDate(idx, java.sql.Date.valueOf((LocalDate) p));
        } else {
            ps.setObject(idx, p);
        }
    }

    private static List<Row> readAll(ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int cols = md.getColumnCount();
        String[] labels = new String[cols];
        String[] names = new String[cols];
        int[] types = new int[cols];
        String[] typeNames = new String[cols];
        int[] precisions = new int[cols];
        int[] scales = new int[cols];
        boolean[] skip = new boolean[cols];
        for (int i = 0; i < cols; i++) {
            // Oracle returns column labels in UPPER CASE (TOKEN_CODE) -> token_code -> tokenCode
            labels[i] = md.getColumnLabel(i + 1).toLowerCase(Locale.ROOT);
            names[i] = camel(labels[i]);
            types[i] = md.getColumnType(i + 1);
            typeNames[i] = String.valueOf(md.getColumnTypeName(i + 1)).toUpperCase(Locale.ROOT);
            precisions[i] = md.getPrecision(i + 1);
            scales[i] = md.getScale(i + 1);
            skip[i] = labels[i].equals("rn_");           // helper column of the ROWNUM paging idiom
        }
        List<Row> rows = new ArrayList<>();
        while (rs.next()) {
            Row row = new Row();
            for (int i = 0; i < cols; i++) {
                if (!skip[i]) {
                    row.put(names[i], readValue(rs, i + 1, labels[i], types[i], typeNames[i], precisions[i], scales[i]));
                }
            }
            rows.add(row);
        }
        return rows;
    }

    private static Object readValue(ResultSet rs, int idx, String label, int type, String typeName, int precision, int scale)
            throws SQLException {
        switch (type) {
            case Types.TIMESTAMP:
            case Types.TIMESTAMP_WITH_TIMEZONE:
            case -101:      // Oracle TIMESTAMP WITH TIME ZONE
            case -102:      // Oracle TIMESTAMP WITH LOCAL TIME ZONE
            case Types.DATE: {
                java.sql.Timestamp t = rs.getTimestamp(idx);
                if (t == null) {
                    return null;
                }
                // Oracle's DATE type always carries a time part; the schema uses it for "..._date" columns only
                if (type == Types.DATE || typeName.equals("DATE") || label.endsWith("_date")) {
                    return t.toLocalDateTime().toLocalDate();
                }
                return t.toLocalDateTime();
            }
            case Types.BIT:
            case Types.BOOLEAN: {
                Object b = rs.getObject(idx);
                if (b == null) {
                    return null;
                }
                if (b instanceof Boolean) {
                    return b;
                }
                if (b instanceof Number) {
                    return ((Number) b).intValue() != 0;
                }
                return b;
            }
            case Types.DECIMAL:
            case Types.NUMERIC: {
                BigDecimal d = rs.getBigDecimal(idx);
                if (d == null) {
                    return null;
                }
                if (precision == 1 && scale == 0) {
                    return d.signum() != 0;               // NUMBER(1) = boolean flag of the schema
                }
                if (scale <= 0) {                         // whole numbers (ids, counters, COUNT(*), SUM(...))
                    BigDecimal st = d.stripTrailingZeros();
                    if (st.scale() <= 0 && st.precision() - st.scale() <= 18) {
                        return d.longValue();
                    }
                }
                return d;
            }
            default: {
                Object o = rs.getObject(idx);
                if (o instanceof byte[]) {
                    return rs.getString(idx);
                }
                if (o instanceof java.sql.Timestamp) {
                    return ((java.sql.Timestamp) o).toLocalDateTime();
                }
                if (o instanceof java.sql.Date) {
                    return ((java.sql.Date) o).toLocalDate();
                }
                if (o instanceof java.sql.Clob) {
                    return rs.getString(idx);
                }
                if (o instanceof BigDecimal) {
                    BigDecimal st = ((BigDecimal) o).stripTrailingZeros();
                    return st.scale() <= 0 && st.precision() - st.scale() <= 18 ? (Object) ((BigDecimal) o).longValue() : o;
                }
                return o;
            }
        }
    }

    static String camel(String snake) {
        if (snake.indexOf('_') < 0) {
            return snake;
        }
        StringBuilder sb = new StringBuilder(snake.length());
        boolean up = false;
        for (int i = 0; i < snake.length(); i++) {
            char c = snake.charAt(i);
            if (c == '_') {
                up = true;
            } else if (up) {
                sb.append(Character.toUpperCase(c));
                up = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
