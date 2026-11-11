package database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.nio.file.Files;
import java.nio.file.Paths;

public class DBConnection {
    // 1. Biến static lưu trữ instance duy nhất của lớp (mẫu Singleton)
    private static DBConnection instance;
    private Connection connection;

    // Đường dẫn tới file SQLite (file này sẽ tự động sinh ra ở thư mục gốc dự án)
    private static final String URL = "jdbc:sqlite:auction_system.db";

    // 2. Private constructor để chặn việc dùng từ khóa 'new' từ bên ngoài
    private DBConnection() {
        try {
            connection = DriverManager.getConnection(URL);
            System.out.println("Kết nối CSDL SQLite thành công!");
            initializeDatabase();
        } catch (SQLException e) {
            System.err.println("Lỗi kết nối CSDL: " + e.getMessage());
        }
    }

    // 3. Phương thức public static để lấy instance (đã được đồng bộ thread-safe)
    public static synchronized DBConnection getInstance() {
        if (instance == null) {
            instance = new DBConnection();
        }
        return instance;
    }

    public Connection getConnection() {
        return connection;
    }

    // Tự động đọc file schema.sql để khởi tạo các bảng
    private void initializeDatabase() {
        try (Statement stmt = connection.createStatement()) {
            String sql = new String(Files.readAllBytes(Paths.get("src/database/schema.sql")));
            String[] commands = sql.split(";");
            for (String command : commands) {
                if (!command.trim().isEmpty()) {
                    stmt.execute(command);
                }
            }
            System.out.println("Khởi tạo cấu trúc bảng thành công!");
        } catch (Exception e) {
            System.err.println("Lỗi khởi tạo bảng: " + e.getMessage());
        }
    }
}