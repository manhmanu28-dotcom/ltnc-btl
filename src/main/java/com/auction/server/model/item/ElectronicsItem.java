package com.auction.server.model.item;

/**
 * ElectronicsItem: Đồ điện tử (Laptop, Điện thoại, Máy ảnh...)
 */
public class ElectronicsItem extends Item {
    private int warrantyMonths; // Số tháng bảo hành
    private String brand;

    public ElectronicsItem(String id, String title, String description, double startingPrice,
                           int warrantyMonths, String brand) {
        super(id, title, description, startingPrice);
        this.warrantyMonths = warrantyMonths;
        this.brand = brand;
    }

    @Override
    public double calculateShippingFee(double distanceKm) {
        // Cước cơ bản: 5.000đ/km + 50.000đ đóng gói chống sốc/hàng dễ vỡ điện tử
        return 50_000 + (distanceKm * 5_000);
    }

    @Override
    public String getSpecificDetails() {
        return String.format("Thương hiệu: %s | Thời hạn bảo hành: %d tháng", brand, warrantyMonths);
    }

    public int getWarrantyMonths() {
        return warrantyMonths;
    }

    public String getBrand() {
        return brand;
    }
}
