package com.auction.server;

import com.auction.server.network.AuctionServer;

import java.util.concurrent.CountDownLatch;

/** Chạy server riêng trước khi mở JavaFX client. */
public class ServerApplication {

    public static void main(String[] args) throws Exception {
        int port = args.length == 0 ? AuctionServer.DEFAULT_PORT : Integer.parseInt(args[0]);
        try (AuctionServer server = new AuctionServer(port)) {
            Runtime.getRuntime().addShutdownHook(new Thread(server::close, "Auction-Server-Shutdown"));
            server.start();
            System.out.println("Auction Server đang chạy tại cổng " + server.getLocalPort());
            System.out.println("Nhấn Ctrl+C để dừng. Server mới khởi động chưa có phiên đấu giá.");
            new CountDownLatch(1).await();
        }
    }
}
