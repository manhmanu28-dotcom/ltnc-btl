package com.auction.client.model;

import com.auction.server.network.AuctionSocketClient;

/** Kết nối đã đăng nhập; mật khẩu không được giữ trong thông tin phiên. */
public record AuthenticatedSession(
        String userId,
        String username,
        String role,
        String host,
        int port,
        AuctionSocketClient client) {

    public AuthenticatedSession(String username, String host, int port, AuctionSocketClient client) {
        this("", username, "BIDDER", host, port, client);
    }

    public boolean isSellerAccount() {
        return "SELLER".equalsIgnoreCase(role);
    }
}
