package com.auction.server.dao;

import com.auction.server.database.DBConnection;
import com.auction.server.model.entity.Admin;
import com.auction.server.model.entity.Bidder;
import com.auction.server.model.entity.Seller;
import com.auction.server.model.entity.User;
import com.auction.server.util.PasswordUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * UserDAO: Tầng truy xuất dữ liệu người dùng (Yêu cầu 3.4 & Thành viên 4).
 *
 * Hỗ trợ:
 * - Đa hình hóa thực thể User (Admin, Seller, Bidder).
 * - Mã hóa mật khẩu an toàn với PasswordUtil.
 * - Khóa/mở khóa tài khoản (Admin moderation).
 * - Cập nhật số dư Bidder, điểm uy tín Seller.
 */
public class UserDAO {

    private final DBConnection dbConnection;

    public UserDAO() {
        this(DBConnection.getInstance());
    }

    public UserDAO(DBConnection dbConnection) {
        this.dbConnection = dbConnection;
    }

    /**
     * Lưu thông tin người dùng mới vào Database.
     */
    public boolean save(User user) {
        return writeUser(user, true);
    }

    /** Chỉ tạo mới; tên tài khoản trùng sẽ bị từ chối, không ghi đè người dùng cũ. */
    public boolean create(User user) {
        return writeUser(user, false);
    }

    private boolean writeUser(User user, boolean replace) {
        if (user == null) {
            throw new IllegalArgumentException("User không được null!");
        }

        String sql = (replace ? "INSERT OR REPLACE" : "INSERT")
                + " INTO users (id, username, password_hash, email, role, balance, rating, is_locked) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection conn = dbConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, user.getId());
            stmt.setString(2, user.getUsername());
            stmt.setString(3, user.getPasswordHash());
            stmt.setString(4, user.getEmail());
            stmt.setString(5, user.getRoleName());

            if (user instanceof Bidder) {
                stmt.setDouble(6, ((Bidder) user).getBalance());
                stmt.setDouble(7, 5.0);
            } else if (user instanceof Seller) {
                stmt.setDouble(6, 0.0);
                stmt.setDouble(7, ((Seller) user).getRating());
            } else {
                stmt.setDouble(6, 0.0);
                stmt.setDouble(7, 5.0);
            }

            stmt.setInt(8, 0); // Mặc định không bị khóa

            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Lỗi lưu người dùng vào Database: " + e.getMessage(), e);
        }
    }

    /**
     * Tìm người dùng theo ID.
     */
    public Optional<User> findById(String id) {
        if (id == null || id.trim().isEmpty()) {
            return Optional.empty();
        }

        String sql = "SELECT * FROM users WHERE id = ?";
        try (Connection conn = dbConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, id.trim());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToUser(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Lỗi tìm kiếm người dùng theo ID: " + e.getMessage(), e);
        }
        return Optional.empty();
    }

    /**
     * Tìm người dùng theo username.
     */
    public Optional<User> findByUsername(String username) {
        if (username == null || username.trim().isEmpty()) {
            return Optional.empty();
        }

        String sql = "SELECT * FROM users WHERE username = ?";
        try (Connection conn = dbConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, username.trim());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToUser(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Lỗi tìm kiếm người dùng theo username: " + e.getMessage(), e);
        }
        return Optional.empty();
    }

    /**
     * Lấy toàn bộ danh sách người dùng trong hệ thống (phục vụ Admin Dashboard).
     */
    public List<User> findAll() {
        List<User> list = new ArrayList<>();
        String sql = "SELECT * FROM users ORDER BY created_at DESC";

        try (Connection conn = dbConnection.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                list.add(mapResultSetToUser(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Lỗi truy vấn toàn bộ người dùng: " + e.getMessage(), e);
        }
        return list;
    }

    /**
     * Xác thực thông tin đăng nhập với mật khẩu thô và hash mã hóa trong DB.
     */
    public Optional<User> authenticate(String username, String rawPassword) {
        Optional<User> userOpt = findByUsername(username);
        if (userOpt.isEmpty()) {
            return Optional.empty();
        }

        User user = userOpt.get();
        if (isUserLocked(user.getId())) {
            throw new IllegalStateException("Tài khoản '" + username + "' đã bị Khóa bởi Quản trị viên!");
        }

        if (PasswordUtil.verifyPassword(rawPassword, user.getPasswordHash())) {
            return Optional.of(user);
        }
        return Optional.empty();
    }

    /**
     * Cập nhật số dư cho Bidder.
     */
    public boolean updateBalance(String userId, double newBalance) {
        String sql = "UPDATE users SET balance = ? WHERE id = ?";
        try (Connection conn = dbConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setDouble(1, Math.max(0, newBalance));
            stmt.setString(2, userId);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Lỗi cập nhật số dư Bidder: " + e.getMessage(), e);
        }
    }

    /**
     * Cập nhật điểm uy tín cho Seller.
     */
    public boolean updateRating(String sellerId, double rating) {
        String sql = "UPDATE users SET rating = ? WHERE id = ?";
        try (Connection conn = dbConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setDouble(1, Math.max(0.0, Math.min(5.0, rating)));
            stmt.setString(2, sellerId);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Lỗi cập nhật rating Seller: " + e.getMessage(), e);
        }
    }

    /**
     * Khóa hoặc Mở khóa tài khoản người dùng (Admin moderation).
     */
    public boolean setUserLocked(String userId, boolean locked) {
        String sql = "UPDATE users SET is_locked = ? WHERE id = ?";
        try (Connection conn = dbConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setInt(1, locked ? 1 : 0);
            stmt.setString(2, userId);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Lỗi thiết lập trạng thái khóa người dùng: " + e.getMessage(), e);
        }
    }

    /**
     * Kiểm tra xem người dùng có đang bị khóa hay không.
     */
    public boolean isUserLocked(String userId) {
        String sql = "SELECT is_locked FROM users WHERE id = ?";
        try (Connection conn = dbConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("is_locked") == 1;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Lỗi kiểm tra trạng thái khóa người dùng: " + e.getMessage(), e);
        }
        return false;
    }

    /**
     * Xóa người dùng theo ID.
     */
    public boolean delete(String id) {
        String sql = "DELETE FROM users WHERE id = ?";
        try (Connection conn = dbConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, id);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Lỗi xóa người dùng: " + e.getMessage(), e);
        }
    }

    /**
     * Ánh xạ ResultSet thành đối tượng kế thừa cụ thể của User (Polymorphic Mapping).
     */
    private User mapResultSetToUser(ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        String username = rs.getString("username");
        String passwordHash = rs.getString("password_hash");
        String email = rs.getString("email");
        String role = rs.getString("role");
        double balance = rs.getDouble("balance");
        double rating = rs.getDouble("rating");

        if ("ADMIN".equalsIgnoreCase(role)) {
            return new Admin(id, username, passwordHash, email);
        } else if ("SELLER".equalsIgnoreCase(role)) {
            Seller seller = new Seller(id, username, passwordHash, email);
            seller.setRating(rating);
            return seller;
        } else {
            return new Bidder(id, username, passwordHash, email, balance);
        }
    }
}

