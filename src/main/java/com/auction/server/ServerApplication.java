package com.auction.server;

import com.auction.server.database.DBConnection;
import com.auction.server.network.AuctionServer;
import com.auction.server.service.AuctionManager;
import com.auction.server.service.AuctionPersistenceService;
import com.auction.server.network.GlobalServerRegistry;
import com.auction.server.network.LanDiscoveryService;
import com.auction.server.network.NetworkAddressUtil;
import com.auction.server.network.PublicTunnelService;
import com.auction.server.network.ServerDirectoryEntry;
import com.auction.server.network.ServerEndpoint;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Chạy máy chủ đấu giá dùng chung (hỗ trợ cả cùng Wi-Fi/LAN và khác Wi-Fi qua Public Tunnel + Danh bạ toàn cầu).
 *
 * Tham số hỗ trợ:
 * - [port] hoặc --port <port> : Cổng TCP lắng nghe (mặc định 8888)
 * - --name <ten-server>       : Tên hiển thị trong danh sách "Tham gia Server"
 * - --bind <ip>               : Địa chỉ bind (mặc định 0.0.0.0 - nhận mọi kết nối mạng)
 * - --public                  : Tự động mở Public TCP Tunnel (Pinggy SSH) cho người khác Wi-Fi/4G
 * - --no-discovery            : Tắt dịch vụ tự tìm máy chủ trên cùng Wi-Fi (UDP 8889)
 */
public class ServerApplication {

    public static void main(String[] args) throws Exception {
        ServerConfig config = ServerConfig.parse(args);
        DBConnection db = DBConnection.getInstance();
        System.out.println("Tài khoản & dữ liệu được lưu bền vững tại: " + db.getDatabaseDisplayPath());

        String serverId = "SRV-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String serverName = (config.serverName() == null || config.serverName().isBlank())
                ? "Server của " + NetworkAddressUtil.resolveLocalHostName()
                : config.serverName().trim();

        try (AuctionServer server = new AuctionServer(config.bindAddress(), config.port())) {
            int restoredAuctions = AuctionPersistenceService.getInstance()
                    .initializeAndLoad(AuctionManager.getInstance(), server.getActiveBidders());
            if (restoredAuctions > 0) {
                System.out.println("Đã khôi phục " + restoredAuctions + " phiên đấu giá từ cơ sở dữ liệu.");
            }
            LanDiscoveryService.Responder discoveryResponder = null;
            PublicTunnelService publicTunnel = null;
            GlobalServerRegistry registry = new GlobalServerRegistry();
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
                    System.out.println("Server vẫn đang chạy bình thường cho mạng LAN/Wi-Fi nội bộ.");
                    publicTunnel.close();
                    publicTunnel = null;
                }
            }

            List<NetworkAddressUtil.InterfaceAddressInfo> lanAddresses = NetworkAddressUtil.listLanIpv4Addresses();
            String primaryLanIp = lanAddresses.isEmpty() ? "127.0.0.1" : lanAddresses.get(0).hostAddress();
            final ServerEndpoint finalPublicEndpoint = publicEndpoint;

            boolean registryPublished = registry.announceOnline(buildDirectoryEntry(
                    serverId, serverName, primaryLanIp, actualPort, finalPublicEndpoint, server));
            heartbeat = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread t = new Thread(runnable, "Auction-Server-Registry-Heartbeat");
                t.setDaemon(true);
                return t;
            });
            heartbeat.scheduleAtFixedRate(
                    () -> registry.announceOnline(buildDirectoryEntry(
                            serverId, serverName, primaryLanIp, actualPort, finalPublicEndpoint, server)),
                    GlobalServerRegistry.DEFAULT_HEARTBEAT_INTERVAL_MS,
                    GlobalServerRegistry.DEFAULT_HEARTBEAT_INTERVAL_MS,
                    TimeUnit.MILLISECONDS);

            final LanDiscoveryService.Responder finalDiscovery = discoveryResponder;
            final PublicTunnelService finalTunnel = publicTunnel;
            final ScheduledExecutorService finalHeartbeat = heartbeat;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                finalHeartbeat.shutdownNow();
                registry.announceOffline(serverId, serverName);
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

        if (lanAddresses.isEmpty()) {
            System.out.println(" 2) Cùng Wi-Fi / LAN          : Không tìm thấy địa chỉ IPv4 LAN khả dụng.");
        } else {
            System.out.println(" 2) Cùng Wi-Fi / LAN          :");
            for (NetworkAddressUtil.InterfaceAddressInfo info : lanAddresses) {
                String tag = info.virtualAdapter() ? "[VPN/Ảo]" : "[LAN/Wi-Fi]";
                System.out.printf("      %-11s %s:%d  (%s)%n", tag, info.hostAddress(), port, info.interfaceName());
            }
        }
        if (discoveryEnabled) {
            System.out.println("    Tự động tìm kiếm LAN      : ĐANG BẬT (UDP " + LanDiscoveryService.DEFAULT_DISCOVERY_PORT + ")");
        }

        if (publicEndpoint != null) {
            System.out.println(" 3) Khác Wi-Fi / Internet     : " + publicEndpoint.toAddress());
            System.out.println("    -> Ô Server: " + publicEndpoint.host() + "  |  Ô Cổng: " + publicEndpoint.port());
        } else {
            System.out.println(" 3) Khác Wi-Fi / Internet     : Chưa bật. Chạy .\\run.ps1 Server -Public để mở địa chỉ công khai.");
        }
        System.out.println(" 4) Danh bạ 'Tham gia Server' : " + (registryPublished ? "ĐÃ ĐĂNG CÔNG KHAI (mọi người sẽ thấy ngay)" : "Chế độ cục bộ (LAN)"));
        System.out.println("-------------------------------------------------------------------------");
        System.out.println(" Mẹo Windows Firewall (nếu máy cùng Wi-Fi không vào được), chạy PowerShell Admin:");
        System.out.println("   New-NetFirewallRule -DisplayName \"Auction Server TCP " + port
                + "\" -Direction Inbound -Protocol TCP -LocalPort " + port + " -Action Allow");
        System.out.println(" Nhấn Ctrl+C để dừng Server.");
        System.out.println("=========================================================================");
    }

    record ServerConfig(String bindAddress, int port, String serverName, boolean enablePublicTunnel, boolean enableLanDiscovery) {
        static ServerConfig parse(String[] args) {
            String bind = AuctionServer.DEFAULT_BIND_ADDRESS;
            int port = AuctionServer.DEFAULT_PORT;
            String name = "";
            boolean publicTunnel = false;
            boolean lanDiscovery = true;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--public".equalsIgnoreCase(arg)) {
                    publicTunnel = true;
                } else if ("--no-discovery".equalsIgnoreCase(arg)) {
                    lanDiscovery = false;
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
            return new ServerConfig(bind, port, name, publicTunnel, lanDiscovery);
        }
    }
}
