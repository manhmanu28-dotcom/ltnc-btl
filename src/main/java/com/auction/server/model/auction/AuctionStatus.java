package com.auction.server.model.auction;

/**
 * AuctionStatus: Máy trạng thái (State Machine) quản lý vòng đời phiên đấu giá (Yêu cầu 3.1.4).
 * Chuỗi trạng thái chuẩn: OPEN -> RUNNING -> FINISHED -> PAID / CANCELED
 */
public enum AuctionStatus {
    OPEN,
    RUNNING,
    FINISHED,
    PAID,
    CANCELED;

    /**
     * Kiểm tra tính hợp lệ của việc chuyển từ trạng thái hiện tại sang trạng thái tiếp theo.
     */
    public boolean canTransitionTo(AuctionStatus nextStatus) {
        if (nextStatus == null || this == nextStatus) {
            return false;
        }
        switch (this) {
            case OPEN:
                return nextStatus == RUNNING || nextStatus == CANCELED;
            case RUNNING:
                return nextStatus == FINISHED || nextStatus == CANCELED;
            case FINISHED:
                return nextStatus == PAID || nextStatus == CANCELED;
            case PAID:
            case CANCELED:
            default:
                return false; // Trạng thái kết thúc (Terminal States)
        }
    }
}
