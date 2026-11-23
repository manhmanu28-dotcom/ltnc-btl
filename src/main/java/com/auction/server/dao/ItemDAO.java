package com.auction.server.dao;

import com.auction.server.database.DBConnection;
import com.auction.server.model.item.ArtItem;
import com.auction.server.model.item.ElectronicsItem;
import com.auction.server.model.item.Item;
import com.auction.server.model.item.ItemFactory;
import com.auction.server.model.item.VehicleItem;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * ItemDAO: Quản lý lưu trữ và truy vấn bảng `items` trong SQLite.
 */
public class ItemDAO {

    /** Lưu một mặt hàng mới xuống bảng `items`. */
    public boolean saveItem(Item item, String sellerId) {
        if (item == null || sellerId == null || sellerId.trim().isEmpty()) {
            return false;
        }
        String sql = "INSERT INTO items (id, seller_id, title, description, starting_price, category, extra_attributes) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try {
            Connection conn = DBConnection.getInstance().getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, item.getId());
                pstmt.setString(2, sellerId.trim());
                pstmt.setString(3, item.getTitle());
                pstmt.setString(4, item.getDescription());
                pstmt.setDouble(5, item.getStartingPrice());
                pstmt.setString(6, resolveCategory(item));
                pstmt.setString(7, item.getSpecificDetails());
                return pstmt.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            System.err.println("[ItemDAO] Lỗi khi lưu mặt hàng: " + e.getMessage());
            return false;
        }
    }

    /** Tìm mặt hàng theo ID. */
    public Optional<Item> findById(String itemId) {
        String sql = "SELECT id, title, description, starting_price, category FROM items WHERE id = ?";
        try {
            Connection conn = DBConnection.getInstance().getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, itemId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapRow(rs));
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("[ItemDAO] Lỗi khi tìm mặt hàng theo ID: " + e.getMessage());
        }
        return Optional.empty();
    }

    /** Lấy danh sách mặt hàng theo người bán (sellerId). */
    public List<Item> findBySellerId(String sellerId) {
        List<Item> result = new ArrayList<>();
        String sql = "SELECT id, title, description, starting_price, category FROM items "
                + "WHERE seller_id = ? ORDER BY created_at DESC";
        try {
            Connection conn = DBConnection.getInstance().getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, sellerId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        result.add(mapRow(rs));
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("[ItemDAO] Lỗi khi lấy danh sách mặt hàng theo Seller: " + e.getMessage());
        }
        return result;
    }

    /** Lấy toàn bộ mặt hàng trong hệ thống. */
    public List<Item> findAll() {
        List<Item> result = new ArrayList<>();
        String sql = "SELECT id, title, description, starting_price, category FROM items ORDER BY created_at DESC";
        try {
            Connection conn = DBConnection.getInstance().getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql);
                 ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    result.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            System.err.println("[ItemDAO] Lỗi khi lấy toàn bộ mặt hàng: " + e.getMessage());
        }
        return result;
    }

    /** Xóa mặt hàng theo ID. */
    public boolean deleteById(String itemId) {
        String sql = "DELETE FROM items WHERE id = ?";
        try {
            Connection conn = DBConnection.getInstance().getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, itemId);
                return pstmt.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            System.err.println("[ItemDAO] Lỗi khi xóa mặt hàng: " + e.getMessage());
            return false;
        }
    }

    private String resolveCategory(Item item) {
        if (item instanceof ElectronicsItem) {
            return "ELECTRONICS";
        }
        if (item instanceof ArtItem) {
            return "ART";
        }
        if (item instanceof VehicleItem) {
            return "VEHICLE";
        }
        return "OTHER";
    }

    private Item mapRow(ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        String title = rs.getString("title");
        String description = rs.getString("description");
        double startingPrice = rs.getDouble("starting_price");
        String category = rs.getString("category");

        ItemFactory.ItemType type;
        try {
            type = ItemFactory.ItemType.valueOf(category);
        } catch (IllegalArgumentException | NullPointerException ex) {
            type = ItemFactory.ItemType.ELECTRONICS;
        }
        return ItemFactory.createItem(type, id, title, description, startingPrice, Collections.emptyMap());
    }
}