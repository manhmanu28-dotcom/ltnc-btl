package com.auction.server.model.entity;

/**
 * Seller: Người bán hàng (Kế thừa từ User)
 */
public class Seller extends User {
    private double rating; // Đánh giá uy tín của người bán (vd: 4.8 / 5.0)

    public Seller(String id, String username, String passwordHash, String email) {
        super(id, username, passwordHash, email);
        this.rating = 5.0; // Điểm uy tín mặc định ban đầu
    }

    @Override
    public String getRoleName() {
        return "SELLER";
    }

    @Override
    public boolean canBid() {
        return false; // Người bán KHÔNG được tự đấu giá sản phẩm của chính mình / người khác (chống gian lận)
    }

    @Override
    public boolean canCreateAuction() {
        return true; // Người bán ĐƯỢC tạo phiên đấu giá
    }

    @Override
    public String printInfo() {
        return String.format("%s | Điểm uy tín: %.1f/5.0", super.printInfo(), rating);
    }

    public double getRating() {
        return rating;
    }

    public void setRating(double rating) {
        this.rating = rating;
    }
}
