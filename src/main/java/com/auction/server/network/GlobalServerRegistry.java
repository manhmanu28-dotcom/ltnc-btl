package com.auction.server.network;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * GlobalServerRegistry: Danh bạ máy chủ trực tuyến giúp mọi người ở KHÁC MẠNG Wi-Fi / 4G
 * tự động nhìn thấy tất cả các Server đã được tạo khi bấm "Tham gia Server".
 *
 * Cơ chế hoạt động:
 * - Mặc định dùng kênh HTTP công khai không cần API key trên ntfy.sh (có thể đổi bằng -Dauction.registry.url=...).
 * - Khi máy chủ bật, gửi thông báo ONLINE kèm heartbeat định kỳ mỗi 60 giây.
 * - Khi máy chủ tắt, gửi thông báo OFFLINE để gỡ khỏi danh sách ngay lập tức.
 * - Khi người chơi bấm "Tham gia Server", Client tải danh sách từ đây kết hợp với UDP LAN Discovery
 *   và máy chủ cục bộ để hiển thị đầy đủ tất cả Server (cả cùng Wi-Fi lẫn khác Wi-Fi).
 */
public class GlobalServerRegistry {

    public static final String DEFAULT_REGISTRY_URL = "https://ntfy.sh/bidion-ltnc-btl-servers-v1";
    public static final String RECORD_PREFIX = "BIDION_SRV_V1|";
    public static final long DEFAULT_HEARTBEAT_INTERVAL_MS = 60_000L;
    public static final long DEFAULT_ENTRY_TTL_MS = 180_000L;
    private static final int HTTP_TIMEOUT_SECONDS = 6;

    private final String registryBaseUrl;
    private final HttpClient httpClient;

    public GlobalServerRegistry() {
        this(resolveConfiguredUrl());
    }

    public GlobalServerRegistry(String registryBaseUrl) {
        String cleaned = (registryBaseUrl == null || registryBaseUrl.isBlank())
                ? DEFAULT_REGISTRY_URL
                : registryBaseUrl.trim();
        while (cleaned.endsWith("/")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        this.registryBaseUrl = cleaned;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public static String resolveConfiguredUrl() {
        String prop = System.getProperty("auction.registry.url");
        if (prop != null && !prop.isBlank()) {
            return prop.trim();
        }
        String env = System.getenv("AUCTION_REGISTRY_URL");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        return DEFAULT_REGISTRY_URL;
    }

    public String getRegistryBaseUrl() {
        return registryBaseUrl;
    }

    /**
     * Đăng hoặc cập nhật trạng thái ONLINE của máy chủ lên danh bạ toàn cầu.
     */
    public boolean announceOnline(ServerDirectoryEntry entry) {
        if (entry == null) {
            return false;
        }
        String payload = encodeRecord(entry, "ONLINE", System.currentTimeMillis());
        return postRecord(payload);
    }

    /**
     * Thông báo máy chủ đã dừng (OFFLINE) để gỡ khỏi danh sách của mọi người ngay lập tức.
     */
    public boolean announceOffline(String serverId, String serverName) {
        if (serverId == null || serverId.isBlank()) {
            return false;
        }
        ServerDirectoryEntry placeholder = new ServerDirectoryEntry(
                serverId,
                serverName,
                "",
                0,
                "",
                0,
                0,
                0,
                System.currentTimeMillis(),
                ServerDirectoryEntry.Source.INTERNET);
        return postRecord(encodeRecord(placeholder, "OFFLINE", System.currentTimeMillis()));
    }

    private boolean postRecord(String payload) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(registryBaseUrl))
                    .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                    .header("Content-Type", "text/plain; charset=utf-8")
                    .header("Title", "BIDION Server Directory")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (IOException | IllegalArgumentException ignored) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Lấy danh sách tất cả các máy chủ đang ONLINE trên danh bạ toàn cầu.
     */
    public List<ServerDirectoryEntry> fetchOnlineServers() {
        return fetchOnlineServers(DEFAULT_ENTRY_TTL_MS);
    }

    public List<ServerDirectoryEntry> fetchOnlineServers(long maxAgeMillis) {
        long safeTtl = Math.max(5_000L, maxAgeMillis);
        try {
            URI uri = URI.create(registryBaseUrl + "/json?poll=1&since=10m");
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return List.of();
            }
            return parseDirectoryResponse(response.body(), System.currentTimeMillis(), safeTtl);
        } catch (IOException | IllegalArgumentException ignored) {
            return List.of();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    /**
     * Phân tích nội dung NDJSON trả về từ ntfy.sh (hoặc danh sách dòng bản ghi thô)
     * và lọc ra các máy chủ đang ONLINE còn hạn heartbeat.
     */
    static List<ServerDirectoryEntry> parseDirectoryResponse(String responseBody, long nowMillis, long maxAgeMillis) {
        if (responseBody == null || responseBody.isBlank()) {
            return List.of();
        }
        Map<String, ParsedAnnouncement> latestById = new LinkedHashMap<>();

        String[] lines = responseBody.split("\\R");
        for (String rawLine : lines) {
            String trimmed = rawLine.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String messageText = extractMessagePayload(trimmed);
            if (messageText == null) {
                continue;
            }
            Optional<ParsedAnnouncement> parsed = decodeRecord(messageText);
            if (parsed.isEmpty()) {
                continue;
            }
            ParsedAnnouncement item = parsed.get();
            ParsedAnnouncement existing = latestById.get(item.entry().serverId());
            if (existing == null || item.timestampMillis() >= existing.timestampMillis()) {
                latestById.put(item.entry().serverId(), item);
            }
        }

        List<ServerDirectoryEntry> active = new ArrayList<>();
        for (ParsedAnnouncement announcement : latestById.values()) {
            if (!"ONLINE".equalsIgnoreCase(announcement.status())) {
                continue;
            }
            long age = nowMillis - announcement.timestampMillis();
            if (age > maxAgeMillis) {
                continue;
            }
            ServerDirectoryEntry entry = announcement.entry();
            if (entry.hasPublicEndpoint() || entry.hasLanEndpoint()) {
                active.add(entry);
            }
        }
        return active;
    }

    private static String extractMessagePayload(String line) {
        if (line.startsWith(RECORD_PREFIX)) {
            return line;
        }
        if (!line.startsWith("{")) {
            return null;
        }
        try {
            JsonElement element = JsonParser.parseString(line);
            if (!element.isJsonObject()) {
                return null;
            }
            JsonObject obj = element.getAsJsonObject();
            JsonElement event = obj.get("event");
            if (event != null && !"message".equalsIgnoreCase(event.getAsString())) {
                return null;
            }
            JsonElement message = obj.get("message");
            return (message != null && !message.isJsonNull()) ? message.getAsString() : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    static String encodeRecord(ServerDirectoryEntry entry, String status, long timestampMillis) {
        String safeId = sanitizeField(entry.serverId());
        String safeStatus = sanitizeField(status);
        String safePublicHost = sanitizeField(entry.publicHost());
        String safeLanHost = sanitizeField(entry.lanHost());
        String safeName = sanitizeField(entry.serverName());
        return RECORD_PREFIX
                + safeId + "|"
                + safeStatus + "|"
                + timestampMillis + "|"
                + safePublicHost + "|"
                + entry.publicPort() + "|"
                + safeLanHost + "|"
                + entry.lanPort() + "|"
                + entry.auctionCount() + "|"
                + entry.connectedClients() + "|"
                + safeName;
    }

    static Optional<ParsedAnnouncement> decodeRecord(String raw) {
        if (raw == null || !raw.startsWith(RECORD_PREFIX)) {
            return Optional.empty();
        }
        String body = raw.substring(RECORD_PREFIX.length());
        String[] parts = body.split("\\|", 10);
        if (parts.length < 10) {
            return Optional.empty();
        }
        try {
            String serverId = parts[0].trim();
            String status = parts[1].trim();
            long ts = Long.parseLong(parts[2].trim());
            String publicHost = parts[3].trim();
            int publicPort = Integer.parseInt(parts[4].trim());
            String lanHost = parts[5].trim();
            int lanPort = Integer.parseInt(parts[6].trim());
            int auctionCount = Integer.parseInt(parts[7].trim());
            int clientCount = Integer.parseInt(parts[8].trim());
            String serverName = parts[9].trim();

            if (serverId.isEmpty()) {
                return Optional.empty();
            }
            ServerDirectoryEntry.Source source = (!publicHost.isEmpty() && publicPort > 0)
                    ? ServerDirectoryEntry.Source.INTERNET
                    : ServerDirectoryEntry.Source.LAN;
            ServerDirectoryEntry entry = new ServerDirectoryEntry(
                    serverId,
                    serverName,
                    lanHost,
                    lanPort,
                    publicHost,
                    publicPort,
                    auctionCount,
                    clientCount,
                    ts,
                    source);
            return Optional.of(new ParsedAnnouncement(status, ts, entry));
        } catch (NumberFormatException error) {
            return Optional.empty();
        }
    }

    private static String sanitizeField(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replace("|", "-").replace("\n", " ").replace("\r", " ");
    }

    /**
     * Gộp toàn bộ các máy chủ từ 3 nguồn:
     * 1. Máy chủ do chính máy này đang tạo (localHosted)
     * 2. Các máy chủ phát hiện qua cùng Wi-Fi (lanServers)
     * 3. Các máy chủ đăng ký qua Internet/khác Wi-Fi (globalServers)
     *
     * Gộp trùng theo serverId (hoặc tên + cổng LAN), ưu tiên kết nối trực tiếp khi cùng Wi-Fi
     * và dùng đường hầm công khai khi khác Wi-Fi.
     */
    public static List<ServerDirectoryEntry> mergeServers(
            ServerDirectoryEntry localHosted,
            List<LanDiscoveryService.DiscoveredServer> lanServers,
            List<ServerDirectoryEntry> globalServers) {
        Map<String, ServerDirectoryEntry> mergedByKey = new LinkedHashMap<>();

        if (localHosted != null) {
            mergedByKey.put(identityKey(localHosted.serverId(), localHosted.serverName(), localHosted.lanPort()), localHosted);
        }

        if (lanServers != null) {
            long now = System.currentTimeMillis();
            for (LanDiscoveryService.DiscoveredServer lan : lanServers) {
                String key = findExistingKey(mergedByKey, lan.serverId(), lan.serverName(), lan.port());
                ServerDirectoryEntry existing = key != null ? mergedByKey.get(key) : null;
                if (existing != null) {
                    if (existing.source() == ServerDirectoryEntry.Source.LOCAL_HOSTED) {
                        // Giữ nguyên đánh dấu máy chủ của chính mình, chỉ bổ sung IP LAN nếu chưa có
                        String bestLanHost = existing.hasLanEndpoint() ? existing.lanHost() : lan.host();
                        mergedByKey.put(key, new ServerDirectoryEntry(
                                existing.serverId(),
                                existing.serverName(),
                                bestLanHost,
                                existing.lanPort() > 0 ? existing.lanPort() : lan.port(),
                                existing.publicHost(),
                                existing.publicPort(),
                                Math.max(existing.auctionCount(), lan.auctionCount()),
                                Math.max(existing.connectedClients(), lan.connectedClients()),
                                now,
                                ServerDirectoryEntry.Source.LOCAL_HOSTED));
                    }
                    continue;
                }
                String newKey = identityKey(lan.serverId(), lan.serverName(), lan.port());
                mergedByKey.put(newKey, new ServerDirectoryEntry(
                        lan.serverId(),
                        lan.serverName(),
                        lan.host(),
                        lan.port(),
                        "",
                        0,
                        lan.auctionCount(),
                        lan.connectedClients(),
                        now,
                        ServerDirectoryEntry.Source.LAN));
            }
        }

        if (globalServers != null) {
            for (ServerDirectoryEntry global : globalServers) {
                String key = findExistingKey(mergedByKey, global.serverId(), global.serverName(), global.lanPort());
                ServerDirectoryEntry existing = key != null ? mergedByKey.get(key) : null;
                if (existing != null) {
                    String publicHost = existing.hasPublicEndpoint() ? existing.publicHost() : global.publicHost();
                    int publicPort = existing.hasPublicEndpoint() ? existing.publicPort() : global.publicPort();
                    ServerDirectoryEntry.Source combinedSource = existing.source() == ServerDirectoryEntry.Source.LOCAL_HOSTED
                            ? ServerDirectoryEntry.Source.LOCAL_HOSTED
                            : ((!publicHost.isEmpty() && publicPort > 0)
                                ? ServerDirectoryEntry.Source.LAN_AND_INTERNET
                                : existing.source());
                    mergedByKey.put(key, new ServerDirectoryEntry(
                            existing.serverId(),
                            existing.serverName(),
                            existing.hasLanEndpoint() ? existing.lanHost() : global.lanHost(),
                            existing.lanPort() > 0 ? existing.lanPort() : global.lanPort(),
                            publicHost,
                            publicPort,
                            Math.max(existing.auctionCount(), global.auctionCount()),
                            Math.max(existing.connectedClients(), global.connectedClients()),
                            Math.max(existing.updatedAtMillis(), global.updatedAtMillis()),
                            combinedSource));
                } else {
                    String newKey = identityKey(global.serverId(), global.serverName(), global.lanPort());
                    mergedByKey.put(newKey, global);
                }
            }
        }

        List<ServerDirectoryEntry> result = new ArrayList<>(mergedByKey.values());
        result.sort(Comparator.comparingInt(GlobalServerRegistry::sourceSortOrder)
                .thenComparing(ServerDirectoryEntry::serverName, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    private static String identityKey(String serverId, String serverName, int lanPort) {
        if (serverId != null && !serverId.isBlank() && !serverId.startsWith("LAN-")) {
            return "ID:" + serverId.trim().toUpperCase(Locale.ROOT);
        }
        return "NAME:" + (serverName == null ? "" : serverName.trim().toLowerCase(Locale.ROOT)) + ":" + lanPort;
    }

    private static String findExistingKey(Map<String, ServerDirectoryEntry> map,
                                          String serverId,
                                          String serverName,
                                          int lanPort) {
        for (Map.Entry<String, ServerDirectoryEntry> item : map.entrySet()) {
            ServerDirectoryEntry e = item.getValue();
            if (serverId != null && !serverId.isBlank()
                    && !serverId.startsWith("LAN-")
                    && serverId.equalsIgnoreCase(e.serverId())) {
                return item.getKey();
            }
            if (lanPort > 0 && e.lanPort() == lanPort
                    && serverName != null && serverName.equalsIgnoreCase(e.serverName())) {
                return item.getKey();
            }
        }
        return null;
    }

    private static int sourceSortOrder(ServerDirectoryEntry entry) {
        return switch (entry.source()) {
            case LOCAL_HOSTED -> 0;
            case LAN_AND_INTERNET -> 1;
            case LAN -> 2;
            case INTERNET -> 3;
        };
    }

    record ParsedAnnouncement(String status, long timestampMillis, ServerDirectoryEntry entry) {
    }
}
