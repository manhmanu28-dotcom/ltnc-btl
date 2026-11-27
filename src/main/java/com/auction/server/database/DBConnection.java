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

/**
 * DBConnection: Quản lý kết nối Cơ sở dữ liệu tập trung (Yêu cầu 3.4 & Thành viên 4).
 *
 * Đặc điểm bền vững:
 * 1. Lưu file SQLite mặc định tại thư mục dữ liệu cố định của người dùng (~/.bidion/auction.db),
 *    nằm ngoài cây thư mục Git để khi commit, pull, re-clone hoặc chạy từ thư mục khác không bị mất dữ liệu.
 * 2. Tự động gộp (auto-migrate) dữ liệu tài khoản/phiên từ các file auction.db cục bộ cũ (nếu có) sang DB cố định.
 * 3. Bọc Connection bằng Uncloseable Proxy để các khối try-with-resources trong DAO không vô tình đóng kết nối chung.
 * 4. Bật WAL autocheckpoint để dữ liệu ghi xuống đĩa ngay lập tức kể cả khi dừng nóng tiến trình.
 */
public class DBConnection {

    public static final String APP_DATA_DIR_NAME = ".bidion";
    public static final String DB_FILE_NAME = "auction.db";
    public static final String DEFAULT_DB_URL = buildDefaultJdbcUrl();

    private static volatile DBConnection instance;
    private static final Object LOCK = new Object();

    private final String jdbcUrl;
    private final boolean defaultPersistentDb;
    private Connection rawConnection;
    private Connection sharedConnectionProxy;

    private DBConnection() {
        this(resolveConfiguredJdbcUrl(), isUsingDefaultPersistentLocation());
    }

    private DBConnection(String jdbcUrl) {
        this(jdbcUrl, false);
    }

    private DBConnection(String jdbcUrl, boolean defaultPersistentDb) {
        this.jdbcUrl = (jdbcUrl != null && !jdbcUrl.isBlank()) ? jdbcUrl : buildDefaultJdbcUrl();
        this.defaultPersistentDb = defaultPersistentDb;
        ensureParentDirectoryExists(this.jdbcUrl);
        initConnection();
        initSchema();
        if (this.defaultPersistentDb) {
            migrateLegacyLocalDatabases();
        }
    }

    private static String buildDefaultJdbcUrl() {
        Path homeDir = Paths.get(System.getProperty("user.home", ".")).toAbsolutePath().normalize();
        Path dbFile = homeDir.resolve(APP_DATA_DIR_NAME).resolve(DB_FILE_NAME);
        return "jdbc:sqlite:" + dbFile;
    }

    private static boolean isUsingDefaultPersistentLocation() {
        String sysProp = System.getProperty("auction.db.path");
        if (sysProp != null && !sysProp.isBlank()) {
            return false;
        }
        String envProp = System.getenv("AUCTION_DB_PATH");
        return envProp == null || envProp.isBlank();
    }

    private static String resolveConfiguredJdbcUrl() {
        String customPath = System.getProperty("auction.db.path");
        if (customPath == null || customPath.isBlank()) {
            customPath = System.getenv("AUCTION_DB_PATH");
        }
        if (customPath != null && !customPath.isBlank()) {
            String trimmed = customPath.trim();
            if (trimmed.startsWith("jdbc:sqlite:")) {
                return trimmed;
            }
            return "jdbc:sqlite:" + Paths.get(trimmed).toAbsolutePath().normalize();
        }
        return buildDefaultJdbcUrl();
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
     * Khởi tạo thể hiện Singleton với JDBC URL tùy chỉnh (dùng cho Unit Test độc lập).
     */
    public static DBConnection getInstance(String customJdbcUrl) {
        if (instance == null) {
            synchronized (LOCK) {
                if (instance == null) {
                    instance = new DBConnection(customJdbcUrl);
                } else if (!instance.jdbcUrl.equals(customJdbcUrl)) {
                    instance.close();
                    instance = new DBConnection(customJdbcUrl);
                }
            }
        } else if (!instance.jdbcUrl.equals(customJdbcUrl)) {
            synchronized (LOCK) {
                instance.close();
                instance = new DBConnection(customJdbcUrl);
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
     * Mở hoặc lấy kết nối hiện tại tới Database (bọc proxy chống đóng nhầm từ try-with-resources).
     */
    public synchronized Connection getConnection() throws SQLException {
        if (rawConnection == null || rawConnection.isClosed()) {
            initConnection();
        }
        return sharedConnectionProxy;
    }

    private synchronized void initConnection() {
        try {
            Class.forName("org.sqlite.JDBC");
            this.rawConnection = DriverManager.getConnection(this.jdbcUrl);

            try (Statement stmt = this.rawConnection.createStatement()) {
                stmt.execute("PRAGMA foreign_keys = ON;");
                stmt.execute("PRAGMA busy_timeout = 5000;");
                if (!this.jdbcUrl.contains(":memory:")) {
                    stmt.execute("PRAGMA journal_mode = WAL;");
                    stmt.execute("PRAGMA synchronous = NORMAL;");
                    stmt.execute("PRAGMA wal_autocheckpoint = 1;");
                }
            }
            this.sharedConnectionProxy = createNonClosingProxy(this.rawConnection);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Không tìm thấy SQLite JDBC Driver!", e);
        } catch (SQLException e) {
            throw new RuntimeException("Không thể mở kết nối tới SQLite Database: " + this.jdbcUrl, e);
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
     * Tự động khởi tạo cấu trúc CSDL từ file schema.sql hoặc script tích hợp.
     */
    public synchronized void initSchema() {
        String schemaScript = loadSchemaScript();
        executeScript(schemaScript);
    }

    /**
     * Tự động gộp tài khoản và dữ liệu đấu giá từ các file auction.db cục bộ cũ (nếu có)
     * vào file CSDL cố định trong ~/.bidion/auction.db mà không ghi đè dữ liệu mới hơn.
     */
    private synchronized void migrateLegacyLocalDatabases() {
        Path targetPath = extractFilePath(this.jdbcUrl);
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
                // Bỏ qua nếu file cũ không phải SQLite hợp lệ hoặc đang bị tiến trình khác khóa
            }
        }
    }

    /**
     * Nhập không phá hủy (INSERT OR IGNORE) toàn bộ dữ liệu từ một file SQLite khác vào CSDL hiện tại.
     */
    public synchronized void importFromSqliteFile(Path legacyFile) throws SQLException {
        if (legacyFile == null || !Files.isRegularFile(legacyFile)) {
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
            // Bảng chưa tồn tại hoặc khác cấu trúc ở file cũ
        }
    }

    private String loadSchemaScript() {
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

        // Script mặc định dự phòng nếu file không tồn tại
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
                    String sql = rawSql.trim();
                    if (!sql.isEmpty()) {
                        stmt.execute(sql);
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Lỗi khi thực thi SQL schema script: " + e.getMessage(), e);
        }
    }

    /**
     * Đẩy dữ liệu WAL xuống file chính và đóng kết nối CSDL khi Server tắt.
     */
    public synchronized void close() {
        if (rawConnection != null) {
            try {
                if (!rawConnection.isClosed()) {
                    if (!this.jdbcUrl.contains(":memory:")) {
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

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public String getDatabaseDisplayPath() {
        Path file = extractFilePath(this.jdbcUrl);
        return file != null ? file.toString() : this.jdbcUrl;
    }
}
