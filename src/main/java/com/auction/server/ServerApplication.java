package com.auction.server;

import com.auction.server.database.DBConnection;
import com.auction.server.network.AuctionServer;
import com.auction.server.network.LanDiscoveryService;
import com.auction.server.network.NetworkAddressUtil;
import com.auction.server.network.PublicTunnelService;
import com.auction.server.network.ServerEndpoint;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Chạy máy chủ đấu giá dùng chung (hỗ trợ cả cùng Wi-Fi/LAN và khác Wi-Fi qua Public Tunnel).
 *
 * Tham số hỗ trợ:
 * - [port] hoặc --port <port> : Cổng TCP lắng nghe (mặc định 8888)
 * - --bind <ip>               : Địa chỉ bind (mặc định 0.0.0.0 - nhận mọi kết nối mạng)
 * - --public                  : Tự động mở Public TCP Tunnel (Pinggy SSH) cho người khác Wi-Fi/4G
 * - --no-discovery            : Tắt dịch vụ tự tìm máy chủ trên cùng Wi-Fi (UDP 8889)
 */
public class ServerApplication {

    public static void main(String[] args) throws Exception {
        ServerConfig config = ServerConfig.parse(args);
        DBConnection.getInstance();
        System.out.println("Tài khoản được lưu tại: " + Path.of("auction.db").toAbsolutePath());

        try (AuctionServer server = new AuctionServer(config.bindAddress(), config.port())) {
            LanDiscoveryService.Responder discoveryResponder = null;
            PublicTunnelService publicTunnel = null;
            CountDownLatch keepAlive = new CountDownLatch(1);

            server.start();
            int actualPort = server.getLocalPort();

            if (config.enableLanDiscovery()) {
                try {
                    discoveryResponder = LanDiscoveryService.startResponder(server);
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

            final LanDiscoveryService.Responder finalDiscovery = discoveryResponder;
            final PublicTunnelService finalTunnel = publicTunnel;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (finalTunnel != null) {
                    finalTunnel.close();
                }
                if (finalDiscovery != null) {
                    finalDiscovery.close();
                }
                server.close();
                keepAlive.countDown();
            }, "Auction-Server-Shutdown"));

            printConnectionSummary(actualPort, config.bindAddress(), discoveryResponder != null, publicEndpoint);
            keepAlive.await();
        }
    }

    private static void printConnectionSummary(int port,
                                               String bindAddress,
                                               boolean discoveryEnabled,
                                               ServerEndpoint publicEndpoint) {
        System.out.println("=========================================================================");
        System.out.println(" AUCTION SERVER ĐÃ SẴN SÀNG  (Bind: " + bindAddress + ":" + port + ")");
        System.out.println("=========================================================================");
        System.out.println(" 1) Cùng máy (Local)          : 127.0.0.1:" + port);

        List<NetworkAddressUtil.InterfaceAddressInfo> lanAddresses = NetworkAddressUtil.listLanIpv4Addresses();
        if (lanAddresses.isEmpty()) {
            System.out.println(" 2) Cùng Wi-Fi / LAN          : Không tìm thấy địa chỉ IPv4 LAN khả dụng.");
        } else {
            System.out.println(" 2) Cùng Wi-Fi / LAN          : Gửi 1 trong các địa chỉ sau (hoặc bấm 'Tìm cùng Wi-Fi' trên Client):");
            for (NetworkAddressUtil.InterfaceAddressInfo info : lanAddresses) {
                String tag = info.virtualAdapter() ? "[VPN/Ảo]" : "[LAN/Wi-Fi]";
                System.out.printf("      %-11s %s:%d  (%s)%n", tag, info.hostAddress(), port, info.interfaceName());
            }
        }
        if (discoveryEnabled) {
            System.out.println("    Tự động tìm kiếm LAN      : ĐANG BẬT (UDP " + LanDiscoveryService.DEFAULT_DISCOVERY_PORT + ")");
        }

        if (publicEndpoint != null) {
            System.out.println(" 3) Khác Wi-Fi / Internet     : " + publicEndpoint.toAddress() + "  <-- Gửi dòng này cho người khác mạng!");
            System.out.println("    -> Ô Server: " + publicEndpoint.host() + "  |  Ô Cổng: " + publicEndpoint.port());
        } else {
            System.out.println(" 3) Khác Wi-Fi / Internet     : Chưa bật. Chạy .\\run.cmd Server -Public (hoặc --public) để mở địa chỉ công khai.");
        }
        System.out.println("-------------------------------------------------------------------------");
        System.out.println(" Mẹo Windows Firewall (nếu máy cùng Wi-Fi không vào được), chạy PowerShell Admin:");
        System.out.println("   New-NetFirewallRule -DisplayName \"Auction Server TCP " + port
                + "\" -Direction Inbound -Protocol TCP -LocalPort " + port + " -Action Allow");
        System.out.println(" Nhấn Ctrl+C để dừng Server.");
        System.out.println("=========================================================================");
    }

    record ServerConfig(String bindAddress, int port, boolean enablePublicTunnel, boolean enableLanDiscovery) {
        static ServerConfig parse(String[] args) {
            String bind = AuctionServer.DEFAULT_BIND_ADDRESS;
            int port = AuctionServer.DEFAULT_PORT;
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
                } else if (!arg.startsWith("-")) {
                    port = Integer.parseInt(arg);
                }
            }
            return new ServerConfig(bind, port, publicTunnel, lanDiscovery);
        }
    }
}
