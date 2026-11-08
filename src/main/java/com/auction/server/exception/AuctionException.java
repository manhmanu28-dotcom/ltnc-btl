package com.auction.server.exception;

/**
 * AuctionException: Lớp ngoại lệ cơ sở cho mọi lỗi nghiệp vụ trong hệ thống đấu giá (Yêu cầu 3.1.5).
 */
public class AuctionException extends RuntimeException {

    public AuctionException(String message) {
        super(message);
    }

    public AuctionException(String message, Throwable cause) {
        super(message, cause);
    }
}
