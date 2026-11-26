package com.auction.client.model;

import com.auction.server.network.protocol.SocketResponse;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Complete server snapshot, including history, for the live room. */
public record AuctionRoomSnapshot(String auctionId, String title, String description,
        String status, double startingPrice, double highest, double minimumNextBid,
        double increment, String winner, LocalDateTime endTime, LocalDateTime serverTime,
        long receivedNanos, List<Bid> bids) {
    public record Bid(String id, String bidder, double amount, LocalDateTime time) { }

    /** Keep fractional seconds until display rounding, even when JavaFX pulses arrive late. */
    public long remainingSeconds(long nowNanos) {
        long elapsedNanos = Math.max(0, nowNanos - receivedNanos);
        var remaining = java.time.Duration.between(serverTime, endTime).minusNanos(elapsedNanos);
        if (remaining.isNegative() || remaining.isZero()) return 0;
        return remaining.getSeconds() + (remaining.getNano() == 0 ? 0 : 1);
    }

    public static AuctionRoomSnapshot from(SocketResponse response) {
        Map<?, ?> auction = (Map<?, ?>) response.getData().get("auction");
        if (auction == null) throw new IllegalArgumentException("Server không gửi thông tin phiên.");
        Map<?, ?> item = (Map<?, ?>) auction.get("item");
        Object rawHistory = auction.get("bidHistory");
        List<Bid> bids = rawHistory instanceof List<?> history ? history.stream().map(entry -> {
            Map<?, ?> bid = (Map<?, ?>) entry;
            return new Bid(text(bid, "transactionId"), text(bid, "bidderUsername"),
                    number(bid, "amount"), LocalDateTime.parse(text(bid, "bidTime")));
        }).toList() : List.of();
        return new AuctionRoomSnapshot(text(auction, "auctionId"), text(item, "title"),
                text(item, "description"), text(auction, "status"), number(item, "startingPrice"),
                number(auction, "currentHighestBid"), number(auction, "minimumNextBid"),
                number(auction, "minIncrement"), text(auction, "currentWinnerUsername"),
                LocalDateTime.parse(text(auction, "endTime")), response.getTimestamp(),
                System.nanoTime(), bids);
    }

    private static String text(Map<?, ?> map, String key) {
        return map == null || map.get(key) == null ? "" : String.valueOf(map.get(key));
    }

    private static double number(Map<?, ?> map, String key) {
        return ((Number) map.get(key)).doubleValue();
    }
}
