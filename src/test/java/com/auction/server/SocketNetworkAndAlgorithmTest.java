package com.auction.server;

import com.auction.server.model.auction.Auction;
import com.auction.server.model.entity.Seller;
import com.auction.server.model.item.Item;
import com.auction.server.model.item.ItemFactory;
import com.auction.server.network.AuctionServer;
import com.auction.server.network.ClientHandler;
import com.auction.server.network.SocketBroadcastManager;
import com.auction.server.network.protocol.ActionType;
import com.auction.server.network.protocol.SocketRequest;
import com.auction.server.network.protocol.SocketResponse;
import com.auction.server.service.AuctionManager;
import com.auction.server.dao.UserDAO;
import com.auction.server.database.DBConnection;
import com.auction.server.model.entity.Bidder;
import com.auction.server.util.PasswordUtil;
import java.nio.file.Files;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bộ kiểm thử chuyên sâu cho Hạ tầng mạng Socket, Giao thức JSON, Realtime Observer
 * và các Thuật toán nâng cao (Nhiệm vụ Thành viên 2 - Nguyễn Hoàng Việt).
 */
class SocketNetworkAndAlgorithmTest {

    private AuctionManager auctionManager;
    private Auction sampleAuction;

    @BeforeEach
    void setUp() throws Exception {
        DBConnection.resetInstance();
        DBConnection.getInstance("jdbc:sqlite:" + Files.createTempFile("auction-network-test-", ".db"));
        UserDAO users = new UserDAO();
        String passwordHash = PasswordUtil.hashPassword("test-password");
        users.create(new Bidder("BIDDER-VIET", "hoang_viet", passwordHash, "viet@example.com", 100_000_000));
        users.create(new Bidder("BIDDER-NAM", "nhat_nam", passwordHash, "nam@example.com", 100_000_000));
        users.create(new Bidder("BIDDER-02", "viet_pro_bidder", passwordHash, "bidder@example.com", 100_000_000));
        auctionManager = AuctionManager.getInstance();
        auctionManager.clearAll();

        Map<String, Object> attrs = new HashMap<>();
        attrs.put("artistName", "Bùi Xuân Phái");
        attrs.put("hasCertificate", true);
        Item artItem = ItemFactory.createItem(
                ItemFactory.ItemType.ART,
                "ITEM-ART-01",
                "Tranh Phố Cổ Hà Nội",
                "Tranh sơn dầu nguyên bản có chứng nhận bảo tàng",
                20_000_000,
                attrs
        );

        Seller seller = new Seller("SELLER-ART", "gallery_hn", "hash", "gallery@vnu.edu.vn");
        LocalDateTime start = LocalDateTime.now().minusMinutes(10);
        LocalDateTime end = LocalDateTime.now().plusMinutes(30);

        sampleAuction = new Auction("AUC-NET-01", artItem, seller.getId(), 1_000_000, start, end);
        sampleAuction.startAuction();
        auctionManager.registerAuction(sampleAuction);
    }

    @AfterEach
    void tearDown() {
        auctionManager.clearAll();
        DBConnection.resetInstance();
    }

    @Test
    @DisplayName("1. Kiểm tra đóng gói (Serialize) và giải mã (Parse) bản tin JSON Request/Response")
    void testJsonProtocolSerializationAndDeserialization() {
        SocketRequest originalReq = SocketRequest.of(ActionType.PLACE_BID, "BIDDER-99", "AUC-NET-01")
                .withRequestId("REQ-TEST-01")
                .putPayload("amount", 25_000_000.0)
                .putPayload("username", "nguyen_hoang_viet")
                .putPayload("note", "Đặt giá \"đặc biệt\" hợp lệ");

        String jsonReq = originalReq.toJson();
        assertNotNull(jsonReq);
        assertFalse(jsonReq.contains("\n"), "Bản tin JSON truyền qua Socket phải nằm gọn trên 1 dòng!");

        SocketRequest parsedReq = SocketRequest.fromJson(jsonReq);
        assertEquals("REQ-TEST-01", parsedReq.getRequestId());
        assertEquals(ActionType.PLACE_BID, parsedReq.getAction());
        assertEquals("BIDDER-99", parsedReq.getUserId());
        assertEquals("AUC-NET-01", parsedReq.getAuctionId());
        assertEquals(25_000_000.0, parsedReq.getRequiredPayloadDouble("amount"));
        assertEquals("nguyen_hoang_viet", parsedReq.getPayloadString("username"));
        assertEquals("Đặt giá \"đặc biệt\" hợp lệ", parsedReq.getPayloadString("note"));

        SocketResponse originalResp = SocketResponse.ok(parsedReq, "Đặt giá thành công!")
                .putData("currentHighestBid", 25_000_000.0)
                .putData("timeExtended", true);

        String jsonResp = originalResp.toJson();
        SocketResponse parsedResp = SocketResponse.fromJson(jsonResp);
        assertTrue(parsedResp.isSuccess());
        assertEquals("REQ-TEST-01", parsedResp.getRequestId());
        assertEquals(ActionType.PLACE_BID, parsedResp.getAction());
        assertEquals("Đặt giá thành công!", parsedResp.getMessage());
        assertEquals(Boolean.TRUE, parsedResp.getData().get("timeExtended"));
    }

    @Test
    @DisplayName("2. Kiểm tra điều phối JSON qua ClientHandler: LOGIN, GET_AUCTIONS, PLACE_BID, AUTO_BID & Bắt lỗi")
    void testClientHandlerJsonDispatchAndErrorHandling() {
        SocketBroadcastManager broadcastManager = new SocketBroadcastManager();
        ClientHandler handler = new ClientHandler(null, auctionManager, broadcastManager, new ConcurrentHashMap<>());

        // Bước 1: Gửi gói tin LOGIN
        String loginJson = SocketRequest.of(ActionType.LOGIN, "BIDDER-VIET", null)
                .putPayload("username", "hoang_viet")
                .putPayload("password", "test-password")
                .toJson();
        SocketResponse loginResp = handler.processJsonMessage(loginJson);
        assertTrue(loginResp.isSuccess());
        assertEquals("BIDDER-VIET", loginResp.getData().get("userId"));

        // Bước 2: Gửi gói tin PLACE_BID hợp lệ (20.000.000 VNĐ)
        String bidJson = SocketRequest.of(ActionType.PLACE_BID, "BIDDER-VIET", "AUC-NET-01")
                .putPayload("amount", 20_000_000.0)
                .toJson();
        SocketResponse bidResp = handler.processJsonMessage(bidJson);
        assertTrue(bidResp.isSuccess());
        assertEquals(20_000_000.0, sampleAuction.getCurrentHighestBid());

        assertTrue(handler.processJsonMessage(SocketRequest.of(ActionType.LOGIN)
                .putPayload("username", "nhat_nam")
                .putPayload("password", "test-password").toJson()).isSuccess());

        // Bước 3: Người thứ 2 đặt giá thấp hơn mức tối thiểu -> phải trả về SocketResponse(success=false)
        String invalidBidJson = SocketRequest.of(ActionType.PLACE_BID, "BIDDER-NAM", "AUC-NET-01")
                .putPayload("username", "nhat_nam")
                .putPayload("amount", 20_500_000.0) // Bước giá tối thiểu là 1.000.000 -> cần >= 21.000.000
                .toJson();
        SocketResponse errorResp = handler.processJsonMessage(invalidBidJson);
        assertFalse(errorResp.isSuccess(), "Đặt giá thiếu bước giá tối thiểu phải trả về success = false!");
        assertTrue(errorResp.getMessage().contains("không hợp lệ"));

        // Bước 4: Người thứ 2 đăng ký AUTO_BID qua JSON (maxBid = 26.000.000, increment = 1.000.000)
        String autoBidJson = SocketRequest.of(ActionType.AUTO_BID, "BIDDER-NAM", "AUC-NET-01")
                .putPayload("username", "nhat_nam")
                .putPayload("maxBid", 26_000_000.0)
                .putPayload("increment", 1_000_000.0)
                .toJson();
        SocketResponse autoBidResp = handler.processJsonMessage(autoBidJson);
        assertTrue(autoBidResp.isSuccess());
        assertEquals("BIDDER-NAM", sampleAuction.getCurrentWinnerId());
        assertEquals(21_000_000.0, sampleAuction.getCurrentHighestBid());
    }

    @Test
    @DisplayName("3. Kiểm tra End-to-End Socket Server & Realtime Broadcast (Observer không dùng polling)")
    void testEndToEndSocketServerAndRealtimeBroadcast() throws Exception {
        // Sử dụng port = 0 để hệ điều hành tự cấp phát cổng trống khả dụng
        try (AuctionServer server = new AuctionServer(0)) {
            server.start();
            int port = server.getLocalPort();
            assertTrue(port > 0);

            CountDownLatch watcherReady = new CountDownLatch(1);
            CountDownLatch broadcastReceived = new CountDownLatch(1);
            AtomicReference<SocketResponse> pushedNotification = new AtomicReference<>();

            // Luồng Client 1: Mở kết nối Socket, gửi lệnh WATCH_AUCTION và ngồi chờ gói tin Push từ Server
            Thread watcherClientThread = new Thread(() -> {
                try (Socket watcherSocket = new Socket("127.0.0.1", port);
                     PrintWriter out = new PrintWriter(
                             new OutputStreamWriter(watcherSocket.getOutputStream(), StandardCharsets.UTF_8), true);
                     BufferedReader in = new BufferedReader(
                             new InputStreamReader(watcherSocket.getInputStream(), StandardCharsets.UTF_8))) {

                    // Gửi yêu cầu đăng ký xem phiên AUC-NET-01
                    SocketRequest watchReq = SocketRequest.of(ActionType.WATCH_AUCTION, "WATCHER-01", "AUC-NET-01");
                    out.println(watchReq.toJson());

                    // Đọc phản hồi xác nhận WATCH_AUCTION thành công
                    String ackLine = in.readLine();
                    SocketResponse ackResp = SocketResponse.fromJson(ackLine);
                    if (ackResp.isSuccess()) {
                        watcherReady.countDown();
                    }

                    // Ngồi lắng nghe gói tin Realtime Broadcast (KHÔNG POLLING!)
                    String pushLine = in.readLine();
                    if (pushLine != null) {
                        pushedNotification.set(SocketResponse.fromJson(pushLine));
                        broadcastReceived.countDown();
                    }
                } catch (Exception ignored) {
                }
            });
            watcherClientThread.setDaemon(true);
            watcherClientThread.start();

            assertTrue(watcherReady.await(5, TimeUnit.SECONDS), "Client 1 phải đăng ký WATCH_AUCTION thành công!");

            // Luồng Client 2: Kết nối vào Server và gửi lệnh PLACE_BID
            try (Socket bidderSocket = new Socket("127.0.0.1", port);
                 PrintWriter out = new PrintWriter(
                         new OutputStreamWriter(bidderSocket.getOutputStream(), StandardCharsets.UTF_8), true);
                 BufferedReader in = new BufferedReader(
                         new InputStreamReader(bidderSocket.getInputStream(), StandardCharsets.UTF_8))) {

                out.println(SocketRequest.of(ActionType.LOGIN)
                        .putPayload("username", "viet_pro_bidder")
                        .putPayload("password", "test-password").toJson());
                assertTrue(SocketResponse.fromJson(in.readLine()).isSuccess());

                SocketRequest bidReq = SocketRequest.of(ActionType.PLACE_BID, "BIDDER-02", "AUC-NET-01")
                        .putPayload("username", "viet_pro_bidder")
                        .putPayload("amount", 22_000_000.0);
                out.println(bidReq.toJson());

                String firstLine = in.readLine();
                assertNotNull(firstLine);
            }

            // Kiểm chứng: Client 1 ngay lập tức nhận được gói tin Realtime Broadcast BID_UPDATE từ Server!
            assertTrue(broadcastReceived.await(5, TimeUnit.SECONDS),
                    "Client 1 đang xem phiên phải nhận được thông báo BID_UPDATE tức thời qua Socket!");
            SocketResponse push = pushedNotification.get();
            assertNotNull(push);
            assertEquals(ActionType.BID_UPDATE, push.getAction());
            assertEquals("AUC-NET-01", push.getAuctionId());
            assertEquals("BIDDER-02", push.getData().get("currentWinnerId"));
            assertEquals(22_000_000.0, ((Number) push.getData().get("currentHighestBid")).doubleValue());
        }
    }

    /**
     * Cho phép chạy trực tiếp bộ kiểm thử Hạ tầng mạng Socket & Giao thức JSON từ IDE/CLI.
     */
    public static void main(String[] args) throws Exception {
        SocketNetworkAndAlgorithmTest suite = new SocketNetworkAndAlgorithmTest();
        String[] testNames = {
                "1. testJsonProtocolSerializationAndDeserialization",
                "2. testClientHandlerJsonDispatchAndErrorHandling",
                "3. testEndToEndSocketServerAndRealtimeBroadcast"
        };
        Runnable[] tests = {
                suite::testJsonProtocolSerializationAndDeserialization,
                suite::testClientHandlerJsonDispatchAndErrorHandling,
                () -> {
                    try {
                        suite.testEndToEndSocketServerAndRealtimeBroadcast();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
        };

        System.out.println("==================================================================");
        System.out.println(" RUNNING SOCKET NETWORK, JSON PROTOCOL & REALTIME BROADCAST SUITE");
        System.out.println("==================================================================");
        int passed = 0;
        for (int i = 0; i < tests.length; i++) {
            suite.setUp();
            long start = System.currentTimeMillis();
            try {
                tests[i].run();
                long elapsed = System.currentTimeMillis() - start;
                System.out.printf("[PASS] %s (%d ms)%n", testNames[i], elapsed);
                passed++;
            } finally {
                suite.tearDown();
            }
        }
        System.out.println("==================================================================");
        System.out.printf(" RESULT: %d/%d TESTS PASSED SUCCESSFULLY!%n", passed, tests.length);
        System.out.println("==================================================================");
    }
}
