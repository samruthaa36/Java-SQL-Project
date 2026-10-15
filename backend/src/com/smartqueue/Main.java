package com.smartqueue;

import com.smartqueue.config.AppConfig;
import com.smartqueue.db.Database;
import com.smartqueue.db.DbException;
import com.smartqueue.db.DemoDataSeeder;
import com.smartqueue.db.SchemaInitializer;
import com.smartqueue.http.HttpApp;
import com.smartqueue.service.BackgroundJobs;
import com.smartqueue.service.QueueService;
import com.smartqueue.service.Settings;
import com.smartqueue.util.Log;
import com.smartqueue.util.Times;

import java.net.BindException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Enumeration;

/** SmartQueue - Smart Queue & Crowd Management System. Application entry point. */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        AppConfig.load();

        String tz = AppConfig.get("app.timezone", null);
        if (tz != null) {
            try {
                Times.setZone(ZoneId.of(tz));
            } catch (RuntimeException e) {
                Log.warn("Unknown time zone '" + tz + "', using " + Times.zone());
            }
        }

        String host = AppConfig.get("db.host", "localhost");
        String port = AppConfig.get("db.port", "1521");
        String service = AppConfig.get("db.service", "XEPDB1");
        String sid = AppConfig.get("db.sid", null);
        String user = AppConfig.get("db.user", "smartqueue");
        String password = AppConfig.get("db.password", "");
        String customUrl = AppConfig.get("db.url", null);
        String url;
        String target;
        if (customUrl != null) {
            url = customUrl;
            target = "custom url";
        } else if (sid != null) {
            url = "jdbc:oracle:thin:@" + host + ":" + port + ":" + sid;                 // old style: SID
            target = host + ":" + port + " (SID " + sid + ")";
        } else {
            url = "jdbc:oracle:thin:@//" + host + ":" + port + "/" + service;           // service name
            target = host + ":" + port + "/" + service;
        }

        printBanner();
        try {
            Database.init(url, user, password, AppConfig.getInt("db.pool-size", 12));
            Log.info("Connected to Oracle Database (" + target + ") as " + user);
            SchemaInitializer.createTables();
            Settings.load();
            DemoDataSeeder.seedIfEmpty(AppConfig.getBool("app.seed-demo-data", false));
            QueueService.expireStale();
        } catch (DbException e) {
            fail("Could not start the database layer", e);
            return;
        } catch (RuntimeException e) {
            fail("Start-up problem", e);
            return;
        }

        Path frontend = locateFrontend();
        int serverPort = AppConfig.getInt("server.port", 8080);
        String bind = AppConfig.get("server.host", "0.0.0.0");
        HttpApp app = new HttpApp(frontend);
        try {
            app.start(bind, serverPort, AppConfig.getInt("server.threads", 32));
        } catch (BindException e) {
            Log.error("Port " + serverPort + " is already in use. Close the other program or change server.port in config/application.properties.", null);
            Database.shutdown();
            System.exit(1);
            return;
        } catch (Exception e) {
            Log.error("Could not start the web server", e);
            System.exit(1);
            return;
        }
        BackgroundJobs.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            BackgroundJobs.stop();
            app.stop();
            Database.shutdown();
        }));

        System.out.println();
        System.out.println("  SmartQueue is running");
        System.out.println("  --------------------------------------------");
        System.out.println("  Open in this computer : http://localhost:" + serverPort + "/");
        String lan = lanAddress();
        if (lan != null && !bind.equals("127.0.0.1") && !bind.equals("localhost")) {
            System.out.println("  Open on your phone    : http://" + lan + ":" + serverPort + "/   (same Wi-Fi)");
        }
        System.out.println("  Lobby display board   : http://localhost:" + serverPort + "/display.html");
        System.out.println("  --------------------------------------------");
        System.out.println("  Select your role when opening SmartQueue:");
        System.out.println("    Customer | Counter Staff | Administrator");
        System.out.println("  Sign in or create a real account in your browser.");
        System.out.println("  Press Ctrl+C to stop.");
        System.out.println();
    }

    private static void fail(String title, Throwable e) {
        Log.error(title + ": " + e.getMessage(), null);
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String msg = String.valueOf(root.getMessage());
        System.err.println();
        String low = msg.toLowerCase();
        if (root instanceof SQLException && (msg.contains("No suitable driver") || msg.contains("ClassNotFound"))) {
            System.err.println("  The Oracle JDBC driver is missing. Download ojdbc8.jar (or ojdbc11.jar) from oracle.com and put it in the lib/ folder.");
        } else if (msg.contains("ORA-01017")) {
            System.err.println("  Oracle refused the user name or password. Edit db.user / db.password in config/application.properties.");
            System.err.println("  (The user is created once with database/oracle-setup.sql.)");
        } else if (msg.contains("ORA-12514") || msg.contains("ORA-12505") || msg.contains("ORA-12154")) {
            System.err.println("  The listener does not know this service name. Check db.service in config/application.properties");
            System.err.println("  (Oracle XE 11g: XE   |   XE 18c/21c: XEPDB1   |   Oracle 23ai Free: FREEPDB1   |   or set db.sid).");
        } else if (msg.contains("ORA-12541") || low.contains("connection refused") || low.contains("the network adapter could not establish")) {
            System.err.println("  Cannot reach Oracle. Is the database (and its TNS listener) running on db.host / db.port (default localhost:1521)?");
        } else if (msg.contains("ORA-01950") || msg.contains("ORA-01536")) {
            System.err.println("  The database user has no space quota. Run:  ALTER USER <user> QUOTA UNLIMITED ON USERS;");
        } else if (msg.contains("ORA-01031")) {
            System.err.println("  The database user lacks privileges. Run (as SYSTEM):  GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE TO <user>;");
        } else {
            System.err.println("  " + msg);
        }
        System.err.println();
        System.exit(1);
    }

    private static Path locateFrontend() {
        String custom = AppConfig.get("app.frontend-dir", null);
        Path[] candidates = custom != null
                ? new Path[]{Paths.get(custom)}
                : new Path[]{AppConfig.baseDir().resolve("frontend"), Paths.get("frontend"), Paths.get("..", "frontend")};
        for (Path p : candidates) {
            if (Files.isDirectory(p) && Files.isRegularFile(p.resolve("index.html"))) {
                return p;
            }
        }
        throw new IllegalStateException("The 'frontend' folder (with index.html) was not found. Start the application from the SmartQueue folder.");
    }

    private static String lanAddress() {
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            if (nics == null) {
                return null;
            }
            for (NetworkInterface nic : Collections.list(nics)) {
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) {
                    continue;
                }
                for (InetAddress a : Collections.list(nic.getInetAddresses())) {
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress() && a.isSiteLocalAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
            // not important
        }
        return null;
    }

    private static void printBanner() {
        System.out.println();
        System.out.println("  ==========================================");
        System.out.println("   SmartQueue  -  Smart Queue & Crowd Mgmt");
        System.out.println("  ==========================================");
    }
}
