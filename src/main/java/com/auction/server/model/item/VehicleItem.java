package com.auction.server.model.item;

/**
 * VehicleItem: Phương tiện giao thông (Ô tô, Xe máy phân khối lớn...)
 */
public class VehicleItem extends Item {
    private int mileage;          // Số km đã chạy (ODO)
    private int manufactureYear;  // Năm sản xuất

    public VehicleItem(String id, String title, String description, double startingPrice,
                       int mileage, int manufactureYear) {
        super(id, title, description, startingPrice);
        this.mileage = mileage;
        this.manufactureYear = manufactureYear;
    }

    @Override
    public double calculateShippingFee(double distanceKm) {
        // Vận chuyển bằng xe chuyên dụng cứu hộ sàn phẳng: 25.000đ/km + 500.000đ phí bến bãi
        return 500_000 + (distanceKm * 25_000);
    }

    @Override
    public String getSpecificDetails() {
        return String.format("Năm sản xuất: %d | Số km đã chạy (ODO): %,d km", manufactureYear, mileage);
    }

    public int getMileage() {
        return mileage;
    }

    public int getManufactureYear() {
        return manufactureYear;
    }
}
