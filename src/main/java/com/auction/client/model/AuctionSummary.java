package com.auction.client.model;

/** Dữ liệu của một dòng trong bảng danh sách phiên đấu giá. */
public record AuctionSummary(
        String auctionId,
        String itemTitle,
        double currentHighestBid,
        String status) {
}
