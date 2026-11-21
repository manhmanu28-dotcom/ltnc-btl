package com.auction.server.model.auction;

import java.time.LocalDateTime;

/**
 * AuctionSnapshot: Bản chụp trạng thái bất biến (Immutable Snapshot) của một phiên đấu giá
 * tại một thời điểm xác định bên trong vùng khóa (lock), giúp tránh lỗi đọc dữ liệu không đồng nhất
 * (Torn Read) khi Observer hoặc nhiều luồng đọc thông tin phiên đấu giá.
 */
public final class AuctionSnapshot {
    private final String auctionId;
    private final String itemTitle;
    private final AuctionStatus status;
    private final double currentHighestBid;
    private final double minimumNextBid;
    private final String currentWinnerId;
    private final String currentWinnerUsername;
    private final LocalDateTime endTime;
    private final int bidCount;

    public AuctionSnapshot(String auctionId,
                           String itemTitle,
                           AuctionStatus status,
                           double currentHighestBid,
                           double minimumNextBid,
                           String currentWinnerId,
                           String currentWinnerUsername,
                           LocalDateTime endTime,
                           int bidCount) {
        this.auctionId = auctionId;
        this.itemTitle = itemTitle;
        this.status = status;
        this.currentHighestBid = currentHighestBid;
        this.minimumNextBid = minimumNextBid;
        this.currentWinnerId = currentWinnerId;
        this.currentWinnerUsername = currentWinnerUsername;
        this.endTime = endTime;
        this.bidCount = bidCount;
    }

    public String getAuctionId() {
        return auctionId;
    }

    public String getItemTitle() {
        return itemTitle;
    }

    public AuctionStatus getStatus() {
        return status;
    }

    public double getCurrentHighestBid() {
        return currentHighestBid;
    }

    public double getMinimumNextBid() {
        return minimumNextBid;
    }

    public String getCurrentWinnerId() {
        return currentWinnerId;
    }

    public String getCurrentWinnerUsername() {
        return currentWinnerUsername;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public int getBidCount() {
        return bidCount;
    }
}
