package com.auction.server.model.item;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * ItemFactory: Áp dụng Factory Method Pattern kết hợp Registry mở rộng (Open/Closed Principle).
 * Nhiệm vụ: Tách biệt logic khởi tạo đối tượng Item ra khỏi Controller / Service.
 */
public class ItemFactory {

    public enum ItemType {
        ELECTRONICS,
        ART,
        VEHICLE
    }

    @FunctionalInterface
    public interface ItemCreator {
        Item create(String id, String title, String description,
                    double startingPrice, Map<String, Object> extraAttributes);
    }

    private static final Map<ItemType, ItemCreator> CREATORS = new EnumMap<>(ItemType.class);

    static {
        CREATORS.put(ItemType.ELECTRONICS, (id, title, description, startingPrice, attrs) -> {
            int warranty = parseInt(attrs, "warrantyMonths", 12);
            String brand = parseString(attrs, "brand", "Chưa rõ");
            return new ElectronicsItem(id, title, description, startingPrice, warranty, brand);
        });
        CREATORS.put(ItemType.ART, (id, title, description, startingPrice, attrs) -> {
            String artist = parseString(attrs, "artistName", "Khuyết danh");
            boolean certificate = parseBoolean(attrs, "hasCertificate", false);
            return new ArtItem(id, title, description, startingPrice, artist, certificate);
        });
        CREATORS.put(ItemType.VEHICLE, (id, title, description, startingPrice, attrs) -> {
            int mileage = parseInt(attrs, "mileage", 0);
            int year = parseInt(attrs, "manufactureYear", 2024);
            return new VehicleItem(id, title, description, startingPrice, mileage, year);
        });
    }

    /**
     * Tạo đối tượng Item dựa vào phân loại (type) một cách an toàn kiểu dữ liệu.
     */
    public static Item createItem(ItemType type, String id, String title, String description,
                                  double startingPrice, Map<String, Object> extraAttributes) {
        if (type == null) {
            throw new IllegalArgumentException("Loại sản phẩm (ItemType) không được null!");
        }
        ItemCreator creator = CREATORS.get(type);
        if (creator == null) {
            throw new IllegalArgumentException("Loại sản phẩm chưa được hỗ trợ: " + type);
        }
        Map<String, Object> safeAttributes = (extraAttributes != null)
                ? extraAttributes : Collections.emptyMap();
        return creator.create(id, title, description, startingPrice, safeAttributes);
    }

    private static int parseInt(Map<String, Object> attrs, String key, int defaultValue) {
        Object value = attrs.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt(((String) value).trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private static String parseString(Map<String, Object> attrs, String key, String defaultValue) {
        Object value = attrs.get(key);
        if (value == null) {
            return defaultValue;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? defaultValue : text;
    }

    private static boolean parseBoolean(Map<String, Object> attrs, String key, boolean defaultValue) {
        Object value = attrs.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean(((String) value).trim());
        }
        return defaultValue;
    }
}
