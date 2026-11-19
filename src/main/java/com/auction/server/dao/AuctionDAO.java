package com.auction.server.dao;

import com.auction.server.database.DBConnection;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class AuctionDAO {
    // Mở một phiên đấu giá mới cho một mặt hàng
    public boolean createAuction(int itemId, String startTime, String endTime) {
        String sql = "INSERT INTO auctions (item_id, start_time, end_time, status) VALUES (?, ?, ?, 'OPEN')";

        try (Connection conn = DBConnection.getInstance().getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, itemId);
            pstmt.setString(2, startTime);
            pstmt.setString(3, endTime);

            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("Lỗi khi tạo phiên đấu giá: " + e.getMessage());
            return false;
        }
    }

    // Cập nhật trạng thái phiên (vd: từ RUNNING sang FINISHED)
    public boolean updateStatus(int auctionId, String status) {
        String sql = "UPDATE auctions SET status = ? WHERE id = ?";
        try (Connection conn = DBConnection.getInstance().getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, status);
            pstmt.setInt(2, auctionId);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("Lỗi cập nhật trạng thái đấu giá: " + e.getMessage());
            return false;
        }
    }
}