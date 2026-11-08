package com.auction.server.model.item;

import com.auction.server.model.entity.BaseEntity;

/**
 * Item: Lớp trừu tượng cho mọi mặt hàng đấu giá (Abstraction)
 */
public abstract class Item extends BaseEntity {
    private String title;
    private String description;
    private double startingPrice;

    public Item(String id, String title, String description, double startingPrice) {
        super(id);
        if (startingPrice < 0) {
            throw new IllegalArgumentException("Giá khởi điểm không thể âm!");
        }
        this.title = title;
        this.description = description;
        this.startingPrice = startingPrice;
    }

    // --- CÁC HÀM ĐA HÌNH (Polymorphism) ---

    /**
     * Mỗi loại mặt hàng có cách tính chi phí vận chuyển khác nhau:
     * - Điện tử: Cần đóng gói chống sốc.
     * - Tranh nghệ thuật: Cần bảo hiểm giá trị cao đặc biệt.
     * - Phương tiện: Cần xe cứu hộ / chuyên dụng vận chuyển đường dài.
     */
    public abstract double calculateShippingFee(double distanceKm);

    /**
     * Trả về thông tin đặc thù của từng loại sản phẩm để hiển thị lên GUI (TV3 dùng).
     */
    public abstract String getSpecificDetails();

    @Override
    public String printInfo() {
        return String.format("Item[ID=%s, Tên='%s', Giá khởi điểm=%,.0f VNĐ, Chi tiết={%s}]",
                getId(), title, startingPrice, getSpecificDetails());
    }

    // Getters & Setters
    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public double getStartingPrice() {
        return startingPrice;
    }

    public void setStartingPrice(double startingPrice) {
        this.startingPrice = startingPrice;
    }
}
