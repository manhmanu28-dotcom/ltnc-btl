package com.auction.server.model.entity;

/**
 * Bidder: Người tham gia đấu giá (Kế thừa từ User)
 */
public class Bidder extends User {
    private double balance; // Số dư tài khoản để đặt cọc / đấu giá

    public Bidder(String id, String username, String passwordHash, String email, double initialBalance) {
        super(id, username, passwordHash, email);
        this.balance = Math.max(0, initialBalance);
    }

    @Override
    public String getRoleName() {
        return "BIDDER";
    }

    @Override
    public boolean canBid() {
        return true; // Người đấu giá ĐƯỢC đặt giá
    }

    @Override
    public boolean canCreateAuction() {
        return false; // Người đấu giá KHÔNG ĐƯỢC tự tạo phiên bán
    }

    @Override
    public String printInfo() {
        return String.format("%s | Số dư: %,.0f VNĐ", super.printInfo(), balance);
    }

    public double getBalance() {
        return balance;
    }

    public void setBalance(double balance) {
        this.balance = balance;
    }
}
