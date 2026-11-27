package com.auction.server.dao;

import com.auction.server.database.DBConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * AuctionDAO: Quản lý lưu trữ và truy vấn bảng `auctions` trong SQLite.
 */
public class AuctionDAO {

    /** Bản ghi dữ liệu phiên đấu giá đọc từ DB (dùng cho Service & Admin GUI). */
    public record AuctionRecord(
            String id,
            String itemId,
            String sellerId,
            double minIncrement,
            double currentPrice,
            String currentWinnerId,
            String currentWinnerUsername,
            String status,
            String startTime,
            String endTime
    ) {
    }

    /** Tạo mới một phiên đấu giá xuống DB (tương thích SQLite, PostgreSQL, MySQL). */
    public boolean createAuction(String auctionId, String itemId, String sellerId,
                                 double minIncrement, double startingPrice,
                                 String status, String startTime, String endTime) {
        DBConnection db = DBConnection.getInstance();
        String sql = switch (db.getDialect()) {
            case POSTGRESQL -> "INSERT INTO auctions (id, item_id, seller_id, min_increment, current_price, status, start_time, end_time) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING";
            case MYSQL -> "INSERT IGNORE INTO auctions (id, item_id, seller_id, min_increment, current_price, status, start_time, end_time) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
            case SQLITE -> "INSERT OR IGNORE INTO auctions (id, item_id, seller_id, min_increment, current_price, status, start_time, end_time) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        };
        synchronized (db) {
            try {
                Connection conn = db.getConnection();
                try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.setString(1, auctionId);
                    pstmt.setString(2, itemId);
                    pstmt.setString(3, sellerId);
                    pstmt.setDouble(4, minIncrement);
                    pstmt.setDouble(5, startingPrice);
                    pstmt.setString(6, status != null ? status : "OPEN");
                    pstmt.setString(7, startTime);
                    pstmt.setString(8, endTime);
                    return pstmt.executeUpdate() > 0;
                }
            } catch (SQLException e) {
                System.err.println("[AuctionDAO] Lỗi khi tạo phiên đấu giá: " + e.getMessage());
                return false;
            }
        }
    }

    /** Cập nhật trạng thái phiên (OPEN, RUNNING, FINISHED, PAID, CANCELED). */
    public boolean updateStatus(String auctionId, String status) {
        String sql = "UPDATE auctions SET status = ? WHERE id = ?";
        DBConnection db = DBConnection.getInstance();
        synchronized (db) {
            try {
                Connection conn = db.getConnection();
                try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.setString(1, status);
                    pstmt.setString(2, auctionId);
                    return pstmt.executeUpdate() > 0;
                }
            } catch (SQLException e) {
                System.err.println("[AuctionDAO] Lỗi khi cập nhật trạng thái phiên: " + e.getMessage());
                return false;
            }
        }
    }

    /** Cập nhật giá cao nhất và người đang dẫn đầu sau mỗi lượt đặt giá hợp lệ. */
    public boolean updateHighestBid(String auctionId, double newPrice, String winnerId, String winnerUsername) {
        String sql = "UPDATE auctions SET current_price = ?, current_winner_id = ?, current_winner_username = ? WHERE id = ?";
        DBConnection db = DBConnection.getInstance();
        synchronized (db) {
            try {
                Connection conn = db.getConnection();
                try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.setDouble(1, newPrice);
                    pstmt.setString(2, winnerId);
                    pstmt.setString(3, winnerUsername);
                    pstmt.setString(4, auctionId);
                    return pstmt.executeUpdate() > 0;
                }
            } catch (SQLException e) {
                System.err.println("[AuctionDAO] Lỗi khi cập nhật giá cao nhất: " + e.getMessage());
                return false;
            }
        }
    }

    /** Gia hạn thời gian kết thúc phiên (Anti-Sniping). */
    public boolean updateEndTime(String auctionId, String newEndTime) {
        String sql = "UPDATE auctions SET end_time = ? WHERE id = ?";
        DBConnection db = DBConnection.getInstance();
        synchronized (db) {
            try {
                Connection conn = db.getConnection();
                try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.setString(1, newEndTime);
                    pstmt.setString(2, auctionId);
                    return pstmt.executeUpdate() > 0;
                }
            } catch (SQLException e) {
                System.err.println("[AuctionDAO] Lỗi khi gia hạn thời gian phiên: " + e.getMessage());
                return false;
            }
        }
    }

    /** Tìm phiên đấu giá theo ID. */
    public Optional<AuctionRecord> findById(String auctionId) {
        String sql = "SELECT id, item_id, seller_id, min_increment, current_price, "
                + "current_winner_id, current_winner_username, status, start_time, end_time "
                + "FROM auctions WHERE id = ?";
        DBConnection db = DBConnection.getInstance();
        synchronized (db) {
            try {
                Connection conn = db.getConnection();
                try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.setString(1, auctionId);
                    try (ResultSet rs = pstmt.executeQuery()) {
                        if (rs.next()) {
                            return Optional.of(mapRow(rs));
                        }
                    }
                }
            } catch (SQLException e) {
                System.err.println("[AuctionDAO] Lỗi khi tìm phiên theo ID: " + e.getMessage());
            }
        }
        return Optional.empty();
    }

    /** Lấy danh sách tất cả phiên đấu giá. */
    public List<AuctionRecord> findAll() {
        List<AuctionRecord> result = new ArrayList<>();
        String sql = "SELECT id, item_id, seller_id, min_increment, current_price, "
                + "current_winner_id, current_winner_username, status, start_time, end_time "
                + "FROM auctions ORDER BY created_at DESC";
        DBConnection db = DBConnection.getInstance();
        synchronized (db) {
            try {
                Connection conn = db.getConnection();
                try (PreparedStatement pstmt = conn.prepareStatement(sql);
                     ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        result.add(mapRow(rs));
                    }
                }
            } catch (SQLException e) {
                System.err.println("[AuctionDAO] Lỗi khi lấy danh sách phiên: " + e.getMessage());
            }
        }
        return result;
    }

    /** Lọc danh sách phiên theo trạng thái. */
    public List<AuctionRecord> findByStatus(String status) {
        List<AuctionRecord> result = new ArrayList<>();
        String sql = "SELECT id, item_id, seller_id, min_increment, current_price, "
                + "current_winner_id, current_winner_username, status, start_time, end_time "
                + "FROM auctions WHERE status = ? ORDER BY created_at DESC";
        DBConnection db = DBConnection.getInstance();
        synchronized (db) {
            try {
                Connection conn = db.getConnection();
                try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.setString(1, status);
                    try (ResultSet rs = pstmt.executeQuery()) {
                        while (rs.next()) {
                            result.add(mapRow(rs));
                        }
                    }
                }
            } catch (SQLException e) {
                System.err.println("[AuctionDAO] Lỗi khi lọc phiên theo trạng thái: " + e.getMessage());
            }
        }
        return result;
    }

    /** Xóa phiên đấu giá theo ID. */
    public boolean deleteById(String auctionId) {
        String sql = "DELETE FROM auctions WHERE id = ?";
        DBConnection db = DBConnection.getInstance();
        synchronized (db) {
            try {
                Connection conn = db.getConnection();
                try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.setString(1, auctionId);
                    return pstmt.executeUpdate() > 0;
                }
            } catch (SQLException e) {
                System.err.println("[AuctionDAO] Lỗi khi xóa phiên: " + e.getMessage());
                return false;
            }
        }
    }

    private AuctionRecord mapRow(ResultSet rs) throws SQLException {
        return new AuctionRecord(
                rs.getString("id"),
                rs.getString("item_id"),
                rs.getString("seller_id"),
                rs.getDouble("min_increment"),
                rs.getDouble("current_price"),
                rs.getString("current_winner_id"),
                rs.getString("current_winner_username"),
                rs.getString("status"),
                rs.getString("start_time"),
                rs.getString("end_time")
        );
    }
}