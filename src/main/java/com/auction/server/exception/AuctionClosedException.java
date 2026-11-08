package com.auction.server.exception;

/**
 * AuctionClosedException: Ngoại lệ phát sinh khi người dùng cố gắng đặt giá
 * vào một phiên đấu giá chưa bắt đầu (OPEN) hoặc đã kết thúc (FINISHED, PAID, CANCELED).
 */
public class AuctionClosedException extends AuctionException {

    public AuctionClosedException(String message) {
        super(message);
    }
}
