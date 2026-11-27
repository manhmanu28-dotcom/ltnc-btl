package com.auction.server.network;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * ServerDirectoryEntry: Thông tin một máy chủ đấu giá đang hoạt động hiển thị trong danh sách "Tham gia Server".
 *
 * Một máy chủ có thể đồng thời có:
 * - Địa chỉ nội bộ cùng Wi-Fi (lanHost:lanPort)
 * - Địa chỉ Internet công khai khác Wi-Fi (publicHost:publicPort)
 */
public record ServerDirectoryEntry(
        String serverId,
        String serverName,
        String lanHost,
        int lanPort,
        String publicHost,
        int publicPort,
        int auctionCount,
        int connectedClients,
        long updatedAtMillis,
        Source source) {

    public enum Source {
        LOCAL_HOSTED,
        LAN,
        INTERNET,
        LAN_AND_INTERNET
    }

    public ServerDirectoryEntry {
        serverId = (serverId == null || serverId.isBlank()) ? "SRV-UNKNOWN" : serverId.trim();
        serverName = (serverName == null || serverName.isBlank()) ? "Auction-Server" : serverName.trim();
        lanHost = (lanHost == null || lanHost.isBlank()) ? "" : lanHost.trim();
        publicHost = (publicHost == null || publicHost.isBlank()) ? "" : publicHost.trim();
        lanPort = (lanPort >= 1 && lanPort <= 65535) ? lanPort : 0;
        publicPort = (publicPort >= 1 && publicPort <= 65535) ? publicPort : 0;
        auctionCount = Math.max(0, auctionCount);
        connectedClients = Math.max(0, connectedClients);
        source = (source != null) ? source : Source.LAN;
    }

    public boolean hasLanEndpoint() {
        return !lanHost.isEmpty() && lanPort > 0;
    }

    public boolean hasPublicEndpoint() {
        return !publicHost.isEmpty() && publicPort > 0;
    }

    public ServerEndpoint lanEndpoint() {
        return hasLanEndpoint() ? new ServerEndpoint(lanHost, lanPort) : null;
    }

    public ServerEndpoint publicEndpoint() {
        return hasPublicEndpoint() ? new ServerEndpoint(publicHost, publicPort) : null;
    }

    /**
     * Địa chỉ kết nối ưu tiên nhất cho máy khách hiện tại.
     */
    public ServerEndpoint preferredEndpoint() {
        List<ServerEndpoint> candidates = candidateEndpoints();
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    /**
     * Danh sách địa chỉ thử kết nối theo thứ tự ưu tiên (có cơ chế dự phòng giữa LAN và Internet).
     */
    public List<ServerEndpoint> candidateEndpoints() {
        Set<ServerEndpoint> ordered = new LinkedHashSet<>();
        switch (source) {
            case LOCAL_HOSTED -> {
                if (lanPort > 0) {
                    ordered.add(new ServerEndpoint("127.0.0.1", lanPort));
                }
                if (hasLanEndpoint()) {
                    ordered.add(new ServerEndpoint(lanHost, lanPort));
                }
                if (hasPublicEndpoint()) {
                    ordered.add(new ServerEndpoint(publicHost, publicPort));
                }
            }
            case LAN, LAN_AND_INTERNET -> {
                if (hasLanEndpoint()) {
                    ordered.add(new ServerEndpoint(lanHost, lanPort));
                }
                if (hasPublicEndpoint()) {
                    ordered.add(new ServerEndpoint(publicHost, publicPort));
                }
            }
            case INTERNET -> {
                if (hasPublicEndpoint()) {
                    ordered.add(new ServerEndpoint(publicHost, publicPort));
                }
                if (hasLanEndpoint()) {
                    ordered.add(new ServerEndpoint(lanHost, lanPort));
                }
            }
        }
        return new ArrayList<>(ordered);
    }

    public String sourceBadge() {
        return switch (source) {
            case LOCAL_HOSTED -> hasPublicEndpoint() ? "Máy bạn tạo · Wi-Fi + Internet" : "Máy bạn tạo · Cùng Wi-Fi";
            case LAN_AND_INTERNET -> "Cùng Wi-Fi + Internet";
            case LAN -> "Cùng Wi-Fi (LAN)";
            case INTERNET -> "Khác Wi-Fi (Internet)";
        };
    }

    public String displayAddress() {
        ServerEndpoint preferred = preferredEndpoint();
        if (source == Source.LOCAL_HOSTED && hasPublicEndpoint()) {
            return publicHost + ":" + publicPort;
        }
        return preferred != null ? preferred.toAddress() : "Chưa có địa chỉ";
    }

    public String statsSummary() {
        return auctionCount + " phiên · " + connectedClients + " online";
    }
}
