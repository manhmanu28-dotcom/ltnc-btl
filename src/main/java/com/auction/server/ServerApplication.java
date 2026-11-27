package com.auction.server;

import com.auction.server.database.DBConnection;
import com.auction.server.network.AuctionServer;
import com.auction.server.network.GlobalServerRegistry;
import com.auction.server.network.LanDiscoveryService;
import com.auction.server.network.NetworkAddressUtil;
import com.auction.server.network.PublicTunnelService;
import com.auction.server.network.ServerDirectoryEntry;
import com.auction.server.network.ServerEndpoint;
import com.auction.server.service.AuctionManager;
import com.auction.server.service.AuctionPersistenceService;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Chạy máy chủ đấu giá tập trung (hỗ trợ deploy 24/7 trên Cloud Docker/Railway/VPS
 * hoặc chạy cục bộ trên LAN/Public Tunnel).
 *
 * Tham số hỗ trợ:
 * - [port] hoặc --port <port> : Cổng TCP lắng nghe (ưu tiên CLI -> AUCTION_PORT -> PORT -> 8888)
 * - --name <ten-server>       : Tên hiển thị của máy chủ
 * - --bind <ip>               : Địa chỉ bind (mặc định 0.0.0.0 - nhận mọi kết nối mạng)
 * - --public                  : Tự động mở Public TCP Tunnel (Pinggy SSH) khi chạy trên máy cá nhân
 * - --no-discovery            : Tắt UDP LAN Discovery (dùng khi deploy lên Cloud/Docker)
 * - --no-registry             : Tắt đăng ký danh bạ động (dùng khi chạy Server cố định 24/7)
 */
public class ServerApplication {

    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
        ServerConfig config = ServerConfig.parse(args);
        DBConnection db = DBConnection.getInstance();
        System.out.println("Tài khoản & dữ liệu được lưu bền vững tại: " + db.getDatabaseDisplayPath());

        String serverId = "SRV-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String serverName = (config.serverName() == null || config.serverName().isBlank())
                ? "BIDION Server (" + NetworkAddressUtil.resolveLocalHostName() + ")"
                : config.serverName().trim();

        try (AuctionServer server = new AuctionServer(config.bindAddress(), config.port())) {
            int restoredAuctions = AuctionPersistenceService.getInstance()
                    .initializeAndLoad(AuctionManager.getInstance(), server.getActiveBidders());
            if (restoredAuctions > 0) {
                System.out.println("Đã khôi phục " + restoredAuctions + " phiên đấu giá từ cơ sở dữ liệu.");
            }
            LanDiscoveryService.Responder discoveryResponder = null;
            PublicTunnelService publicTunnel = null;
            GlobalServerRegistry registry = config.enableGlobalRegistry() ? new GlobalServerRegistry() : null;
            ScheduledExecutorService heartbeat = null;
            CountDownLatch keepAlive = new CountDownLatch(1);

            server.start();
            int actualPort = server.getLocalPort();

            if (config.enableLanDiscovery()) {
                try {
                    discoveryResponder = LanDiscoveryService.startResponder(
                            server,
                            LanDiscoveryService.DEFAULT_DISCOVERY_PORT,
                            serverName,
                            serverId);
                } catch (IOException error) {
                    System.out.println("[Cảnh báo] Không thể bật tự tìm máy chủ LAN (UDP "
                            + LanDiscoveryService.DEFAULT_DISCOVERY_PORT + "): " + error.getMessage());
                }
            }

            ServerEndpoint publicEndpoint = null;
            if (config.enablePublicTunnel()) {
                System.out.println("Đang khởi tạo Public TCP Tunnel cho kết nối khác Wi-Fi (Pinggy SSH)...");
                publicTunnel = new PublicTunnelService();
                try {
                    publicEndpoint = publicTunnel.startPinggyTunnel(actualPort, 20_000L);
                } catch (IOException error) {
                    System.out.println("[Cảnh báo] " + error.getMessage());
                    System.out.println("Server vẫn đang chạy bình thường trên cổng TCP " + actualPort + ".");
                    publicTunnel.close();
                    publicTunnel = null;
                }
            }

            List<NetworkAddressUtil.InterfaceAddressInfo> lanAddresses = NetworkAddressUtil.listLanIpv4Addresses();
            String primaryLanIp = lanAddresses.isEmpty() ? "127.0.0.1" : lanAddresses.get(0).hostAddress();
            final ServerEndpoint finalPublicEndpoint = publicEndpoint;

            boolean registryPublished = false;
            if (registry != null) {
                registryPublished = registry.announceOnline(buildDirectoryEntry(
                        serverId, serverName, primaryLanIp, actualPort, finalPublicEndpoint, server));
                heartbeat = Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread t = new Thread(runnable, "Auction-Server-Registry-Heartbeat");
                    t.setDaemon(true);
                    return t;
                });
                final GlobalServerRegistry finalRegistry = registry;
                heartbeat.scheduleAtFixedRate(
                        () -> finalRegistry.announceOnline(buildDirectoryEntry(
                                serverId, serverName, primaryLanIp, actualPort, finalPublicEndpoint, server)),
                        GlobalServerRegistry.DEFAULT_HEARTBEAT_INTERVAL_MS,
                        GlobalServerRegistry.DEFAULT_HEARTBEAT_INTERVAL_MS,
                        TimeUnit.MILLISECONDS);
            }

            final LanDiscoveryService.Responder finalDiscovery = discoveryResponder;
            final PublicTunnelService finalTunnel = publicTunnel;
            final ScheduledExecutorService finalHeartbeat = heartbeat;
            final GlobalServerRegistry finalRegistry = registry;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (finalHeartbeat != null) {
                    finalHeartbeat.shutdownNow();
                }
                if (finalRegistry != null) {
                    finalRegistry.announceOffline(serverId, serverName);
                }
                if (finalTunnel != null) {
                    finalTunnel.close();
                }
                if (finalDiscovery != null) {
                    finalDiscovery.close();
                }
                server.close();
                keepAlive.countDown();
            }, "Auction-Server-Shutdown"));

            printConnectionSummary(serverName, actualPort, config.bindAddress(), lanAddresses,
                    discoveryResponder != null, registryPublished, publicEndpoint);
            keepAlive.await();
        }
    }

    private static ServerDirectoryEntry buildDirectoryEntry(String serverId,
                                                            String serverName,
                                                            String lanIp,
                                                            int tcpPort,
                                                            ServerEndpoint publicEndpoint,
                                                            AuctionServer server) {
        return new ServerDirectoryEntry(
                serverId,
                serverName,
                lanIp,
                tcpPort,
                publicEndpoint != null ? publicEndpoint.host() : "",
                publicEndpoint != null ? publicEndpoint.port() : 0,
                server.getAuctionCount(),
                server.getConnectedClientCount(),
                System.currentTimeMillis(),
                publicEndpoint != null ? ServerDirectoryEntry.Source.LAN_AND_INTERNET : ServerDirectoryEntry.Source.LAN);
    }

    private static void printConnectionSummary(String serverName,
                                               int port,
                                               String bindAddress,
                                               List<NetworkAddressUtil.InterfaceAddressInfo> lanAddresses,
                                               boolean discoveryEnabled,
                                               boolean registryPublished,
                                               ServerEndpoint publicEndpoint) {
        System.out.println("=========================================================================");
        System.out.println(" BIDION AUCTION SERVER ĐÃ SẴN SÀNG");
        System.out.println(" Tên Server : " + serverName + "  (Bind: " + bindAddress + ":" + port + ")");
        System.out.println("=========================================================================");
        System.out.println(" 1) Cùng máy (Local)          : 127.0.0.1:" + port);

        if (!lanAddresses.isEmpty()) {
            System.out.println(" 2) Mạng LAN / Nội bộ         :");
            for (NetworkAddressUtil.InterfaceAddressInfo info : lanAddresses) {
                String tag = info.virtualAdapter() ? "[VPN/Ảo]" : "[LAN/Wi-Fi]";
                System.out.printf("      %-11s %s:%d  (%s)%n", tag, info.hostAddress(), port, info.interfaceName());
            }
        }
        if (discoveryEnabled) {
            System.out.println("    Tự động tìm kiếm LAN      : ĐANG BẬT (UDP " + LanDiscoveryService.DEFAULT_DISCOVERY_PORT + ")");
        }
        if (publicEndpoint != null) {
            System.out.println(" 3) Đường hầm công khai       : " + publicEndpoint.toAddress());
        }
        if (registryPublished) {
            System.out.println(" 4) Danh bạ trực tuyến        : ĐÃ ĐĂNG CÔNG KHAI");
        }
        System.out.println("-------------------------------------------------------------------------");
        System.out.println(" Server đang lắng nghe kết nối TCP trên cổng " + port + ". Nhấn Ctrl+C để dừng.");
        System.out.println("=========================================================================");
    }

    record ServerConfig(String bindAddress,
                        int port,
                        String serverName,
                        boolean enablePublicTunnel,
                        boolean enableLanDiscovery,
                        boolean enableGlobalRegistry) {

        static ServerConfig parse(String[] args) {
            String bind = AuctionServer.DEFAULT_BIND_ADDRESS;
            int port = resolveDefaultPort();
            String name = "";
            boolean publicTunnel = false;
            boolean lanDiscovery = true;
            boolean globalRegistry = true;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--public".equalsIgnoreCase(arg)) {
                    publicTunnel = true;
                } else if ("--no-discovery".equalsIgnoreCase(arg)) {
                    lanDiscovery = false;
                } else if ("--no-registry".equalsIgnoreCase(arg)) {
                    globalRegistry = false;
                } else if ("--port".equalsIgnoreCase(arg) && i + 1 < args.length) {
                    port = Integer.parseInt(args[++i]);
                } else if (arg.startsWith("--port=")) {
                    port = Integer.parseInt(arg.substring("--port=".length()));
                } else if ("--bind".equalsIgnoreCase(arg) && i + 1 < args.length) {
                    bind = args[++i];
                } else if (arg.startsWith("--bind=")) {
                    bind = arg.substring("--bind=".length());
                } else if ("--name".equalsIgnoreCase(arg) && i + 1 < args.length) {
                    name = args[++i];
                } else if (arg.startsWith("--name=")) {
                    name = arg.substring("--name=".length());
                } else if (!arg.startsWith("-")) {
                    port = Integer.parseInt(arg);
                }
            }
            return new ServerConfig(bind, port, name, publicTunnel, lanDiscovery, globalRegistry);
        }

        private static int resolveDefaultPort() {
            for (String envName : new String[]{"AUCTION_PORT", "PORT"}) {
                String raw = System.getenv(envName);
                if (raw != null && !raw.isBlank()) {
                    try {
                        int parsed = Integer.parseInt(raw.trim());
                        if (parsed >= 1 && parsed <= 65535) {
                            return parsed;
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            return AuctionServer.DEFAULT_PORT;
        }
    }
}
