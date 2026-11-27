package com.auction.client.service;

import com.auction.server.network.AuctionServer;
import com.auction.server.network.ServerEndpoint;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.prefs.Preferences;

/**
 * ConnectionPreferences: Nạp địa chỉ máy chủ dùng chung cho toàn bộ Client.
 *
 * Thứ tự ưu tiên khi khởi động:
 * 1. Tham số dòng lệnh --server=host:port (vd: .\run.cmd Client -Server host:port)
 * 2. Biến hệ thống -Dauction.server=host:port hoặc biến môi trường AUCTION_SERVER
 * 3. Tệp cấu hình chung auction-server.properties (nếu cấu hình địa chỉ Cloud dùng chung)
 * 4. Địa chỉ máy chủ đã kết nối thành công gần nhất (lưu trong Preferences)
 * 5. Mặc định 127.0.0.1:8888
 */
public final class ConnectionPreferences {

    public static final ServerEndpoint DEFAULT_ENDPOINT = new ServerEndpoint("127.0.0.1", AuctionServer.DEFAULT_PORT);
    private static final String KEY_HOST = "lastServerHost";
    private static final String KEY_PORT = "lastServerPort";
    private static final String KEY_RECENT = "recentServers";
    private static final int MAX_RECENT = 5;

    private static final Preferences SETTINGS = preferences();

    private ConnectionPreferences() {
    }

    private static Preferences preferences() {
        try {
            return Preferences.userNodeForPackage(ConnectionPreferences.class);
        } catch (SecurityException ignored) {
            return null;
        }
    }

    public static ServerEndpoint resolveInitialEndpoint(List<String> rawArgs) {
        ServerEndpoint fromArgs = parseFromArgs(rawArgs);
        if (fromArgs != null) {
            return fromArgs;
        }
        ServerEndpoint fromEnv = parseOptional(System.getProperty("auction.server"));
        if (fromEnv != null) {
            return fromEnv;
        }
        fromEnv = parseOptional(System.getenv("AUCTION_SERVER"));
        if (fromEnv != null) {
            return fromEnv;
        }
        ServerEndpoint fromFile = readSharedServerConfig();
        if (fromFile != null && !fromFile.isLoopback()) {
            // Khi nhóm đã cấu hình địa chỉ Cloud Server cố định trong auction-server.properties,
            // luôn ưu tiên vào thẳng đúng 1 Server dùng chung duy nhất này.
            return fromFile;
        }
        ServerEndpoint lastUsed = loadLastEndpoint();
        if (lastUsed != null && !lastUsed.isLoopback()) {
            return lastUsed;
        }
        return fromFile != null ? fromFile : DEFAULT_ENDPOINT;
    }

    private static ServerEndpoint readSharedServerConfig() {
        for (Path candidate : List.of(
                Path.of("auction-server.properties"),
                Path.of("ltnc-btl", "auction-server.properties"))) {
            ServerEndpoint endpoint = readFromPropertiesFile(candidate);
            if (endpoint != null) {
                return endpoint;
            }
        }
        try (InputStream in = ConnectionPreferences.class.getResourceAsStream("/com/auction/client/auction-server.properties")) {
            if (in != null) {
                return readFromStream(in);
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    static ServerEndpoint parseFromArgs(List<String> rawArgs) {
        if (rawArgs == null || rawArgs.isEmpty()) {
            return null;
        }
        for (int i = 0; i < rawArgs.size(); i++) {
            String arg = rawArgs.get(i);
            if (arg == null) {
                continue;
            }
            if (arg.startsWith("--server=")) {
                ServerEndpoint parsed = parseOptional(arg.substring("--server=".length()));
                if (parsed != null) {
                    return parsed;
                }
            } else if ("--server".equalsIgnoreCase(arg) && i + 1 < rawArgs.size()) {
                ServerEndpoint parsed = parseOptional(rawArgs.get(i + 1));
                if (parsed != null) {
                    return parsed;
                }
            }
        }
        return null;
    }

    static ServerEndpoint readFromPropertiesFile(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(file)) {
            return readFromStream(in);
        } catch (IOException ignored) {
            return null;
        }
    }

    private static ServerEndpoint readFromStream(InputStream in) throws IOException {
        Properties props = new Properties();
        props.load(in);
        String serverValue = props.getProperty("server");
        if (serverValue != null && !serverValue.trim().isEmpty()) {
            return parseOptional(serverValue);
        }
        String host = props.getProperty("host");
        String port = props.getProperty("port");
        if (host != null && !host.trim().isEmpty()) {
            try {
                return ServerEndpoint.fromInputs(host, port != null ? port : String.valueOf(AuctionServer.DEFAULT_PORT));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    public static ServerEndpoint loadLastEndpoint() {
        if (SETTINGS == null) {
            return DEFAULT_ENDPOINT;
        }
        try {
            String host = SETTINGS.get(KEY_HOST, DEFAULT_ENDPOINT.host());
            int port = SETTINGS.getInt(KEY_PORT, DEFAULT_ENDPOINT.port());
            return new ServerEndpoint(host, port);
        } catch (IllegalArgumentException | SecurityException ignored) {
            return DEFAULT_ENDPOINT;
        }
    }

    public static void save(ServerEndpoint endpoint) {
        if (endpoint == null || SETTINGS == null) {
            return;
        }
        try {
            SETTINGS.put(KEY_HOST, endpoint.host());
            SETTINGS.putInt(KEY_PORT, endpoint.port());

            List<String> updated = new ArrayList<>();
            updated.add(endpoint.toAddress());
            for (ServerEndpoint existing : recentEndpoints()) {
                String addr = existing.toAddress();
                if (!updated.contains(addr) && updated.size() < MAX_RECENT) {
                    updated.add(addr);
                }
            }
            SETTINGS.put(KEY_RECENT, String.join(",", updated));
        } catch (SecurityException ignored) {
        }
    }

    public static List<ServerEndpoint> recentEndpoints() {
        if (SETTINGS == null) {
            return List.of();
        }
        try {
            String raw = SETTINGS.get(KEY_RECENT, "");
            if (raw == null || raw.trim().isEmpty()) {
                return List.of();
            }
            List<ServerEndpoint> result = new ArrayList<>();
            for (String token : raw.split(",")) {
                ServerEndpoint parsed = parseOptional(token);
                if (parsed != null) {
                    result.add(parsed);
                }
            }
            return result;
        } catch (SecurityException ignored) {
            return List.of();
        }
    }

    private static ServerEndpoint parseOptional(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return ServerEndpoint.parse(raw.trim(), AuctionServer.DEFAULT_PORT);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
