package com.auction.server.service;

import com.auction.server.model.auction.Auction;
import com.auction.server.model.auction.AuctionSnapshot;
import com.auction.server.model.auction.AuctionStatus;
import com.auction.server.model.auction.BidTransaction;

/**
 * AuctionObserver: Giao diện Observer Pattern (Yêu cầu 3.2.4 & 3.6).
 * Cho phép tầng Socket Server (Thành viên 2) hoặc GUI lắng nghe các sự kiện
 * thay đổi giá, gia hạn Anti-sniping và đóng phiên theo thời gian thực mà không cần polling.
 */
public interface AuctionObserver {

    /**
     * Được gọi ngay sau khi có một lượt đặt giá mới hợp lệ (thủ công hoặc tự động).
     *
     * @param auction      Phiên đấu giá có cập nhật
     * @param transaction  Giao dịch đặt giá vừa được ghi nhận
     * @param timeExtended true nếu lượt đặt giá này kích hoạt Anti-sniping làm dời endTime
     */
    void onNewBid(Auction auction, BidTransaction transaction, boolean timeExtended);

    /**
     * Phiên bản nhận bản chụp trạng thái bất biến {@link AuctionSnapshot} để tránh Torn Read.
     */
    default void onNewBid(AuctionSnapshot snapshot, BidTransaction transaction, boolean timeExtended) {
    }

    /**
     * Được gọi khi trạng thái của phiên đấu giá thay đổi (ví dụ: OPEN -> RUNNING -> FINISHED).
     *
     * @param auction   Phiên đấu giá
     * @param oldStatus Trạng thái trước đó
     * @param newStatus Trạng thái mới
     */
    void onStatusChanged(Auction auction, AuctionStatus oldStatus, AuctionStatus newStatus);

    /**
     * Phiên bản nhận bản chụp trạng thái bất biến {@link AuctionSnapshot} khi đổi trạng thái.
     */
    default void onStatusChanged(AuctionSnapshot snapshot, AuctionStatus oldStatus, AuctionStatus newStatus) {
    }
}
