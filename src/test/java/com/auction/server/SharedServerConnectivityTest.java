package com.auction.server;

import com.auction.client.model.AuctionSummary;
import com.auction.client.service.AuctionListService;
import com.auction.client.service.ConnectionPreferences;
import com.auction.server.dao.UserDAO;
import com.auction.server.database.DBConnection;
import com.auction.server.model.entity.Bidder;
import com.auction.server.network.AuctionServer;
import com.auction.server.network.AuctionSocketClient;
import com.auction.server.network.GlobalServerRegistry;
import com.auction.server.network.HostedServerManager;
import com.auction.server.network.LanDiscoveryService;
import com.auction.server.network.PublicTunnelService;
import com.auction.server.network.ServerDirectoryEntry;
import com.auction.server.network.ServerEndpoint;
import com.auction.server.network.protocol.ActionType;
import com.auction.server.network.protocol.SocketRequest;
import com.auction.server.network.protocol.SocketResponse;
import com.auction.server.service.AuctionManager;
import com.auction.server.util.PasswordUtil;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
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
        HostedServerManager.getInstance().stopServer();
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

    @Test
    @DisplayName("6. Gộp trùng lặp 1 máy chủ khi có nhiều card mạng (VirtualBox 192.168.56.1 + Wi-Fi 192.168.0.106 + 127.0.0.1)")
    void deduplicatesSameServerAcrossVirtualBoxWifiAndLoopback() {
        List<LanDiscoveryService.DiscoveredServer> raw = List.of(
                new LanDiscoveryService.DiscoveredServer("192.168.56.1", 8888, "LAPTOP-BBUJK48M", 0, 0),
                new LanDiscoveryService.DiscoveredServer("127.0.0.1", 8888, "LAPTOP-BBUJK48M", 0, 0),
                new LanDiscoveryService.DiscoveredServer("192.168.0.106", 8888, "LAPTOP-BBUJK48M", 0, 0)
        );
        List<LanDiscoveryService.DiscoveredServer> deduplicated = LanDiscoveryService.deduplicateServers(raw);
        assertEquals(1, deduplicated.size(), "Cùng 1 máy chủ chỉ được hiển thị đúng 1 dòng!");
        assertEquals("192.168.0.106", deduplicated.get(0).host(),
                "Phải ưu tiên IP Wi-Fi thực thay vì VirtualBox Host-Only 192.168.56.1 hay 127.0.0.1!");
    }

    @Test
    @DisplayName("7. GlobalServerRegistry: Đăng ONLINE, lấy danh sách tất cả Server và gỡ ngay khi OFFLINE")
    void publishesFetchesAndRemovesServersThroughGlobalRegistry() throws Exception {
        List<String> storedMessages = new CopyOnWriteArrayList<>();
        HttpServer mockNtfy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockNtfy.createContext("/bidion-test", exchange -> {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                storedMessages.add(body);
                byte[] ok = "{\"event\":\"message\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, ok.length);
                exchange.getResponseBody().write(ok);
            } else {
                StringBuilder ndjson = new StringBuilder();
                for (String msg : storedMessages) {
                    String escaped = msg.replace("\\", "\\\\").replace("\"", "\\\"");
                    ndjson.append("{\"event\":\"message\",\"message\":\"").append(escaped).append("\"}\n");
                }
                byte[] out = ndjson.toString().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, out.length);
                exchange.getResponseBody().write(out);
            }
            exchange.close();
        });
        mockNtfy.start();

        try {
            String baseUrl = "http://127.0.0.1:" + mockNtfy.getAddress().getPort() + "/bidion-test";
            GlobalServerRegistry registry = new GlobalServerRegistry(baseUrl);

            ServerDirectoryEntry hostA = new ServerDirectoryEntry(
                    "SRV-HOST-A", "Phòng của Việt", "192.168.0.106", 8888,
                    "rnaaa.a.free.pinggy.link", 41001, 2, 3,
                    System.currentTimeMillis(), ServerDirectoryEntry.Source.LOCAL_HOSTED);
            ServerDirectoryEntry hostB = new ServerDirectoryEntry(
                    "SRV-HOST-B", "Phòng của Mạnh", "10.0.0.15", 8888,
                    "rnbbb.a.free.pinggy.link", 42002, 5, 8,
                    System.currentTimeMillis(), ServerDirectoryEntry.Source.INTERNET);

            assertTrue(registry.announceOnline(hostA));
            assertTrue(registry.announceOnline(hostB));

            List<ServerDirectoryEntry> listed = registry.fetchOnlineServers();
            assertEquals(2, listed.size(), "Phải hiển thị đầy đủ cả 2 Server đã được tạo!");

            assertTrue(registry.announceOffline("SRV-HOST-B", "Phòng của Mạnh"));
            List<ServerDirectoryEntry> afterStop = registry.fetchOnlineServers();
            assertEquals(1, afterStop.size(), "Server đã OFFLINE phải bị gỡ ngay khỏi danh sách!");
            assertEquals("SRV-HOST-A", afterStop.get(0).serverId());
            assertEquals("rnaaa.a.free.pinggy.link", afterStop.get(0).publicHost());
            assertEquals(41001, afterStop.get(0).publicPort());
        } finally {
            mockNtfy.stop(0);
        }
    }

    @Test
    @DisplayName("8. Gộp danh sách Server từ 3 nguồn: Máy bạn tạo + Cùng Wi-Fi (LAN) + Khác Wi-Fi (Internet)")
    void mergesLocalLanAndInternetServersCleanly() {
        long now = System.currentTimeMillis();
        ServerDirectoryEntry local = new ServerDirectoryEntry(
                "SRV-ME", "Phòng của Tôi", "192.168.0.106", 8888,
                "rnme.a.free.pinggy.link", 40001, 1, 1, now, ServerDirectoryEntry.Source.LOCAL_HOSTED);

        List<LanDiscoveryService.DiscoveredServer> lan = List.of(
                new LanDiscoveryService.DiscoveredServer("192.168.0.106", 8888, "Phòng của Tôi", 1, 1, "SRV-ME"),
                new LanDiscoveryService.DiscoveredServer("192.168.0.112", 8888, "Phòng Cùng Wi-Fi", 3, 4, "SRV-LAN")
        );
        List<ServerDirectoryEntry> global = List.of(
                new ServerDirectoryEntry("SRV-LAN", "Phòng Cùng Wi-Fi", "192.168.0.112", 8888,
                        "rnlan.a.free.pinggy.link", 40002, 3, 4, now, ServerDirectoryEntry.Source.INTERNET),
                new ServerDirectoryEntry("SRV-REMOTE", "Phòng Khác Wi-Fi", "192.168.50.9", 8888,
                        "rnremote.a.free.pinggy.link", 40003, 2, 5, now, ServerDirectoryEntry.Source.INTERNET)
        );

        List<ServerDirectoryEntry> merged = GlobalServerRegistry.mergeServers(local, lan, global);
        assertEquals(3, merged.size(), "Phải hiển thị đủ 3 Server khác nhau và không bị trùng!");
        assertEquals("SRV-ME", merged.get(0).serverId());
        assertEquals(ServerDirectoryEntry.Source.LOCAL_HOSTED, merged.get(0).source());
        assertEquals("127.0.0.1", merged.get(0).preferredEndpoint().host());

        ServerDirectoryEntry lanEntry = merged.stream().filter(e -> e.serverId().equals("SRV-LAN")).findFirst().orElseThrow();
        assertEquals(ServerDirectoryEntry.Source.LAN_AND_INTERNET, lanEntry.source());
        assertEquals("192.168.0.112", lanEntry.preferredEndpoint().host(), "Cùng Wi-Fi phải ưu tiên IP LAN trực tiếp!");

        ServerDirectoryEntry remoteEntry = merged.stream().filter(e -> e.serverId().equals("SRV-REMOTE")).findFirst().orElseThrow();
        assertEquals(ServerDirectoryEntry.Source.INTERNET, remoteEntry.source());
        assertEquals("rnremote.a.free.pinggy.link", remoteEntry.preferredEndpoint().host(),
                "Khác Wi-Fi phải ưu tiên địa chỉ công khai Internet!");
    }

    @Test
    @DisplayName("9. HostedServerManager: Tạo Server ngay trong app -> đăng ký Registry -> probe kết nối thành công")
    void startsHostedServerAndProbesBestEndpoint() throws Exception {
        List<String> storedMessages = new CopyOnWriteArrayList<>();
        HttpServer mockNtfy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockNtfy.createContext("/hosted-test", exchange -> {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                storedMessages.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] ok = "ok".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, ok.length);
                exchange.getResponseBody().write(ok);
            } else {
                StringBuilder ndjson = new StringBuilder();
                for (String msg : storedMessages) {
                    String escaped = msg.replace("\\", "\\\\").replace("\"", "\\\"");
                    ndjson.append("{\"event\":\"message\",\"message\":\"").append(escaped).append("\"}\n");
                }
                byte[] out = ndjson.toString().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, out.length);
                exchange.getResponseBody().write(out);
            }
            exchange.close();
        });
        mockNtfy.start();

        try {
            GlobalServerRegistry registry = new GlobalServerRegistry(
                    "http://127.0.0.1:" + mockNtfy.getAddress().getPort() + "/hosted-test");
            HostedServerManager manager = HostedServerManager.getInstance();
            HostedServerManager.HostedServerInfo info = manager.startServer("Phòng Test Nội Bộ", 0, false, registry);

            assertTrue(manager.isRunning());
            assertTrue(info.registryPublished());
            assertEquals("Phòng Test Nội Bộ", info.entry().serverName());

            AuctionSocketClient.ServerProbeResult probe =
                    AuctionSocketClient.probeBestEndpoint(info.entry(), 3000);
            assertEquals(info.entry().lanPort(), probe.endpoint().port());

            assertEquals(1, registry.fetchOnlineServers().size());

            manager.stopServer();
            assertFalse(manager.isRunning());
            assertTrue(registry.fetchOnlineServers().isEmpty(), "Khi dừng Server phải gỡ khỏi Registry ngay!");
        } finally {
            mockNtfy.stop(0);
        }
    }
}
