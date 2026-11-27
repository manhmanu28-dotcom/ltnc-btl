package com.auction.server.dao;

import com.auction.server.database.DBConnection;
import com.auction.server.model.auction.BidTransaction;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * BidDAO: Quản lý lưu trữ và truy vấn lịch sử đặt giá (`bid_transactions`).
 */
public class BidDAO {

    private static final DateTimeFormatter SQLITE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Lưu một giao dịch đặt giá mới xuống DB. */
    public boolean saveBid(BidTransaction bid) {
        if (bid == null) {
            return false;
        }
        String sql = "INSERT OR IGNORE INTO bid_transactions (id, auction_id, bidder_id, bidder_username, bid_amount, bid_time) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        DBConnection db = DBConnection.getInstance();
        synchronized (db) {
            try {
                Connection conn = db.getConnection();
                try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.setString(1, bid.getId());
                    pstmt.setString(2, bid.getAuctionId());
                    pstmt.setString(3, bid.getBidderId());
                    pstmt.setString(4, bid.getBidderUsername());
                    pstmt.setDouble(5, bid.getAmount());
                    pstmt.setString(6, bid.getBidTime().toString());
                    return pstmt.executeUpdate() > 0;
                }
            } catch (SQLException e) {
                System.err.println("[BidDAO] Lỗi khi lưu giao dịch đặt giá: " + e.getMessage());
                return false;
            }
        }
    }

    /** Lấy lịch sử đặt giá theo đúng trình tự thời gian tăng dần để khôi phục phiên khi khởi động lại Server. */
    public List<BidTransaction> findChronologicalByAuctionId(String auctionId) {
        List<BidTransaction> result = new ArrayList<>();
        String sql = "SELECT id, auction_id, bidder_id, bidder_username, bid_amount, bid_time "
                + "FROM bid_transactions WHERE auction_id = ? ORDER BY bid_time ASC, bid_amount ASC";
        DBConnection db = DBConnection.getInstance();
        synchronized (db) {
            try {
                Connection conn = db.getConnection();
                try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.setString(1, auctionId);
                    try (ResultSet rs = pstmt.executeQuery()) {
                        while (rs.next()) {
                            result.add(mapRow(rs));
                        }
                    }
                }
            } catch (SQLException e) {
                System.err.println("[BidDAO] Lỗi khi đọc lịch sử đặt giá theo thứ tự thời gian: " + e.getMessage());
            }
        }
        return result;
    }

    /** Lấy lịch sử đặt giá của một phiên (giá cao nhất lên đầu). */
    public List<BidTransaction> findByAuctionId(String auctionId) {
        List<BidTransaction> result = new ArrayList<>();
        String sql = "SELECT id, auction_id, bidder_id, bidder_username, bid_amount, bid_time "
                + "FROM bid_transactions WHERE auction_id = ? ORDER BY bid_amount DESC, bid_time DESC";
        try {
            Connection conn = DBConnection.getInstance().getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, auctionId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        result.add(mapRow(rs));
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("[BidDAO] Lỗi khi lấy lịch sử đặt giá theo phiên: " + e.getMessage());
        }
        return result;
    }

    /** Lấy lịch sử đặt giá của một người dùng. */
    public List<BidTransaction> findByBidderId(String bidderId) {
        List<BidTransaction> result = new ArrayList<>();
        String sql = "SELECT id, auction_id, bidder_id, bidder_username, bid_amount, bid_time "
                + "FROM bid_transactions WHERE bidder_id = ? ORDER BY bid_time DESC";
        try {
            Connection conn = DBConnection.getInstance().getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, bidderId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        result.add(mapRow(rs));
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("[BidDAO] Lỗi khi lấy lịch sử đặt giá theo Bidder: " + e.getMessage());
        }
        return result;
    }

    /** Lấy lượt đặt giá cao nhất hiện tại của một phiên. */
    public Optional<BidTransaction> findHighestBid(String auctionId) {
        String sql = "SELECT id, auction_id, bidder_id, bidder_username, bid_amount, bid_time "
                + "FROM bid_transactions WHERE auction_id = ? ORDER BY bid_amount DESC, bid_time ASC LIMIT 1";
        try {
            Connection conn = DBConnection.getInstance().getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, auctionId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapRow(rs));
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("[BidDAO] Lỗi khi tìm giá cao nhất: " + e.getMessage());
        }
        return Optional.empty();
    }

    /** Đếm tổng số lượt đặt giá của một phiên. */
    public int countByAuctionId(String auctionId) {
        String sql = "SELECT COUNT(*) FROM bid_transactions WHERE auction_id = ?";
        try {
            Connection conn = DBConnection.getInstance().getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, auctionId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt(1);
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("[BidDAO] Lỗi khi đếm lượt đặt giá: " + e.getMessage());
        }
        return 0;
    }

    private BidTransaction mapRow(ResultSet rs) throws SQLException {
        return new BidTransaction(
                rs.getString("id"),
                rs.getString("auction_id"),
                rs.getString("bidder_id"),
                rs.getString("bidder_username"),
                rs.getDouble("bid_amount"),
                parseDateTime(rs.getString("bid_time"))
        );
    }

    private LocalDateTime parseDateTime(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return LocalDateTime.now();
        }
        try {
            return LocalDateTime.parse(raw.trim());
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(raw.trim(), SQLITE_FORMAT);
            } catch (DateTimeParseException ex) {
                return LocalDateTime.now();
            }
        }
    }
}