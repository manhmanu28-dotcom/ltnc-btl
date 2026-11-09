package com.auction.server.network.protocol;

/**
 * ActionType: Định nghĩa danh mục các loại bản tin giao tiếp giữa Client và Server
 * theo giao thức JSON qua Socket (Yêu cầu 3.4 & Nhiệm vụ Thành viên 2).
 */
public enum ActionType {
    // 1. Nhóm bản tin xác thực & phiên làm việc
    LOGIN,
    REGISTER,
    LOGOUT,
    PING,

    // 2. Nhóm bản tin truy vấn & theo dõi phiên đấu giá (Observer)
    GET_AUCTIONS,
    GET_AUCTION_DETAIL,
    WATCH_AUCTION,
    UNWATCH_AUCTION,

    // 3. Nhóm bản tin nghiệp vụ đấu giá (Bidder & Seller)
    PLACE_BID,
    AUTO_BID,
    CREATE_AUCTION,
    CANCEL_AUCTION,

    // 4. Nhóm bản tin Realtime Broadcast từ Server đẩy về Client (không dùng polling)
    NOTIFY_UPDATE,
    BID_UPDATE,
    STATUS_UPDATE,
    ERROR;

    /**
     * Chuyển đổi chuỗi sang ActionType an toàn, không phân biệt hoa thường.
     */
    public static ActionType fromString(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("ActionType không được để trống!");
        }
        try {
            return ActionType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Hành động không hợp lệ hoặc không được hỗ trợ: " + value, e);
        }
    }
}

