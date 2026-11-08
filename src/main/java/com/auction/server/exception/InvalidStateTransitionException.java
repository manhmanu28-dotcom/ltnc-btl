package com.auction.server.exception;

/**
 * InvalidStateTransitionException: Ngoại lệ phát sinh khi chuyển trạng thái phiên đấu giá
 * vi phạm vòng đời quy định: OPEN -> RUNNING -> FINISHED -> PAID / CANCELED.
 */
public class InvalidStateTransitionException extends AuctionException {

    public InvalidStateTransitionException(String message) {
        super(message);
    }
}
