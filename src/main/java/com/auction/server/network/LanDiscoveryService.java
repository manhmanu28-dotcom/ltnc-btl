package com.auction.server.network;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;

/**
 * LanDiscoveryService: Tự động phát hiện AuctionServer trong cùng mạng Wi-Fi / LAN qua UDP Broadcast.
 *
 * Cơ chế hoạt động:
 * - Phía Server chạy {@link Responder} lắng nghe gói UDP trên cổng mặc định 8889.
 * - Phía Client gọi {@link #discoverServers(int)} để gửi gói tìm kiếm "AUCTION_DISCOVER_V1".
 * - Server phản hồi ngay cổng TCP thực tế, số phiên đấu giá và số người đang trực tuyến.
 * - Client đọc trực tiếp IP từ gói UDP trả về nên luôn lấy đúng địa chỉ IPv4 LAN của máy chủ.
 */
public final class LanDiscoveryService {

    public static final int DEFAULT_DISCOVERY_PORT = 8889;
    public static final String DISCOVER_MAGIC = "AUCTION_DISCOVER_V1";
    public static final String OFFER_PREFIX = "AUCTION_OFFER_V1|";

    public record DiscoveredServer(
            String host,
            int port,
            String serverName,
            int auctionCount,
            int connectedClients) {

        public ServerEndpoint toEndpoint() {
            return new ServerEndpoint(host, port);
        }

        public String displayLabel() {
            return String.format("%s (%s:%d · %d phiên · %d online)",
                    serverName, host, port, auctionCount, connectedClients);
        }
    }

    private LanDiscoveryService() {
    }

    /**
     * Khởi chạy bộ phản hồi LAN Discovery cho một {@link AuctionServer}.
     */
    public static Responder startResponder(AuctionServer server) throws IOException {
        return startResponder(server, DEFAULT_DISCOVERY_PORT);
    }

    public static Responder startResponder(AuctionServer server, int discoveryPort) throws IOException {
        if (server == null) {
            throw new IllegalArgumentException("AuctionServer không được null!");
        }
        return new Responder(
                discoveryPort,
                server::getLocalPort,
                server::getAuctionCount,
                server::getConnectedClientCount,
                NetworkAddressUtil.resolveLocalHostName()
        );
    }

    /**
     * Quét tìm tất cả AuctionServer trong cùng mạng Wi-Fi / LAN trên cổng UDP mặc định 8889.
     */
    public static List<DiscoveredServer> discoverServers(int timeoutMillis) {
        return discoverServers(DEFAULT_DISCOVERY_PORT, timeoutMillis);
    }

    /**
     * Quét tìm tất cả AuctionServer đang phản hồi trên cổng UDP chỉ định.
     */
    public static List<DiscoveredServer> discoverServers(int discoveryPort, int timeoutMillis) {
        int safeTimeout = Math.max(250, timeoutMillis);
        Map<String, DiscoveredServer> foundByKey = new LinkedHashMap<>();

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            byte[] probeData = DISCOVER_MAGIC.getBytes(StandardCharsets.UTF_8);

            for (InetAddress target : NetworkAddressUtil.listBroadcastAddresses()) {
                try {
                    DatagramPacket packet = new DatagramPacket(probeData, probeData.length, target, discoveryPort);
                    socket.send(packet);
                } catch (IOException ignored) {
                    // Một số card mạng ảo không cho phép broadcast -> bỏ qua và tiếp tục card khác
                }
            }

            long deadlineNanos = System.nanoTime() + (safeTimeout * 1_000_000L);
            byte[] buffer = new byte[512];

            while (true) {
                long remainingMillis = (deadlineNanos - System.nanoTime()) / 1_000_000L;
                if (remainingMillis <= 0) {
                    break;
                }
                socket.setSoTimeout((int) Math.min(remainingMillis, safeTimeout));
                DatagramPacket responsePacket = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(responsePacket);
                } catch (SocketTimeoutException timeout) {
                    break;
                }

                String message = new String(
                        responsePacket.getData(),
                        responsePacket.getOffset(),
                        responsePacket.getLength(),
                        StandardCharsets.UTF_8).trim();
                String senderIp = responsePacket.getAddress().getHostAddress();
                parseOffer(senderIp, message).ifPresent(server -> {
                    String key = server.host() + ":" + server.port();
                    foundByKey.putIfAbsent(key, server);
                });
            }
        } catch (IOException ignored) {
            return List.of();
        }

        // Nếu cùng một máy chủ vừa phản hồi qua IP LAN vừa qua 127.0.0.1 cùng tên & cùng cổng,
        // ưu tiên giữ địa chỉ LAN để người dùng dễ thấy và chia sẻ cho máy khác.
        List<DiscoveredServer> servers = new ArrayList<>(foundByKey.values());
        List<DiscoveredServer> deduplicated = new ArrayList<>();
        for (DiscoveredServer candidate : servers) {
            if ("127.0.0.1".equals(candidate.host())) {
                boolean hasLanDuplicate = servers.stream().anyMatch(other ->
                        !"127.0.0.1".equals(other.host())
                                && other.port() == candidate.port()
                                && other.serverName().equals(candidate.serverName()));
                if (hasLanDuplicate) {
                    continue;
                }
            }
            deduplicated.add(candidate);
        }
        return deduplicated;
    }

    static String encodeOffer(int tcpPort, int auctionCount, int connectedClients, String serverName) {
        String safeName = (serverName == null || serverName.trim().isEmpty())
                ? "Auction-Server"
                : serverName.trim().replace("|", "-").replace("\n", " ").replace("\r", " ");
        return OFFER_PREFIX + tcpPort + "|" + Math.max(0, auctionCount) + "|"
                + Math.max(0, connectedClients) + "|" + safeName;
    }

    static Optional<DiscoveredServer> parseOffer(String host, String payload) {
        if (host == null || host.isBlank() || payload == null || !payload.startsWith(OFFER_PREFIX)) {
            return Optional.empty();
        }
        String body = payload.substring(OFFER_PREFIX.length());
        String[] parts = body.split("\\|", 4);
        if (parts.length < 4) {
            return Optional.empty();
        }
        try {
            int tcpPort = Integer.parseInt(parts[0].trim());
            int auctionCount = Integer.parseInt(parts[1].trim());
            int connectedClients = Integer.parseInt(parts[2].trim());
            String name = parts[3].trim();
            if (tcpPort < 1 || tcpPort > 65535) {
                return Optional.empty();
            }
            return Optional.of(new DiscoveredServer(
                    host.trim(),
                    tcpPort,
                    name.isEmpty() ? "Auction-Server" : name,
                    Math.max(0, auctionCount),
                    Math.max(0, connectedClients)
            ));
        } catch (NumberFormatException error) {
            return Optional.empty();
        }
    }

    /**
     * Bộ lắng nghe UDP chạy nền cùng AuctionServer.
     */
    public static final class Responder implements Closeable {
        private final DatagramSocket socket;
        private final IntSupplier tcpPortSupplier;
        private final IntSupplier auctionCountSupplier;
        private final IntSupplier clientCountSupplier;
        private final String serverName;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final Thread thread;

        Responder(int discoveryPort,
                  IntSupplier tcpPortSupplier,
                  IntSupplier auctionCountSupplier,
                  IntSupplier clientCountSupplier,
                  String serverName) throws IOException {
            this.socket = new DatagramSocket(null);
            this.socket.setReuseAddress(true);
            this.socket.bind(new InetSocketAddress(discoveryPort));
            this.tcpPortSupplier = tcpPortSupplier;
            this.auctionCountSupplier = auctionCountSupplier;
            this.clientCountSupplier = clientCountSupplier;
            this.serverName = serverName;

            this.thread = new Thread(this::listenLoop, "Auction-LAN-Discovery");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        private void listenLoop() {
            byte[] buffer = new byte[256];
            while (running.get() && !socket.isClosed()) {
                try {
                    DatagramPacket request = new DatagramPacket(buffer, buffer.length);
                    socket.receive(request);
                    String message = new String(
                            request.getData(),
                            request.getOffset(),
                            request.getLength(),
                            StandardCharsets.UTF_8).trim();
                    if (!DISCOVER_MAGIC.equals(message)) {
                        continue;
                    }
                    int tcpPort = tcpPortSupplier.getAsInt();
                    if (tcpPort <= 0) {
                        continue;
                    }
                    String offer = encodeOffer(
                            tcpPort,
                            auctionCountSupplier.getAsInt(),
                            clientCountSupplier.getAsInt(),
                            serverName);
                    byte[] replyBytes = offer.getBytes(StandardCharsets.UTF_8);
                    DatagramPacket reply = new DatagramPacket(
                            replyBytes,
                            replyBytes.length,
                            request.getAddress(),
                            request.getPort());
                    socket.send(reply);
                } catch (SocketException closed) {
                    break;
                } catch (IOException ignored) {
                    if (!running.get()) {
                        break;
                    }
                }
            }
        }

        public int getLocalPort() {
            return socket.getLocalPort();
        }

        public boolean isRunning() {
            return running.get() && !socket.isClosed();
        }

        @Override
        public void close() {
            if (running.compareAndSet(true, false)) {
                socket.close();
            }
        }
    }
}
