package com.auction.server.dao;

import com.auction.server.database.DBConnection;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class ItemDAO {
    // Thêm mặt hàng mới do Seller đăng lên
    public boolean addItem(int sellerId, String name, String type, double startPrice, double bidIncrement) {
        String sql = "INSERT INTO items (seller_id, name, type, start_price, bid_increment) VALUES (?, ?, ?, ?, ?)";

        try (Connection conn = DBConnection.getInstance().getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, sellerId);
            pstmt.setString(2, name);
            pstmt.setString(3, type);
            pstmt.setDouble(4, startPrice);
            pstmt.setDouble(5, bidIncrement);

            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("Lỗi khi thêm mặt hàng: " + e.getMessage());
            return false;
        }
    }
}