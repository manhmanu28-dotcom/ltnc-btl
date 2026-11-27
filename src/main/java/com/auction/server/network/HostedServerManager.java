package com.auction.server.network;

import com.auction.server.database.DBConnection;
import com.auction.server.service.AuctionManager;
import com.auction.server.service.AuctionPersistenceService;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * HostedServerManager: Quản lý vòng đời máy chủ đấu giá được tạo trực tiếp từ giao diện đăng nhập
 * ("Tạo Server") hoặc từ dòng lệnh.
 *
 * Khi tạo một Server:
 * 1. Khởi tạo SQLite DBConnection và mở AuctionServer trên 0.0.0.0:<port>.
 * 2. Bật LanDiscoveryService (UDP 8889) để mọi người CÙNG Wi-Fi tự tìm thấy ngay.
 * 3. Nếu bật chế độ công khai (mặc định BẬT): mở PublicTunnelService (Pinggy SSH) lấy địa chỉ Internet
 *    để người KHÁC Wi-Fi / 4G kết nối được.
 * 4. Đăng ký lên GlobalServerRegistry kèm heartbeat định kỳ để người khác mạng bấm "Tham gia Server"
 *    nhìn thấy ngay Server này trong danh sách mà không cần gõ tay IP/Cổng.
 */
public final class HostedServerManager {

    public record HostedServerInfo(
            ServerDirectoryEntry entry,
            boolean lanDiscoveryActive,
            boolean registryPublished,
            String publicTunnelWarning) {
    }

    private static final HostedServerManager INSTANCE = new HostedServerManager();

    private AuctionServer auctionServer;
    private LanDiscoveryService.Responder lanDiscoveryResponder;
    private PublicTunnelService publicTunnelService;
    private GlobalServerRegistry registry;
    private ScheduledExecutorService heartbeatScheduler;
    private String serverId;
    private String serverName;
    private String primaryLanHost;
    private ServerEndpoint publicEndpoint;
    private String publicTunnelWarning;
    private boolean registryPublished;
    private boolean shutdownHookInstalled;

    private HostedServerManager() {
    }

    public static HostedServerManager getInstance() {
        return INSTANCE;
    }

    /**
     * Tạo và khởi chạy một máy chủ đấu giá mới.
     *
     * @param requestedName      Tên hiển thị của Server (nếu trống sẽ dùng tên máy tính)
     * @param requestedPort      Cổng TCP mong muốn (nếu <= 0 hoặc đang bận khi autoFallback=true sẽ tự tìm cổng trống)
     * @param enablePublicAccess true để mở địa chỉ Internet cho người khác Wi-Fi/4G
     */
    public synchronized HostedServerInfo startServer(String requestedName,
                                                     int requestedPort,
                                                     boolean enablePublicAccess) throws IOException {
        return startServer(requestedName, requestedPort, enablePublicAccess, new GlobalServerRegistry());
    }

    public synchronized HostedServerInfo startServer(String requestedName,
                                                     int requestedPort,
                                                     boolean enablePublicAccess,
                                                     GlobalServerRegistry customRegistry) throws IOException {
        if (isRunning()) {
            return currentInfo();
        }
        installShutdownHookIfNeeded();

        DBConnection.getInstance();
        int portToUse = chooseAvailablePort(requestedPort);

        String resolvedName = (requestedName == null || requestedName.isBlank())
                ? "Server của " + NetworkAddressUtil.resolveLocalHostName()
                : requestedName.trim();
        String generatedId = "SRV-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        AuctionServer server = new AuctionServer(AuctionServer.DEFAULT_BIND_ADDRESS, portToUse);
        AuctionPersistenceService.getInstance()
                .initializeAndLoad(AuctionManager.getInstance(), server.getActiveBidders());
        server.start();
        int actualPort = server.getLocalPort();

        List<NetworkAddressUtil.InterfaceAddressInfo> lanAddresses = NetworkAddressUtil.listLanIpv4Addresses();
        String bestLanIp = lanAddresses.isEmpty() ? "127.0.0.1" : lanAddresses.get(0).hostAddress();

        LanDiscoveryService.Responder responder = null;
        try {
            responder = LanDiscoveryService.startResponder(
                    server,
                    LanDiscoveryService.DEFAULT_DISCOVERY_PORT,
                    resolvedName,
                    generatedId);
        } catch (IOException ignored) {
            // Nếu máy này đã có một server khác giữ cổng UDP 8889, server mới vẫn hoạt động qua TCP & Registry
        }

        PublicTunnelService tunnel = null;
        ServerEndpoint publicEp = null;
        String tunnelWarn = null;
        if (enablePublicAccess) {
            tunnel = new PublicTunnelService();
            try {
                publicEp = tunnel.startPinggyTunnel(actualPort, 20_000L);
            } catch (IOException error) {
                tunnelWarn = error.getMessage();
                tunnel.close();
                tunnel = null;
            }
        }

        this.auctionServer = server;
        this.lanDiscoveryResponder = responder;
        this.publicTunnelService = tunnel;
        this.registry = customRegistry != null ? customRegistry : new GlobalServerRegistry();
        this.serverId = generatedId;
        this.serverName = resolvedName;
        this.primaryLanHost = bestLanIp;
        this.publicEndpoint = publicEp;
        this.publicTunnelWarning = tunnelWarn;

        this.registryPublished = this.registry.announceOnline(buildCurrentDirectoryEntry());
        startHeartbeatScheduler();

        return currentInfo();
    }

    private void startHeartbeatScheduler() {
        stopHeartbeatScheduler();
        this.heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Auction-Registry-Heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        this.heartbeatScheduler.scheduleAtFixedRate(() -> {
            ServerDirectoryEntry snapshot = getHostedDirectoryEntry();
            if (snapshot != null && registry != null) {
                registryPublished = registry.announceOnline(snapshot);
            }
        }, GlobalServerRegistry.DEFAULT_HEARTBEAT_INTERVAL_MS,
                GlobalServerRegistry.DEFAULT_HEARTBEAT_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
    }

    private void stopHeartbeatScheduler() {
        if (this.heartbeatScheduler != null) {
            this.heartbeatScheduler.shutdownNow();
            this.heartbeatScheduler = null;
        }
    }

    public synchronized boolean isRunning() {
        return auctionServer != null && auctionServer.isRunning();
    }

    public synchronized ServerDirectoryEntry getHostedDirectoryEntry() {
        if (!isRunning()) {
            return null;
        }
        return buildCurrentDirectoryEntry();
    }

    public synchronized HostedServerInfo currentInfo() {
        if (!isRunning()) {
            return null;
        }
        return new HostedServerInfo(
                buildCurrentDirectoryEntry(),
                lanDiscoveryResponder != null && lanDiscoveryResponder.isRunning(),
                registryPublished,
                publicTunnelWarning);
    }

    private ServerDirectoryEntry buildCurrentDirectoryEntry() {
        int port = auctionServer.getLocalPort();
        String pubHost = publicEndpoint != null ? publicEndpoint.host() : "";
        int pubPort = publicEndpoint != null ? publicEndpoint.port() : 0;
        return new ServerDirectoryEntry(
                serverId,
                serverName,
                primaryLanHost,
                port,
                pubHost,
                pubPort,
                auctionServer.getAuctionCount(),
                auctionServer.getConnectedClientCount(),
                System.currentTimeMillis(),
                ServerDirectoryEntry.Source.LOCAL_HOSTED);
    }

    public synchronized void stopServer() {
        stopHeartbeatScheduler();
        if (registry != null && serverId != null) {
            registry.announceOffline(serverId, serverName);
        }
        if (publicTunnelService != null) {
            publicTunnelService.close();
            publicTunnelService = null;
        }
        if (lanDiscoveryResponder != null) {
            lanDiscoveryResponder.close();
            lanDiscoveryResponder = null;
        }
        if (auctionServer != null) {
            auctionServer.close();
            auctionServer = null;
        }
        serverId = null;
        serverName = null;
        primaryLanHost = null;
        publicEndpoint = null;
        publicTunnelWarning = null;
        registryPublished = false;
    }

    private int chooseAvailablePort(int requestedPort) throws IOException {
        int candidate = (requestedPort >= 1 && requestedPort <= 65535)
                ? requestedPort
                : AuctionServer.DEFAULT_PORT;
        if (isTcpPortAvailable(candidate)) {
            return candidate;
        }
        // Nếu cổng mặc định 8888 đang bị chiếm, tự tìm cổng trống kế tiếp (8889..8905)
        for (int p = Math.max(8890, candidate + 1); p <= Math.min(65535, candidate + 20); p++) {
            if (isTcpPortAvailable(p)) {
                return p;
            }
        }
        return 0; // Để hệ điều hành tự cấp cổng trống
    }

    private static boolean isTcpPortAvailable(int port) {
        try (ServerSocket probe = new ServerSocket(port)) {
            probe.setReuseAddress(true);
            return true;
        } catch (IOException busy) {
            return false;
        }
    }

    private void installShutdownHookIfNeeded() {
        if (!shutdownHookInstalled) {
            shutdownHookInstalled = true;
            Runtime.getRuntime().addShutdownHook(new Thread(this::stopServer, "Auction-Hosted-Server-Shutdown"));
        }
    }
}
