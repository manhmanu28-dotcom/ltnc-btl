package com.auction.server.model.auction;

import com.auction.server.model.entity.BaseEntity;

import java.time.LocalDateTime;

/**
 * BidTransaction: Giao dịch đặt giá (Yêu cầu 3.3.1).
 * Được thiết kế bất biến (Immutable) sau khi khởi tạo để đảm bảo an toàn đa luồng (Thread-safe)
 * khi lưu trữ trong lịch sử đấu giá hoặc gửi qua Socket/Observer.
 */
public class BidTransaction extends BaseEntity {
    private final String auctionId;
    private final String bidderId;
    private final String bidderUsername;
    private final double amount;
    private final boolean autoBid;

    public BidTransaction(String id, String auctionId, String bidderId, String bidderUsername,
                          double amount, LocalDateTime bidTime, boolean autoBid) {
        super(id, bidTime);
        if (auctionId == null || auctionId.trim().isEmpty()) {
            throw new IllegalArgumentException("Auction ID không được để trống!");
        }
        if (bidderId == null || bidderId.trim().isEmpty()) {
            throw new IllegalArgumentException("Bidder ID không được để trống!");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("Số tiền đặt giá phải lớn hơn 0!");
        }
        this.auctionId = auctionId.trim();
        this.bidderId = bidderId.trim();
        this.bidderUsername = (bidderUsername != null) ? bidderUsername : bidderId;
        this.amount = amount;
        this.autoBid = autoBid;
    }

    public BidTransaction(String id, String auctionId, String bidderId, String bidderUsername,
                          double amount, LocalDateTime bidTime) {
        this(id, auctionId, bidderId, bidderUsername, amount, bidTime, false);
    }

    @Override
    public String printInfo() {
        return String.format("BidTransaction[ID=%s, Phiên=%s, Người đặt=%s (%s), Giá=%,.0f VNĐ, Lúc=%s, Loại=%s]",
                getId(), auctionId, bidderUsername, bidderId, amount, getCreatedAt(),
                autoBid ? "AUTO-BID" : "MANUAL");
    }

    public String getAuctionId() {
        return auctionId;
    }

    public String getBidderId() {
        return bidderId;
    }

    public String getBidderUsername() {
        return bidderUsername;
    }

    public double getAmount() {
        return amount;
    }

    public LocalDateTime getBidTime() {
        return getCreatedAt();
    }

    public boolean isAutoBid() {
        return autoBid;
    }
}
