package com.auction.server.model.item;

/**
 * ArtItem: Tác phẩm nghệ thuật (Tranh, Tượng điêu khắc, Cổ vật...)
 */
public class ArtItem extends Item {
    private String artistName;       // Tác giả
    private boolean hasCertificate;  // Có chứng chỉ giám định thật/giả không

    public ArtItem(String id, String title, String description, double startingPrice,
                   String artistName, boolean hasCertificate) {
        super(id, title, description, startingPrice);
        this.artistName = artistName;
        this.hasCertificate = hasCertificate;
    }

    @Override
    public double calculateShippingFee(double distanceKm) {
        // Tác phẩm nghệ thuật tính thêm 2% giá khởi điểm tiền bảo hiểm hàng quý + 15.000đ/km
        double insuranceFee = getStartingPrice() * 0.02;
        return insuranceFee + (distanceKm * 15_000);
    }

    @Override
    public String getSpecificDetails() {
        return String.format("Nghệ sĩ: %s | Giấy kiểm định: %s",
                artistName, (hasCertificate ? "Có chứng chỉ" : "Chưa kiểm định"));
    }

    public String getArtistName() {
        return artistName;
    }

    public boolean isHasCertificate() {
        return hasCertificate;
    }
}
