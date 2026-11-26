package com.auction.server.network;

import java.util.Locale;

/**
 * ServerEndpoint: Biểu diễn địa chỉ kết nối TCP (host + port) dùng chung cho Client và Server.
 *
 * Hỗ trợ phân tích linh hoạt nhiều định dạng người dùng hay dán vào giao diện:
 * - "192.168.1.15:8888" (Cùng Wi-Fi / LAN)
 * - "tcp://rnxxx-xxx.a.free.pinggy.link:43210" (Khác Wi-Fi qua Tunnel công khai)
 * - "0.tcp.ap.ngrok.io:14567" (Khác Wi-Fi qua ngrok)
 * - "127.0.0.1" kèm cổng mặc định
 */
public record ServerEndpoint(String host, int port) {

    public ServerEndpoint {
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException("Địa chỉ server (host) không được để trống!");
        }
        host = host.trim();
        if (host.contains(" ") || host.contains("\t") || host.contains("\n") || host.contains("\r")) {
            throw new IllegalArgumentException("Địa chỉ server không được chứa khoảng trắng: " + host);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Cổng kết nối (port) phải nằm trong khoảng 1–65535!");
        }
    }

    /**
     * Phân tích chuỗi dạng "host:port" hoặc "tcp://host:port" (bắt buộc có cổng).
     */
    public static ServerEndpoint parse(String raw) {
        return parse(raw, -1);
    }

    /**
     * Phân tích chuỗi địa chỉ máy chủ; nếu chuỗi không kèm ":port" sẽ dùng {@code defaultPort}.
     */
    public static ServerEndpoint parse(String raw, int defaultPort) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException("Địa chỉ server không được để trống!");
        }
        String cleaned = stripSchemeAndPath(raw.trim());
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("Địa chỉ server không hợp lệ!");
        }

        // Hỗ trợ IPv6 dạng [::1]:8888
        if (cleaned.startsWith("[")) {
            int closeBracket = cleaned.indexOf(']');
            if (closeBracket < 0) {
                throw new IllegalArgumentException("Địa chỉ IPv6 không hợp lệ: " + raw);
            }
            String ipv6Host = cleaned.substring(1, closeBracket).trim();
            String remainder = cleaned.substring(closeBracket + 1).trim();
            if (remainder.isEmpty()) {
                return new ServerEndpoint(ipv6Host, requireValidPort(defaultPort));
            }
            if (!remainder.startsWith(":")) {
                throw new IllegalArgumentException("Định dạng cổng không hợp lệ sau địa chỉ IPv6: " + raw);
            }
            return new ServerEndpoint(ipv6Host, parsePort(remainder.substring(1)));
        }

        int firstColon = cleaned.indexOf(':');
        int lastColon = cleaned.lastIndexOf(':');
        if (firstColon > 0 && firstColon == lastColon) {
            String hostPart = cleaned.substring(0, firstColon).trim();
            String portPart = cleaned.substring(firstColon + 1).trim();
            return new ServerEndpoint(hostPart, parsePort(portPart));
        }

        return new ServerEndpoint(cleaned, requireValidPort(defaultPort));
    }

    /**
     * Phân tích từ 2 ô nhập liệu (host và port) trên giao diện.
     * Nếu người dùng dán nguyên chuỗi "tcp://host:port" hoặc "host:port" vào ô host,
     * phương thức sẽ tự động tách đúng host và port mới.
     */
    public static ServerEndpoint fromInputs(String rawHost, String rawPort) {
        if (rawHost == null || rawHost.trim().isEmpty()) {
            throw new IllegalArgumentException("Vui lòng nhập địa chỉ server.");
        }
        String hostInput = rawHost.trim();
        String stripped = stripSchemeAndPath(hostInput);
        boolean hostContainsPort = hasExplicitPort(stripped);
        if (hostContainsPort) {
            return parse(hostInput);
        }
        int parsedPort = parsePort(rawPort);
        return new ServerEndpoint(stripped, parsedPort);
    }

    /**
     * Kiểm tra chuỗi nhập vào có chứa cả host và port (vd "host:port" hoặc "tcp://host:port") hay không.
     */
    public static boolean containsHostAndPort(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return false;
        }
        return hasExplicitPort(stripSchemeAndPath(raw.trim()));
    }

    private static boolean hasExplicitPort(String stripped) {
        if (stripped.startsWith("[")) {
            int closeBracket = stripped.indexOf(']');
            return closeBracket > 0 && closeBracket + 1 < stripped.length()
                    && stripped.charAt(closeBracket + 1) == ':';
        }
        int firstColon = stripped.indexOf(':');
        int lastColon = stripped.lastIndexOf(':');
        return firstColon > 0 && firstColon == lastColon;
    }

    private static String stripSchemeAndPath(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        String withoutScheme = value;
        for (String prefix : new String[]{"tcp://", "http://", "https://", "auction://"}) {
            if (lower.startsWith(prefix)) {
                withoutScheme = value.substring(prefix.length());
                break;
            }
        }
        int slashIndex = withoutScheme.indexOf('/');
        if (slashIndex >= 0) {
            withoutScheme = withoutScheme.substring(0, slashIndex);
        }
        return withoutScheme.trim();
    }

    private static int parsePort(String rawPort) {
        if (rawPort == null || rawPort.trim().isEmpty()) {
            throw new IllegalArgumentException("Vui lòng nhập cổng kết nối (1–65535).");
        }
        try {
            return requireValidPort(Integer.parseInt(rawPort.trim()));
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Cổng kết nối phải là số nguyên từ 1 đến 65535.");
        }
    }

    private static int requireValidPort(int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Cổng kết nối phải nằm trong khoảng 1–65535.");
        }
        return port;
    }

    public boolean isLoopback() {
        return "127.0.0.1".equals(host)
                || "localhost".equalsIgnoreCase(host)
                || "::1".equals(host);
    }

    public String toAddress() {
        if (host.contains(":") && !host.startsWith("[")) {
            return "[" + host + "]:" + port;
        }
        return host + ":" + port;
    }

    @Override
    public String toString() {
        return toAddress();
    }
}
