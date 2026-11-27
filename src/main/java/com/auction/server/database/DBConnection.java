package com.auction.server.database;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

/**
 * DBConnection: Quản lý kết nối Cơ sở dữ liệu tập trung hỗ trợ Cloud Database dùng chung
 * (PostgreSQL trên Neon/Supabase, MySQL trên TiDB Cloud/Aiven) lẫn SQLite cục bộ bền vững (~/.bidion/auction.db)
 * và SQLite in-memory cho Unit Test (Yêu cầu 3.4 & Thành viên 4).
 *
 * Thứ tự tự động nạp cấu hình:
 * 1. Tham số JVM: -Dauction.db.url=... -Dauction.db.user=... -Dauction.db.password=...
 * 2. Biến môi trường: AUCTION_DB_URL (hoặc DATABASE_URL), AUCTION_DB_USER, AUCTION_DB_PASSWORD
 * 3. Tệp cấu hình Cloud DB: ./db.properties hoặc ~/.bidion/db.properties (không commit lên Git)
 * 4. Tham số đường dẫn SQLite riêng: -Dauction.db.path=... hoặc AUCTION_DB_PATH
 * 5. Mặc định dự phòng: SQLite bền vững tại ~/.bidion/auction.db
 */
public class DBConnection {

    public enum Dialect {
        SQLITE,
        POSTGRESQL,
        MYSQL
    }

    public record DatabaseConfig(String jdbcUrl, String username, String password, Dialect dialect) {
        public boolean isCloudDatabase() {
            return dialect != Dialect.SQLITE;
        }

        public String safeDisplayUrl() {
            if (jdbcUrl == null) {
                return "";
            }
            return jdbcUrl.replaceAll("(?i)(password=)[^&]+", "$1***");
        }
    }

    public static final String APP_DATA_DIR_NAME = ".bidion";
    public static final String DB_FILE_NAME = "auction.db";
    public static final String DEFAULT_CONFIG_FILE = "db.properties";
    public static final String DEFAULT_DB_URL = buildDefaultJdbcUrl();

    private static volatile DBConnection instance;
    private static final Object LOCK = new Object();

    private final DatabaseConfig config;
    private final boolean defaultPersistentSqlite;
    private Connection rawConnection;
    private Connection sharedConnectionProxy;

    private DBConnection() {
        this(resolveDefaultConfig(), isUsingDefaultPersistentSqlite());
    }

    private DBConnection(DatabaseConfig config, boolean defaultPersistentSqlite) {
        this.config = config;
        this.defaultPersistentSqlite = defaultPersistentSqlite && config.dialect() == Dialect.SQLITE;
        if (config.dialect() == Dialect.SQLITE) {
            ensureParentDirectoryExists(config.jdbcUrl());
        }
        initConnection();
        initSchema();
        if (this.defaultPersistentSqlite) {
            migrateLegacyLocalDatabases();
        }
    }

    private static String buildDefaultJdbcUrl() {
        Path homeDir = Paths.get(System.getProperty("user.home", ".")).toAbsolutePath().normalize();
        Path dbFile = homeDir.resolve(APP_DATA_DIR_NAME).resolve(DB_FILE_NAME);
        return "jdbc:sqlite:" + dbFile;
    }

    private static boolean isUsingDefaultPersistentSqlite() {
        if (hasCloudDbConfiguration()) {
            return false;
        }
        String sysProp = System.getProperty("auction.db.path");
        if (sysProp != null && !sysProp.isBlank()) {
            return false;
        }
        String envProp = System.getenv("AUCTION_DB_PATH");
        return envProp == null || envProp.isBlank();
    }

    private static boolean hasCloudDbConfiguration() {
        if (firstNonBlank(System.getProperty("auction.db.url"),
                System.getenv("AUCTION_DB_URL"),
                System.getenv("DATABASE_URL")) != null) {
            return true;
        }
        return readDefaultConfigFiles() != null;
    }

    public static DatabaseConfig resolveDefaultConfig() {
        // 1. JVM System Properties cho Cloud DB
        String propUrl = System.getProperty("auction.db.url");
        if (propUrl != null && !propUrl.isBlank()) {
            return normalizeConfig(
                    propUrl,
                    System.getProperty("auction.db.user"),
                    System.getProperty("auction.db.password"));
        }

        // 2. Biến môi trường Cloud DB (AUCTION_DB_URL hoặc DATABASE_URL của Neon/Supabase/TiDB)
        String envUrl = firstNonBlank(System.getenv("AUCTION_DB_URL"), System.getenv("DATABASE_URL"));
        if (envUrl != null) {
            return normalizeConfig(
                    envUrl,
                    firstNonBlank(System.getenv("AUCTION_DB_USER"), System.getenv("DATABASE_USER")),
                    firstNonBlank(System.getenv("AUCTION_DB_PASSWORD"), System.getenv("DATABASE_PASSWORD")));
        }

        // 3. File cấu hình Cloud DB cục bộ (./db.properties hoặc ~/.bidion/db.properties)
        DatabaseConfig fromFile = readDefaultConfigFiles();
        if (fromFile != null) {
            return fromFile;
        }

        // 4. Đường dẫn SQLite tùy chỉnh (nếu có)
        String customPath = firstNonBlank(System.getProperty("auction.db.path"), System.getenv("AUCTION_DB_PATH"));
        if (customPath != null) {
            String url = customPath.startsWith("jdbc:")
                    ? customPath
                    : "jdbc:sqlite:" + Paths.get(customPath).toAbsolutePath().normalize();
            return normalizeConfig(url, null, null);
        }

        // 5. Fallback mặc định: SQLite bền vững tại ~/.bidion/auction.db
        return normalizeConfig(buildDefaultJdbcUrl(), null, null);
    }

    private static DatabaseConfig readDefaultConfigFiles() {
        Path homeConfig = Paths.get(System.getProperty("user.home", "."))
                .toAbsolutePath().normalize()
                .resolve(APP_DATA_DIR_NAME)
                .resolve(DEFAULT_CONFIG_FILE);
        List<Path> candidates = List.of(
                Paths.get(DEFAULT_CONFIG_FILE),
                Paths.get("ltnc-btl", DEFAULT_CONFIG_FILE),
                homeConfig
        );
        for (Path candidate : candidates) {
            DatabaseConfig cfg = readConfigFile(candidate);
            if (cfg != null) {
                return cfg;
            }
        }
        return null;
    }

    public static DatabaseConfig readConfigFile(Path configPath) {
        if (configPath == null || !Files.isRegularFile(configPath)) {
            return null;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(configPath)) {
            props.load(in);
        } catch (IOException ignored) {
            return null;
        }
        String url = firstNonBlank(props.getProperty("db.url"), props.getProperty("url"));
        if (url == null) {
            return null;
        }
        String user = firstNonBlank(props.getProperty("db.user"), props.getProperty("db.username"), props.getProperty("user"));
        String password = firstNonBlank(props.getProperty("db.password"), props.getProperty("password"));
        return normalizeConfig(url, user, password);
    }

    /**
     * Chuẩn hóa chuỗi kết nối: hỗ trợ cả JDBC chuẩn (`jdbc:postgresql://...`, `jdbc:mysql://...`, `jdbc:sqlite:...`)
     * lẫn chuỗi URI copy trực tiếp từ Neon / Supabase / TiDB Cloud (`postgresql://user:pass@host/db?sslmode=require`).
     */
    public static DatabaseConfig normalizeConfig(String rawUrl, String explicitUser, String explicitPassword) {
        String trimmed = (rawUrl == null || rawUrl.isBlank()) ? buildDefaultJdbcUrl() : rawUrl.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        String user = explicitUser != null ? explicitUser.trim() : null;
        String pass = explicitPassword;

        if (lower.startsWith("postgres://") || lower.startsWith("postgresql://")) {
            ParsedUri parsed = parseCloudUri(trimmed, "postgresql", 5432);
            return new DatabaseConfig(
                    parsed.jdbcUrl(),
                    firstNonBlank(user, parsed.username()),
                    pass != null ? pass : parsed.password(),
                    Dialect.POSTGRESQL);
        }
        if (lower.startsWith("mysql://")) {
            ParsedUri parsed = parseCloudUri(trimmed, "mysql", 3306);
            return new DatabaseConfig(
                    parsed.jdbcUrl(),
                    firstNonBlank(user, parsed.username()),
                    pass != null ? pass : parsed.password(),
                    Dialect.MYSQL);
        }

        Dialect dialect = detectDialect(trimmed);
        return new DatabaseConfig(trimmed, user, pass, dialect);
    }

    public static Dialect detectDialect(String jdbcUrl) {
        if (jdbcUrl == null) {
            return Dialect.SQLITE;
        }
        String lower = jdbcUrl.trim().toLowerCase(Locale.ROOT);
        if (lower.startsWith("jdbc:postgresql:") || lower.startsWith("postgresql:") || lower.startsWith("postgres:")) {
            return Dialect.POSTGRESQL;
        }
        if (lower.startsWith("jdbc:mysql:") || lower.startsWith("mysql:") || lower.startsWith("jdbc:mariadb:")) {
            return Dialect.MYSQL;
        }
        return Dialect.SQLITE;
    }

    private record ParsedUri(String jdbcUrl, String username, String password) {
    }

    private static ParsedUri parseCloudUri(String rawUri, String jdbcScheme, int defaultPort) {
        String normalizedScheme = rawUri.replaceFirst("(?i)^postgres://", "postgresql://");
        URI uri = URI.create(normalizedScheme);
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Chuỗi kết nối Cloud Database không chứa tên máy chủ (host): " + rawUri);
        }
        int port = uri.getPort() > 0 ? uri.getPort() : defaultPort;
        String path = uri.getRawPath() != null ? uri.getRawPath() : "";
        String query = uri.getRawQuery();

        String extractedUser = null;
        String extractedPass = null;
        String userInfo = uri.getRawUserInfo();
        if (userInfo != null && !userInfo.isEmpty()) {
            int colon = userInfo.indexOf(':');
            if (colon >= 0) {
                extractedUser = URLDecoder.decode(userInfo.substring(0, colon), StandardCharsets.UTF_8);
                extractedPass = URLDecoder.decode(userInfo.substring(colon + 1), StandardCharsets.UTF_8);
            } else {
                extractedUser = URLDecoder.decode(userInfo, StandardCharsets.UTF_8);
            }
        }

        StringBuilder jdbc = new StringBuilder("jdbc:")
                .append(jdbcScheme).append("://")
                .append(host).append(":").append(port)
                .append(path);
        if (query != null && !query.isBlank()) {
            jdbc.append("?").append(query);
        }
        return new ParsedUri(jdbc.toString(), extractedUser, extractedPass);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }

    private static void ensureParentDirectoryExists(String url) {
        Path dbPath = extractFilePath(url);
        if (dbPath != null && dbPath.getParent() != null) {
            try {
                Files.createDirectories(dbPath.getParent());
            } catch (IOException e) {
                throw new RuntimeException("Không thể tạo thư mục lưu trữ CSDL: " + dbPath.getParent(), e);
            }
        }
    }

    private static Path extractFilePath(String url) {
        if (url == null || !url.startsWith("jdbc:sqlite:") || url.contains(":memory:")) {
            return null;
        }
        String raw = url.substring("jdbc:sqlite:".length()).trim();
        if (raw.isEmpty() || raw.startsWith("file:")) {
            return null;
        }
        return Paths.get(raw).toAbsolutePath().normalize();
    }

    /**
     * Lấy thể hiện Singleton duy nhất của DBConnection (Double-Checked Locking).
     */
    public static DBConnection getInstance() {
        if (instance == null) {
            synchronized (LOCK) {
                if (instance == null) {
                    instance = new DBConnection();
                }
            }
        }
        return instance;
    }

    /**
     * Khởi tạo thể hiện Singleton với JDBC URL tùy chỉnh (hỗ trợ cả URI Cloud DB và SQLite in-memory cho Unit Test).
     */
    public static DBConnection getInstance(String customJdbcUrl) {
        return getInstance(customJdbcUrl, null, null);
    }

    /**
     * Khởi tạo thể hiện Singleton với JDBC URL, username và password tùy chỉnh.
     */
    public static DBConnection getInstance(String customJdbcUrl, String username, String password) {
        DatabaseConfig target = normalizeConfig(customJdbcUrl, username, password);
        if (instance == null) {
            synchronized (LOCK) {
                if (instance == null) {
                    instance = new DBConnection(target, false);
                } else if (!instance.config.equals(target)) {
                    instance.close();
                    instance = new DBConnection(target, false);
                }
            }
        } else if (!instance.config.equals(target)) {
            synchronized (LOCK) {
                instance.close();
                instance = new DBConnection(target, false);
            }
        }
        return instance;
    }

    /**
     * Reset Singleton (dùng trong teardown của Unit Test).
     */
    public static void resetInstance() {
        synchronized (LOCK) {
            if (instance != null) {
                instance.close();
                instance = null;
            }
        }
    }

    /**
     * Mở hoặc lấy kết nối hiện tại tới Database (bọc proxy chống đóng nhầm từ try-with-resources,
     * đồng thời tự động reconnect nếu Cloud Database ngắt kết nối sau thời gian rảnh).
     */
    public synchronized Connection getConnection() throws SQLException {
        boolean needsReconnect = (rawConnection == null || rawConnection.isClosed());
        if (!needsReconnect && config.dialect() != Dialect.SQLITE) {
            try {
                needsReconnect = !rawConnection.isValid(3);
            } catch (SQLException ignored) {
                needsReconnect = true;
            }
        }
        if (needsReconnect) {
            closeRawQuietly();
            initConnection();
        }
        return sharedConnectionProxy;
    }

    private synchronized void initConnection() {
        try {
            loadDriver(config.dialect());
            if (config.username() != null && !config.username().isEmpty()) {
                this.rawConnection = DriverManager.getConnection(
                        config.jdbcUrl(),
                        config.username(),
                        config.password() != null ? config.password() : "");
            } else {
                this.rawConnection = DriverManager.getConnection(config.jdbcUrl());
            }

            if (config.dialect() == Dialect.SQLITE) {
                try (Statement stmt = this.rawConnection.createStatement()) {
                    stmt.execute("PRAGMA foreign_keys = ON;");
                    stmt.execute("PRAGMA busy_timeout = 5000;");
                    if (!config.jdbcUrl().contains(":memory:")) {
                        stmt.execute("PRAGMA journal_mode = WAL;");
                        stmt.execute("PRAGMA synchronous = NORMAL;");
                        stmt.execute("PRAGMA wal_autocheckpoint = 1;");
                    }
                }
            }
            this.sharedConnectionProxy = createNonClosingProxy(this.rawConnection);
        } catch (ClassNotFoundException e) {
            String driverHint = switch (config.dialect()) {
                case POSTGRESQL -> "PostgreSQL JDBC Driver (org.postgresql:postgresql)";
                case MYSQL -> "MySQL JDBC Driver (com.mysql:mysql-connector-j)";
                case SQLITE -> "SQLite JDBC Driver (org.xerial:sqlite-jdbc)";
            };
            throw new RuntimeException("Không tìm thấy " + driverHint
                    + "! Hãy reload Maven project trong IDE hoặc chạy 'mvn dependency:resolve'.", e);
        } catch (SQLException e) {
            throw new RuntimeException("Không thể mở kết nối tới Database (" + config.safeDisplayUrl() + "): "
                    + e.getMessage(), e);
        }
    }

    private static void loadDriver(Dialect dialect) throws ClassNotFoundException {
        switch (dialect) {
            case POSTGRESQL -> Class.forName("org.postgresql.Driver");
            case MYSQL -> Class.forName("com.mysql.cj.jdbc.Driver");
            case SQLITE -> Class.forName("org.sqlite.JDBC");
        }
    }

    private static Connection createNonClosingProxy(Connection delegate) {
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            if ("close".equals(name)) {
                return null; // Giữ kết nối Singleton sống cho đến khi DBConnection.close() được gọi
            }
            if ("isClosed".equals(name)) {
                return delegate.isClosed();
            }
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                throw e.getTargetException();
            }
        };
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                handler);
    }

    /**
     * Tự động khởi tạo cấu trúc bảng tương thích với hệ CSDL đang kết nối (SQLite / PostgreSQL / MySQL).
     */
    public synchronized void initSchema() {
        String schemaScript = switch (config.dialect()) {
            case POSTGRESQL -> postgresqlSchemaScript();
            case MYSQL -> mysqlSchemaScript();
            case SQLITE -> loadSqliteSchemaScript();
        };
        executeScript(schemaScript);
    }

    /**
     * Tự động gộp tài khoản và dữ liệu đấu giá từ các file auction.db cục bộ cũ (nếu có)
     * vào file CSDL cố định trong ~/.bidion/auction.db mà không ghi đè dữ liệu mới hơn.
     */
    private synchronized void migrateLegacyLocalDatabases() {
        Path targetPath = extractFilePath(config.jdbcUrl());
        if (targetPath == null) {
            return;
        }
        List<Path> candidates = List.of(
                Paths.get("auction.db"),
                Paths.get("ltnc-btl", "auction.db"),
                Paths.get("..", "auction.db")
        );
        for (Path candidate : candidates) {
            try {
                Path normalized = candidate.toAbsolutePath().normalize();
                if (!Files.isRegularFile(normalized) || normalized.equals(targetPath) || Files.size(normalized) == 0) {
                    continue;
                }
                importFromSqliteFile(normalized);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Nhập không phá hủy (INSERT OR IGNORE) toàn bộ dữ liệu từ một file SQLite khác vào CSDL SQLite hiện tại.
     */
    public synchronized void importFromSqliteFile(Path legacyFile) throws SQLException {
        if (config.dialect() != Dialect.SQLITE || legacyFile == null || !Files.isRegularFile(legacyFile)) {
            return;
        }
        Connection conn = (rawConnection != null && !rawConnection.isClosed()) ? rawConnection : getConnection();
        try (PreparedStatement attach = conn.prepareStatement("ATTACH DATABASE ? AS legacy_db")) {
            attach.setString(1, legacyFile.toAbsolutePath().normalize().toString());
            attach.execute();
        }
        try (Statement stmt = conn.createStatement()) {
            tryMigrateTable(stmt, "INSERT OR IGNORE INTO main.users "
                    + "(id, username, password_hash, email, role, balance, rating, is_locked, created_at) "
                    + "SELECT id, username, password_hash, email, role, balance, rating, is_locked, created_at "
                    + "FROM legacy_db.users");
            tryMigrateTable(stmt, "INSERT OR IGNORE INTO main.items "
                    + "(id, seller_id, title, description, starting_price, category, extra_attributes, created_at) "
                    + "SELECT id, seller_id, title, description, starting_price, category, extra_attributes, created_at "
                    + "FROM legacy_db.items WHERE seller_id IN (SELECT id FROM main.users)");
            tryMigrateTable(stmt, "INSERT OR IGNORE INTO main.auctions "
                    + "(id, item_id, seller_id, min_increment, current_price, current_winner_id, current_winner_username, "
                    + "status, start_time, end_time, anti_sniping_trigger, anti_sniping_extend, created_at) "
                    + "SELECT id, item_id, seller_id, min_increment, current_price, current_winner_id, current_winner_username, "
                    + "status, start_time, end_time, anti_sniping_trigger, anti_sniping_extend, created_at "
                    + "FROM legacy_db.auctions "
                    + "WHERE item_id IN (SELECT id FROM main.items) AND seller_id IN (SELECT id FROM main.users)");
            tryMigrateTable(stmt, "INSERT OR IGNORE INTO main.bid_transactions "
                    + "(id, auction_id, bidder_id, bidder_username, bid_amount, bid_time) "
                    + "SELECT id, auction_id, bidder_id, bidder_username, bid_amount, bid_time "
                    + "FROM legacy_db.bid_transactions "
                    + "WHERE auction_id IN (SELECT id FROM main.auctions) AND bidder_id IN (SELECT id FROM main.users)");
        } finally {
            try (Statement detach = conn.createStatement()) {
                detach.execute("DETACH DATABASE legacy_db");
            } catch (SQLException ignored) {
            }
        }
    }

    private static void tryMigrateTable(Statement stmt, String sql) {
        try {
            stmt.executeUpdate(sql);
        } catch (SQLException ignored) {
        }
    }

    private String loadSqliteSchemaScript() {
        List<File> schemaCandidates = List.of(
                new File("src/database/schema.sql"),
                new File("ltnc-btl/src/database/schema.sql")
        );
        for (File file : schemaCandidates) {
            if (file.exists() && file.isFile()) {
                try (InputStream in = new FileInputStream(file);
                     BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append("\n");
                    }
                    return sb.toString();
                } catch (IOException ignored) {
                }
            }
        }

        return "PRAGMA foreign_keys = ON;\n"
                + "CREATE TABLE IF NOT EXISTS users (\n"
                + "    id TEXT PRIMARY KEY,\n"
                + "    username TEXT UNIQUE NOT NULL,\n"
                + "    password_hash TEXT NOT NULL,\n"
                + "    email TEXT,\n"
                + "    role TEXT CHECK(role IN ('BIDDER', 'SELLER', 'ADMIN')) NOT NULL,\n"
                + "    balance REAL DEFAULT 0.0,\n"
                + "    rating REAL DEFAULT 5.0,\n"
                + "    is_locked INTEGER DEFAULT 0,\n"
                + "    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP\n"
                + ");\n"
                + "CREATE TABLE IF NOT EXISTS items (\n"
                + "    id TEXT PRIMARY KEY,\n"
                + "    seller_id TEXT NOT NULL,\n"
                + "    title TEXT NOT NULL,\n"
                + "    description TEXT,\n"
                + "    starting_price REAL NOT NULL CHECK(starting_price >= 0),\n"
                + "    category TEXT CHECK(category IN ('ELECTRONICS', 'ART', 'VEHICLE', 'OTHER')) NOT NULL,\n"
                + "    extra_attributes TEXT,\n"
                + "    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,\n"
                + "    FOREIGN KEY (seller_id) REFERENCES users(id) ON DELETE CASCADE\n"
                + ");\n"
                + "CREATE TABLE IF NOT EXISTS auctions (\n"
                + "    id TEXT PRIMARY KEY,\n"
                + "    item_id TEXT NOT NULL,\n"
                + "    seller_id TEXT NOT NULL,\n"
                + "    min_increment REAL NOT NULL DEFAULT 50000.0,\n"
                + "    current_price REAL NOT NULL,\n"
                + "    current_winner_id TEXT,\n"
                + "    current_winner_username TEXT,\n"
                + "    status TEXT CHECK(status IN ('OPEN', 'RUNNING', 'FINISHED', 'PAID', 'CANCELED')) DEFAULT 'OPEN',\n"
                + "    start_time TIMESTAMP NOT NULL,\n"
                + "    end_time TIMESTAMP NOT NULL,\n"
                + "    anti_sniping_trigger INTEGER DEFAULT 30,\n"
                + "    anti_sniping_extend INTEGER DEFAULT 60,\n"
                + "    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,\n"
                + "    FOREIGN KEY (item_id) REFERENCES items(id) ON DELETE CASCADE,\n"
                + "    FOREIGN KEY (seller_id) REFERENCES users(id) ON DELETE CASCADE,\n"
                + "    FOREIGN KEY (current_winner_id) REFERENCES users(id) ON DELETE SET NULL\n"
                + ");\n"
                + "CREATE TABLE IF NOT EXISTS bid_transactions (\n"
                + "    id TEXT PRIMARY KEY,\n"
                + "    auction_id TEXT NOT NULL,\n"
                + "    bidder_id TEXT NOT NULL,\n"
                + "    bidder_username TEXT NOT NULL,\n"
                + "    bid_amount REAL NOT NULL,\n"
                + "    bid_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,\n"
                + "    FOREIGN KEY (auction_id) REFERENCES auctions(id) ON DELETE CASCADE,\n"
                + "    FOREIGN KEY (bidder_id) REFERENCES users(id) ON DELETE CASCADE\n"
                + ");\n"
                + "CREATE INDEX IF NOT EXISTS idx_auctions_status ON auctions(status);\n"
                + "CREATE INDEX IF NOT EXISTS idx_auctions_seller ON auctions(seller_id);\n"
                + "CREATE INDEX IF NOT EXISTS idx_bids_auction ON bid_transactions(auction_id);\n"
                + "CREATE INDEX IF NOT EXISTS idx_bids_bidder ON bid_transactions(bidder_id);\n";
    }

    private static String postgresqlSchemaScript() {
        return "CREATE TABLE IF NOT EXISTS users (\n"
                + "    id VARCHAR(64) PRIMARY KEY,\n"
                + "    username VARCHAR(100) UNIQUE NOT NULL,\n"
                + "    password_hash TEXT NOT NULL,\n"
                + "    email VARCHAR(255),\n"
                + "    role VARCHAR(20) CHECK(role IN ('BIDDER', 'SELLER', 'ADMIN')) NOT NULL,\n"
                + "    balance DOUBLE PRECISION DEFAULT 0.0,\n"
                + "    rating DOUBLE PRECISION DEFAULT 5.0,\n"
                + "    is_locked INTEGER DEFAULT 0,\n"
                + "    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP\n"
                + ");\n"
                + "CREATE TABLE IF NOT EXISTS items (\n"
                + "    id VARCHAR(64) PRIMARY KEY,\n"
                + "    seller_id VARCHAR(64) NOT NULL REFERENCES users(id) ON DELETE CASCADE,\n"
                + "    title VARCHAR(255) NOT NULL,\n"
                + "    description TEXT,\n"
                + "    starting_price DOUBLE PRECISION NOT NULL CHECK(starting_price >= 0),\n"
                + "    category VARCHAR(32) CHECK(category IN ('ELECTRONICS', 'ART', 'VEHICLE', 'OTHER')) NOT NULL,\n"
                + "    extra_attributes TEXT,\n"
                + "    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP\n"
                + ");\n"
                + "CREATE TABLE IF NOT EXISTS auctions (\n"
                + "    id VARCHAR(64) PRIMARY KEY,\n"
                + "    item_id VARCHAR(64) NOT NULL REFERENCES items(id) ON DELETE CASCADE,\n"
                + "    seller_id VARCHAR(64) NOT NULL REFERENCES users(id) ON DELETE CASCADE,\n"
                + "    min_increment DOUBLE PRECISION NOT NULL DEFAULT 50000.0,\n"
                + "    current_price DOUBLE PRECISION NOT NULL,\n"
                + "    current_winner_id VARCHAR(64) REFERENCES users(id) ON DELETE SET NULL,\n"
                + "    current_winner_username VARCHAR(100),\n"
                + "    status VARCHAR(20) CHECK(status IN ('OPEN', 'RUNNING', 'FINISHED', 'PAID', 'CANCELED')) DEFAULT 'OPEN',\n"
                + "    start_time VARCHAR(64) NOT NULL,\n"
                + "    end_time VARCHAR(64) NOT NULL,\n"
                + "    anti_sniping_trigger INTEGER DEFAULT 30,\n"
                + "    anti_sniping_extend INTEGER DEFAULT 60,\n"
                + "    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP\n"
                + ");\n"
                + "CREATE TABLE IF NOT EXISTS bid_transactions (\n"
                + "    id VARCHAR(64) PRIMARY KEY,\n"
                + "    auction_id VARCHAR(64) NOT NULL REFERENCES auctions(id) ON DELETE CASCADE,\n"
                + "    bidder_id VARCHAR(64) NOT NULL REFERENCES users(id) ON DELETE CASCADE,\n"
                + "    bidder_username VARCHAR(100) NOT NULL,\n"
                + "    bid_amount DOUBLE PRECISION NOT NULL,\n"
                + "    bid_time VARCHAR(64) NOT NULL\n"
                + ");\n"
                + "CREATE INDEX IF NOT EXISTS idx_auctions_status ON auctions(status);\n"
                + "CREATE INDEX IF NOT EXISTS idx_auctions_seller ON auctions(seller_id);\n"
                + "CREATE INDEX IF NOT EXISTS idx_bids_auction ON bid_transactions(auction_id);\n"
                + "CREATE INDEX IF NOT EXISTS idx_bids_bidder ON bid_transactions(bidder_id);\n";
    }

    private static String mysqlSchemaScript() {
        return "CREATE TABLE IF NOT EXISTS users (\n"
                + "    id VARCHAR(64) PRIMARY KEY,\n"
                + "    username VARCHAR(100) UNIQUE NOT NULL,\n"
                + "    password_hash TEXT NOT NULL,\n"
                + "    email VARCHAR(255),\n"
                + "    role VARCHAR(20) NOT NULL,\n"
                + "    balance DOUBLE DEFAULT 0.0,\n"
                + "    rating DOUBLE DEFAULT 5.0,\n"
                + "    is_locked INT DEFAULT 0,\n"
                + "    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP\n"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;\n"
                + "CREATE TABLE IF NOT EXISTS items (\n"
                + "    id VARCHAR(64) PRIMARY KEY,\n"
                + "    seller_id VARCHAR(64) NOT NULL,\n"
                + "    title VARCHAR(255) NOT NULL,\n"
                + "    description TEXT,\n"
                + "    starting_price DOUBLE NOT NULL,\n"
                + "    category VARCHAR(32) NOT NULL,\n"
                + "    extra_attributes TEXT,\n"
                + "    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,\n"
                + "    CONSTRAINT fk_items_seller FOREIGN KEY (seller_id) REFERENCES users(id) ON DELETE CASCADE\n"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;\n"
                + "CREATE TABLE IF NOT EXISTS auctions (\n"
                + "    id VARCHAR(64) PRIMARY KEY,\n"
                + "    item_id VARCHAR(64) NOT NULL,\n"
                + "    seller_id VARCHAR(64) NOT NULL,\n"
                + "    min_increment DOUBLE NOT NULL DEFAULT 50000.0,\n"
                + "    current_price DOUBLE NOT NULL,\n"
                + "    current_winner_id VARCHAR(64),\n"
                + "    current_winner_username VARCHAR(100),\n"
                + "    status VARCHAR(20) DEFAULT 'OPEN',\n"
                + "    start_time VARCHAR(64) NOT NULL,\n"
                + "    end_time VARCHAR(64) NOT NULL,\n"
                + "    anti_sniping_trigger INT DEFAULT 30,\n"
                + "    anti_sniping_extend INT DEFAULT 60,\n"
                + "    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,\n"
                + "    INDEX idx_auctions_status (status),\n"
                + "    INDEX idx_auctions_seller (seller_id),\n"
                + "    CONSTRAINT fk_auctions_item FOREIGN KEY (item_id) REFERENCES items(id) ON DELETE CASCADE,\n"
                + "    CONSTRAINT fk_auctions_seller FOREIGN KEY (seller_id) REFERENCES users(id) ON DELETE CASCADE,\n"
                + "    CONSTRAINT fk_auctions_winner FOREIGN KEY (current_winner_id) REFERENCES users(id) ON DELETE SET NULL\n"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;\n"
                + "CREATE TABLE IF NOT EXISTS bid_transactions (\n"
                + "    id VARCHAR(64) PRIMARY KEY,\n"
                + "    auction_id VARCHAR(64) NOT NULL,\n"
                + "    bidder_id VARCHAR(64) NOT NULL,\n"
                + "    bidder_username VARCHAR(100) NOT NULL,\n"
                + "    bid_amount DOUBLE NOT NULL,\n"
                + "    bid_time VARCHAR(64) NOT NULL,\n"
                + "    INDEX idx_bids_auction (auction_id),\n"
                + "    INDEX idx_bids_bidder (bidder_id),\n"
                + "    CONSTRAINT fk_bids_auction FOREIGN KEY (auction_id) REFERENCES auctions(id) ON DELETE CASCADE,\n"
                + "    CONSTRAINT fk_bids_bidder FOREIGN KEY (bidder_id) REFERENCES users(id) ON DELETE CASCADE\n"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;\n";
    }

    /**
     * Thực thi chuỗi lệnh SQL script phân tách nhau bằng dấu chấm phẩy ';'.
     */
    public synchronized void executeScript(String script) {
        if (script == null || script.trim().isEmpty()) {
            return;
        }

        try {
            Connection conn = getConnection();
            String[] statements = script.split(";");
            try (Statement stmt = conn.createStatement()) {
                for (String rawSql : statements) {
                    String sql = stripLeadingSqlComments(rawSql).trim();
                    if (!sql.isEmpty()) {
                        stmt.execute(sql);
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Lỗi khi thực thi SQL schema script: " + e.getMessage(), e);
        }
    }

    private static String stripLeadingSqlComments(String sql) {
        StringBuilder cleaned = new StringBuilder();
        for (String line : sql.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("--")) {
                cleaned.append(line).append('\n');
            }
        }
        return cleaned.toString();
    }

    private void closeRawQuietly() {
        if (rawConnection != null) {
            try {
                if (!rawConnection.isClosed()) {
                    if (config.dialect() == Dialect.SQLITE && !config.jdbcUrl().contains(":memory:")) {
                        try (Statement checkpoint = rawConnection.createStatement()) {
                            checkpoint.execute("PRAGMA wal_checkpoint(TRUNCATE);");
                        } catch (SQLException ignored) {
                        }
                    }
                    rawConnection.close();
                }
            } catch (SQLException ignored) {
            } finally {
                rawConnection = null;
                sharedConnectionProxy = null;
            }
        }
    }

    /**
     * Đẩy dữ liệu WAL xuống file chính (nếu dùng SQLite) và đóng kết nối CSDL khi Server tắt.
     */
    public synchronized void close() {
        closeRawQuietly();
    }

    public String getJdbcUrl() {
        return config.jdbcUrl();
    }

    public Dialect getDialect() {
        return config.dialect();
    }

    public DatabaseConfig getConfig() {
        return config;
    }

    public boolean isCloudDatabase() {
        return config.isCloudDatabase();
    }

    public String getDatabaseDisplayPath() {
        if (config.dialect() == Dialect.POSTGRESQL) {
            return "Cloud PostgreSQL (" + config.safeDisplayUrl() + ")";
        }
        if (config.dialect() == Dialect.MYSQL) {
            return "Cloud MySQL (" + config.safeDisplayUrl() + ")";
        }
        Path file = extractFilePath(config.jdbcUrl());
        return file != null ? file.toString() : config.jdbcUrl();
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof DBConnection other && Objects.equals(config, other.config));
    }

    @Override
    public int hashCode() {
        return Objects.hash(config);
    }
}
