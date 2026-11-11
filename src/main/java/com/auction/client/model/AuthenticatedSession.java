package com.auction.client.model;

import com.auction.server.network.AuctionSocketClient;

/** Kết nối đã đăng nhập; mật khẩu không được giữ trong thông tin phiên. */
public record AuthenticatedSession(String username, String host, int port, AuctionSocketClient client) {
}
