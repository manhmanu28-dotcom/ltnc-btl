package com.auction.server;

import com.auction.client.model.AuctionSummary;
import com.auction.client.service.AuctionListService;
import com.auction.client.service.ConnectionPreferences;
import com.auction.server.dao.UserDAO;
import com.auction.server.database.DBConnection;
import com.auction.server.model.entity.Bidder;
import com.auction.server.network.AuctionServer;
import com.auction.server.network.AuctionSocketClient;
import com.auction.server.network.LanDiscoveryService;
import com.auction.server.network.PublicTunnelService;
import com.auction.server.network.ServerEndpoint;
import com.auction.server.network.protocol.ActionType;
import com.auction.server.network.protocol.SocketRequest;
import com.auction.server.network.protocol.SocketResponse;
import com.auction.server.service.AuctionManager;
import com.auction.server.util.PasswordUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Kiểm thử tính năng kết nối dùng chung Server & Cổng (Cùng Wi-Fi / LAN và Khác Wi-Fi / Public Tunnel).
 */
class SharedServerConnectivityTest {

    @TempDir
    Path tempDir;

    private AuctionManager auctionManager;

    @BeforeEach
    void setUp() throws Exception {
        DBConnection.resetInstance();
        DBConnection.getInstance("jdbc:sqlite:" + tempDir.resolve("shared-server-test.db"));
        UserDAO users = new UserDAO();
        String passwordHash = PasswordUtil.hashPassword("shared-pass-123");
        users.create(new Bidder("USER-A", "alice_host", passwordHash, "alice@example.com", 500_000_000));
        users.create(new Bidder("USER-B", "bob_guest", passwordHash, "bob@example.com", 500_000_000));
        auctionManager = AuctionManager.getInstance();
        auctionManager.clearAll();
    }

    @AfterEach
    void tearDown() {
        auctionManager.clearAll();
        DBConnection.resetInstance();
    }

    @Test
    @DisplayName("1. Phân tích địa chỉ ServerEndpoint cho cả LAN và Public Tunnel (Pinggy/ngrok)")
    void parsesLanAndPublicTunnelEndpoints() {
        ServerEndpoint lan = ServerEndpoint.parse("192.168.1.15:8888");
        assertEquals("192.168.1.15", lan.host());
        assertEquals(8888, lan.port());
        assertFalse(lan.isLoopback());

        ServerEndpoint pinggy = ServerEndpoint.parse("tcp://rnabc-12-34.a.free.pinggy.link:43210");
        assertEquals("rnabc-12-34.a.free.pinggy.link", pinggy.host());
        assertEquals(43210, pinggy.port());
        assertEquals("rnabc-12-34.a.free.pinggy.link:43210", pinggy.toAddress());

        ServerEndpoint pastedIntoHostBox = ServerEndpoint.fromInputs("tcp://0.tcp.ap.ngrok.io:14567", "8888");
        assertEquals("0.tcp.ap.ngrok.io", pastedIntoHostBox.host());
        assertEquals(14567, pastedIntoHostBox.port());

        assertTrue(ServerEndpoint.parse("127.0.0.1", 8888).isLoopback());
        assertThrows(IllegalArgumentException.class, () -> ServerEndpoint.parse("192.168.1.15:99999"));
        assertThrows(IllegalArgumentException.class, () -> ServerEndpoint.fromInputs("", "8888"));
    }

    @Test
    @DisplayName("2. Trích xuất địa chỉ công khai từ log Pinggy/ngrok (kể cả chứa mã màu ANSI)")
    void extractsPublicEndpointFromTunnelOutput() {
        String ansiPinggyLine = "\u001B[32mtcp://rnxyz-1-2-3.a.free.pinggy.link:38123\u001B[0m";
        Optional<ServerEndpoint> endpoint = PublicTunnelService.parsePublicEndpoint(ansiPinggyLine);
        assertTrue(endpoint.isPresent());
        assertEquals("rnxyz-1-2-3.a.free.pinggy.link", endpoint.get().host());
        assertEquals(38123, endpoint.get().port());

        String ngrokLine = "Forwarding   tcp://0.tcp.ap.ngrok.io:19876 -> localhost:8888";
        Optional<ServerEndpoint> ngrokEndpoint = PublicTunnelService.parsePublicEndpoint(ngrokLine);
        assertTrue(ngrokEndpoint.isPresent());
        assertEquals("0.tcp.ap.ngrok.io", ngrokEndpoint.get().host());
        assertEquals(19876, ngrokEndpoint.get().port());

        assertTrue(PublicTunnelService.parsePublicEndpoint("Connecting to tunnel server...").isEmpty());
    }

    @Test
    @DisplayName("3. Tự động phát hiện máy chủ cùng Wi-Fi qua UDP LAN Discovery & kiểm tra PING")
    void discoversServerOverUdpAndProbesHealth() throws Exception {
        try (AuctionServer server = new AuctionServer("0.0.0.0", 0)) {
            server.start();
            try (LanDiscoveryService.Responder responder = LanDiscoveryService.startResponder(server, 0)) {
                int discoveryPort = responder.getLocalPort();
                assertTrue(discoveryPort > 0);

                List<LanDiscoveryService.DiscoveredServer> discovered =
                        LanDiscoveryService.discoverServers(discoveryPort, 1000);
                assertFalse(discovered.isEmpty(), "Phải phát hiện được AuctionServer đang chạy!");

                LanDiscoveryService.DiscoveredServer match = discovered.stream()
                        .filter(s -> s.port() == server.getLocalPort())
                        .findFirst()
                        .orElseThrow();
                assertEquals(server.getLocalPort(), match.port());

                AuctionSocketClient.ServerProbeResult probe =
                        AuctionSocketClient.probe(new ServerEndpoint("127.0.0.1", match.port()), 3000);
                assertEquals(server.getLocalPort(), probe.endpoint().port());
                assertTrue(probe.latencyMillis() >= 1);
                assertEquals(0, probe.auctionCount());
            }
        }
    }

    @Test
    @DisplayName("4. Hai Client kết nối chung 1 Server: Người A tạo phiên -> Người B nhận cập nhật Realtime tức thời")
    void multipleClientsShareSameServerAndReceiveCreatedAuctionRealtime() throws Exception {
        try (AuctionServer server = new AuctionServer("0.0.0.0", 0)) {
            server.start();
            int port = server.getLocalPort();

            CountDownLatch auctionCreatedBroadcast = new CountDownLatch(1);
            AtomicReference<AuctionSummary> receivedSummary = new AtomicReference<>();

            try (AuctionSocketClient clientA = new AuctionSocketClient("127.0.0.1", port);
                 AuctionSocketClient clientB = new AuctionSocketClient("127.0.0.1", port)) {
                clientA.connect();
                clientB.connect();

                assertTrue(clientA.sendRequest(SocketRequest.of(ActionType.LOGIN)
                        .putPayload("username", "alice_host")
                        .putPayload("password", "shared-pass-123")).isSuccess());
                assertTrue(clientB.sendRequest(SocketRequest.of(ActionType.LOGIN)
                        .putPayload("username", "bob_guest")
                        .putPayload("password", "shared-pass-123")).isSuccess());

                clientB.addRealtimeListener(response -> {
                    if (response.getAction() == ActionType.STATUS_UPDATE
                            && "AUCTION_CREATED".equals(response.getData().get("eventType"))
                            && response.getData().get("auction") instanceof Map<?, ?> auctionMap) {
                        receivedSummary.set(AuctionListService.parseAuctionSummary(auctionMap));
                        auctionCreatedBroadcast.countDown();
                    }
                });

                AuctionListService listService = new AuctionListService();
                listService.createAuction(
                        clientA,
                        "★ Butterfly Knife | Gamma Doppler Emerald",
                        "Skin CS2 · Knives / Butterfly Knife",
                        "ART",
                        1_000_000_000,
                        5_000_000,
                        60);

                assertTrue(auctionCreatedBroadcast.await(5, TimeUnit.SECONDS),
                        "Client B phải nhận được thông báo AUCTION_CREATED ngay khi Client A tạo phiên!");
                assertNotNull(receivedSummary.get());
                assertEquals("★ Butterfly Knife | Gamma Doppler Emerald", receivedSummary.get().itemTitle());
                assertEquals("RUNNING", receivedSummary.get().status());

                List<AuctionSummary> auctionsVisibleToB = listService.fetchAuctions(clientB);
                assertEquals(1, auctionsVisibleToB.size());
                assertEquals("★ Butterfly Knife | Gamma Doppler Emerald", auctionsVisibleToB.get(0).itemTitle());
            }
        }
    }

    @Test
    @DisplayName("5. Nạp cấu hình máy chủ dùng chung từ CLI --server và file properties")
    void resolvesSharedServerFromCliArgsAndConfigFile() throws Exception {
        ServerEndpoint fromCli = ConnectionPreferences.resolveInitialEndpoint(
                List.of("--server=tcp://shared.example.com:9090"));
        assertEquals("shared.example.com", fromCli.host());
        assertEquals(9090, fromCli.port());

        Path propsFile = tempDir.resolve("auction-server.properties");
        Files.writeString(propsFile, "server=192.168.1.99:8888\n");
        ServerEndpoint fromProps = ServerEndpoint.parse(
                Files.readString(propsFile).replace("server=", "").trim(), 8888);
        assertEquals("192.168.1.99", fromProps.host());
        assertEquals(8888, fromProps.port());
    }
}
