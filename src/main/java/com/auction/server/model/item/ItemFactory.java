package com.auction.server.model.item;

import java.util.Map;

/**
 * ItemFactory: Áp dụng Factory Method Pattern
 * Nhiệm vụ: Tách biệt logic khởi tạo đối tượng Item ra khỏi Controller / Service.
 * Khi cần thêm loại hàng mới (vd: Bất động sản RealEstate), chỉ cần mở rộng tại đây.
 */
public class ItemFactory {

    public enum ItemType {
        ELECTRONICS,
        ART,
        VEHICLE
    }

    /**
     * Tạo đối tượng Item dựa vào phân loại (type)
     */
    public static Item createItem(ItemType type, String id, String title, String description,
                                  double startingPrice, Map<String, Object> extraAttributes) {
        if (type == null) {
            throw new IllegalArgumentException("Loại sản phẩm (ItemType) không được null!");
        }

        switch (type) {
            case ELECTRONICS: {
                int warranty = extraAttributes.containsKey("warrantyMonths")
                        ? ((Number) extraAttributes.get("warrantyMonths")).intValue() : 12;
                String brand = (String) extraAttributes.getOrDefault("brand", "Chưa rõ");
                return new ElectronicsItem(id, title, description, startingPrice, warranty, brand);
            }
            case ART: {
                String artist = (String) extraAttributes.getOrDefault("artistName", "Khuyết danh");
                boolean certificate = extraAttributes.containsKey("hasCertificate")
                        && (boolean) extraAttributes.get("hasCertificate");
                return new ArtItem(id, title, description, startingPrice, artist, certificate);
            }
            case VEHICLE: {
                int mileage = extraAttributes.containsKey("mileage")
                        ? ((Number) extraAttributes.get("mileage")).intValue() : 0;
                int year = extraAttributes.containsKey("manufactureYear")
                        ? ((Number) extraAttributes.get("manufactureYear")).intValue() : 2024;
                return new VehicleItem(id, title, description, startingPrice, mileage, year);
            }
            default:
                throw new IllegalArgumentException("Loại sản phẩm chưa được hỗ trợ: " + type);
        }
    }
}
