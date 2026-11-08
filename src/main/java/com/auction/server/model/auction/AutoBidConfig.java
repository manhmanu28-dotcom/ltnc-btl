package com.auction.server.model.auction;

import com.auction.server.model.entity.Bidder;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * AutoBidConfig: Cấu hình đấu giá tự động của người dùng (Yêu cầu 3.2.1).
 * Phục vụ hàng đợi ưu tiên (PriorityQueue):
 * - Ưu tiên 1: Giá trần tối đa (maxBid) cao hơn sẽ đứng trước.
 * - Ưu tiên 2: Nếu maxBid bằng nhau, người đăng ký auto-bid sớm hơn (registeredAt) sẽ được ưu tiên.
 */
public class AutoBidConfig implements Comparable<AutoBidConfig> {
    private final Bidder bidder;
    private final double maxBid;
    private final double increment;
    private final LocalDateTime registeredAt;
    private final long sequenceNumber;

    public AutoBidConfig(Bidder bidder, double maxBid, double increment,
                         LocalDateTime registeredAt, long sequenceNumber) {
        if (bidder == null || !bidder.canBid()) {
            throw new IllegalArgumentException("Người đăng ký Auto-Bid phải là Bidder hợp lệ!");
        }
        if (maxBid <= 0) {
            throw new IllegalArgumentException("Giá tối đa (maxBid) phải lớn hơn 0!");
        }
        if (increment <= 0) {
            throw new IllegalArgumentException("Bước giá tự động (increment) phải lớn hơn 0!");
        }
        this.bidder = bidder;
        this.maxBid = maxBid;
        this.increment = increment;
        this.registeredAt = (registeredAt != null) ? registeredAt : LocalDateTime.now();
        this.sequenceNumber = sequenceNumber;
    }

    @Override
    public int compareTo(AutoBidConfig other) {
        // So sánh maxBid giảm dần (cao hơn đứng đầu PriorityQueue)
        int maxCompare = Double.compare(other.maxBid, this.maxBid);
        if (maxCompare != 0) {
            return maxCompare;
        }
        // Nếu cùng maxBid: ưu tiên thời điểm đăng ký sớm hơn (tăng dần)
        int timeCompare = this.registeredAt.compareTo(other.registeredAt);
        if (timeCompare != 0) {
            return timeCompare;
        }
        return Long.compare(this.sequenceNumber, other.sequenceNumber);
    }

    public Bidder getBidder() {
        return bidder;
    }

    public String getBidderId() {
        return bidder.getId();
    }

    public double getMaxBid() {
        return maxBid;
    }

    public double getIncrement() {
        return increment;
    }

    public LocalDateTime getRegisteredAt() {
        return registeredAt;
    }

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AutoBidConfig)) return false;
        AutoBidConfig that = (AutoBidConfig) o;
        return Objects.equals(getBidderId(), that.getBidderId());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getBidderId());
    }
}
