package com.auction.server.exception;

/**
 * InvalidBidException: Ngoại lệ phát sinh khi giá đặt không hợp lệ:
 * - Đặt giá thấp hơn hoặc bằng giá hiện tại / không đủ bước giá tối thiểu
 * - Người dùng không có quyền đặt giá (Seller/Admin)
 * - Số dư khả dụng không đủ
 * - Người dẫn đầu tự đặt đè lên chính mình (khi không bật allowSelfOutbid)
 */
public class InvalidBidException extends AuctionException {

    public InvalidBidException(String message) {
        super(message);
    }
}
