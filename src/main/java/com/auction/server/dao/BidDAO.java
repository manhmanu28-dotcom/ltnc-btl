package com.auction.server.dao;

import com.auction.server.database.DBConnection;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class BidDAO {
    // Ghi nhận một lượt đặt giá (bid) mới
    public boolean placeBid(int auctionId, int bidderId, double bidAmount) {
        String sql = "INSERT INTO bid_transactions (auction_id, bidder_id, bid_amount, timestamp) VALUES (?, ?, ?, CURRENT_TIMESTAMP)";

        try (Connection conn = DBConnection.getInstance().getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, auctionId);
            pstmt.setInt(2, bidderId);
            pstmt.setDouble(3, bidAmount);

            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("Lỗi khi ghi nhận giá đặt: " + e.getMessage());
            return false;
        }
    }
}