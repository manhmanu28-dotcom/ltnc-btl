package com.auction.server.database;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * DBConnection: Quản lý kết nối Cơ sở dữ liệu tập trung (Yêu cầu 3.4 & Thành viên 4).
 *
 * Áp dụng:
 * 1. Singleton Pattern (Chỉ duy nhất một kết nối / quản lý tập trung cho Server).
 * 2. SQLite Database engine với cơ chế Foreign Keys và WAL (Write-Ahead Logging).
 * 3. Hỗ trợ thay đổi URL kết nối linh hoạt (hỗ trợ InMemory SQLite "jdbc:sqlite::memory:" phục vụ Unit Test).
 * 4. Tự động kiểm tra và khởi tạo bảng dữ liệu từ schema.sql khi khởi động hệ thống.
 */
public class DBConnection {

    public static final String DEFAULT_DB_URL = "jdbc:sqlite:auction.db";
    private static volatile DBConnection instance;
    private static final Object LOCK = new Object();

    private String jdbcUrl;
    private Connection connection;

    private DBConnection() {
        this(DEFAULT_DB_URL);
    }

    private DBConnection(String jdbcUrl) {
        this.jdbcUrl = (jdbcUrl != null) ? jdbcUrl : DEFAULT_DB_URL;
        initConnection();
        initSchema();
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
     * Mở hoặc lấy kết nối hiện tại tới Database.
     * Tự động kết nối lại nếu kết nối trước đó bị đóng.
     */
    public synchronized Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            initConnection();
        }
        return connection;
    }

    private synchronized void initConnection() {
        try {
            Class.forName("org.sqlite.JDBC");
            this.connection = DriverManager.getConnection(this.jdbcUrl);

            // Bật kiểm tra khóa ngoại và tối ưu SQLite
            try (Statement stmt = this.connection.createStatement()) {
                stmt.execute("PRAGMA foreign_keys = ON;");
                if (!this.jdbcUrl.contains(":memory:")) {
                    stmt.execute("PRAGMA journal_mode = WAL;");
                }
            }
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Không tìm thấy SQLite JDBC Driver!", e);
        } catch (SQLException e) {
            throw new RuntimeException("Không thể mở kết nối tới SQLite Database: " + this.jdbcUrl, e);
        }
    }

    /**
     * Tự động khởi tạo cấu trúc CSDL từ file schema.sql hoặc script tích hợp.
     */
    public synchronized void initSchema() {
        String schemaScript = loadSchemaScript();
        executeScript(schemaScript);
    }

    /**
     * Đọc nội dung schema.sql từ thư mục nguồn hoặc classpath.
     */
    private String loadSchemaScript() {
        File file = new File("src/database/schema.sql");
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
                + ");\n";
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
     * Đóng kết nối CSDL khi Server tắt.
     */
    public synchronized void close() {
        if (connection != null) {
            try {
                if (!connection.isClosed()) {
                    connection.close();
                }
            } catch (SQLException ignored) {
            } finally {
                connection = null;
            }
        }
    }

    public String getJdbcUrl() {
        return jdbcUrl;
    }
}

