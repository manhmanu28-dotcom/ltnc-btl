package com.auction.server.network;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PublicTunnelService: Tự động mở đường hầm TCP công khai (Public TCP Tunnel)
 * để người dùng ở KHÁC MẠNG Wi-Fi / 4G có thể kết nối trực tiếp vào AuctionServer
 * mà không cần cấu hình mở cổng router (Port Forwarding) hay nhập thẻ thanh toán.
 *
 * Mặc định sử dụng OpenSSH tích hợp sẵn trên Windows 10/11, macOS, Linux kết nối tới Pinggy TCP.
 */
public class PublicTunnelService implements Closeable {

    private static final Pattern ANSI_ESCAPE_PATTERN = Pattern.compile("\\u001B\\[[;?\\d]*[ -/]*[@-~]");
    private static final Pattern TCP_ENDPOINT_PATTERN = Pattern.compile(
            "tcp://([a-zA-Z0-9.-]+):(\\d{1,5})", Pattern.CASE_INSENSITIVE);

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Process tunnelProcess;
    private Thread outputReaderThread;
    private volatile ServerEndpoint publicEndpoint;

    /**
     * Khởi chạy đường hầm công khai trỏ về cổng TCP nội bộ {@code localPort}.
     *
     * @param localPort     Cổng TCP mà AuctionServer đang lắng nghe (vd: 8888)
     * @param timeoutMillis Thời gian chờ tối đa để nhận địa chỉ công khai từ dịch vụ tunnel
     * @return Địa chỉ công khai {@link ServerEndpoint} (host + port) để gửi cho các Client khác mạng
     */
    public synchronized ServerEndpoint startPinggyTunnel(int localPort, long timeoutMillis) throws IOException {
        if (localPort < 1 || localPort > 65535) {
            throw new IllegalArgumentException("Cổng localPort không hợp lệ: " + localPort);
        }
        return startWithCommand(buildPinggyCommand(localPort), timeoutMillis);
    }

    /**
     * Khởi chạy đường hầm với lệnh tùy chỉnh (hỗ trợ kiểm thử và mở rộng nhà cung cấp khác).
     */
    public synchronized ServerEndpoint startWithCommand(List<String> command, long timeoutMillis) throws IOException {
        if (running.get()) {
            return publicEndpoint;
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);

        Process process = builder.start();
        this.tunnelProcess = process;
        this.running.set(true);

        // Gửi 1 ký tự xuống dòng phòng trường hợp dịch vụ tunnel yêu cầu bỏ qua mật khẩu trống
        try {
            OutputStream stdin = process.getOutputStream();
            stdin.write('\n');
            stdin.flush();
        } catch (IOException ignored) {
        }

        CompletableFuture<ServerEndpoint> endpointFuture = new CompletableFuture<>();
        List<String> recentLines = new ArrayList<>();

        this.outputReaderThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String cleaned = stripAnsi(line).trim();
                    if (!cleaned.isEmpty()) {
                        synchronized (recentLines) {
                            if (recentLines.size() < 20) {
                                recentLines.add(cleaned);
                            }
                        }
                    }
                    Optional<ServerEndpoint> parsed = parsePublicEndpoint(line);
                    if (parsed.isPresent() && !endpointFuture.isDone()) {
                        this.publicEndpoint = parsed.get();
                        endpointFuture.complete(parsed.get());
                    }
                }
            } catch (IOException error) {
                if (!endpointFuture.isDone()) {
                    endpointFuture.completeExceptionally(error);
                }
            } finally {
                if (!endpointFuture.isDone()) {
                    String detail;
                    synchronized (recentLines) {
                        detail = recentLines.isEmpty() ? "Tiến trình tunnel đã dừng trước khi cấp địa chỉ."
                                : String.join(" | ", recentLines);
                    }
                    endpointFuture.completeExceptionally(new IOException("Không mở được đường hầm công khai: " + detail));
                }
                running.set(false);
            }
        }, "Auction-Public-Tunnel");
        this.outputReaderThread.setDaemon(true);
        this.outputReaderThread.start();

        try {
            return endpointFuture.get(Math.max(1000L, timeoutMillis), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            close();
            throw new IOException("Quá thời gian chờ dịch vụ Public Tunnel cấp địa chỉ kết nối. Hãy kiểm tra kết nối Internet.");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            close();
            throw new IOException("Đã hủy mở đường hầm công khai.");
        } catch (ExecutionException execution) {
            close();
            Throwable cause = execution.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException("Không thể khởi tạo Public Tunnel: " + (cause != null ? cause.getMessage() : execution.getMessage()), cause);
        }
    }

    static List<String> buildPinggyCommand(int localPort) {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String nullDevice = isWindows ? "NUL" : "/dev/null";
        return List.of(
                "ssh",
                "-o", "StrictHostKeyChecking=no",
                "-o", "UserKnownHostsFile=" + nullDevice,
                "-o", "ExitOnForwardFailure=yes",
                "-o", "ServerAliveInterval=30",
                "-o", "ServerAliveCountMax=3",
                "-o", "ConnectTimeout=10",
                "-p", "443",
                "-R0:localhost:" + localPort,
                "tcp@a.pinggy.io"
        );
    }

    /**
     * Trích xuất địa chỉ công khai {@code tcp://host:port} từ một dòng log do Pinggy/ngrok in ra.
     */
    public static Optional<ServerEndpoint> parsePublicEndpoint(String rawLine) {
        if (rawLine == null || rawLine.isEmpty()) {
            return Optional.empty();
        }
        String cleaned = stripAnsi(rawLine);
        Matcher matcher = TCP_ENDPOINT_PATTERN.matcher(cleaned);
        if (!matcher.find()) {
            return Optional.empty();
        }
        try {
            String host = matcher.group(1);
            int port = Integer.parseInt(matcher.group(2));
            return Optional.of(new ServerEndpoint(host, port));
        } catch (IllegalArgumentException error) {
            return Optional.empty();
        }
    }

    private static String stripAnsi(String input) {
        return ANSI_ESCAPE_PATTERN.matcher(input).replaceAll("");
    }

    public ServerEndpoint getPublicEndpoint() {
        return publicEndpoint;
    }

    public boolean isRunning() {
        return running.get() && tunnelProcess != null && tunnelProcess.isAlive();
    }

    @Override
    public synchronized void close() {
        running.set(false);
        if (tunnelProcess != null) {
            tunnelProcess.destroy();
            try {
                if (!tunnelProcess.waitFor(1, TimeUnit.SECONDS)) {
                    tunnelProcess.destroyForcibly();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                tunnelProcess.destroyForcibly();
            }
            tunnelProcess = null;
        }
    }
}
