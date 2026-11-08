package com.auction.server.model.entity;

/**
 * Admin: Quản trị viên hệ thống (Kế thừa từ User)
 */
public class Admin extends User {

    public Admin(String id, String username, String passwordHash, String email) {
        super(id, username, passwordHash, email);
    }

    @Override
    public String getRoleName() {
        return "ADMIN";
    }

    @Override
    public boolean canBid() {
        return false; // Admin không tham gia đấu giá
    }

    @Override
    public boolean canCreateAuction() {
        return false; // Admin không bán hàng, chỉ quản trị và duyệt phiên
    }
}
