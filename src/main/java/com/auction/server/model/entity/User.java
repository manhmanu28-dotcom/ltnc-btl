package com.auction.server.model.entity;

/**
 * User: Lớp trừu tượng đại diện cho người dùng chung
 * Áp dụng tính Đóng gói (Encapsulation) và Trừu tượng (Abstraction)
 */
public abstract class User extends BaseEntity {
    private String username;
    private String passwordHash;
    private String email;

    public User(String id, String username, String passwordHash, String email) {
        super(id);
        setUsername(username);
        this.passwordHash = passwordHash;
        this.email = (email != null) ? email.trim() : null;
    }

    // Các hàm đa hình (Polymorphic Methods) - Mỗi vai trò sẽ có hành vi khác nhau
    public abstract String getRoleName();
    public abstract boolean canBid();
    public abstract boolean canCreateAuction();

    @Override
    public String printInfo() {
        return String.format("[%s] ID: %s | Username: %s | Email: %s",
                getRoleName(), getId(), username, email);
    }

    // Getters & Setters (Đóng gói)
    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        if (username == null || username.trim().isEmpty()) {
            throw new IllegalArgumentException("Tên đăng nhập (username) không được để trống!");
        }
        this.username = username.trim();
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = (email != null) ? email.trim() : null;
    }
}
