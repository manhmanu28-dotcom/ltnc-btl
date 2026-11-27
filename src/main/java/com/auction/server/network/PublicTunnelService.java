package com.auction.server.network;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Tự động tạo khóa SSH Ed25519 không mật khẩu và chạy OpenSSH ở chế độ không tương tác
 * (BatchMode=yes, -T) với 127.0.0.1 để không bao giờ bị treo hỏi mật khẩu trên Windows.
 */
public class PublicTunnelService implements Closeable {

    private static final Pattern ANSI_ESCAPE_PATTERN = Pattern.compile("\\u001B\\[[;?\\d]*[ -/]*[@-~]");
    private static final Pattern TCP_ENDPOINT_PATTERN = Pattern.compile(
            "tcp://([a-zA-Z0-9.-]+):(\\d{1,5})", Pattern.CASE_INSENSITIVE);
    private static final List<String> PINGGY_TARGETS = List.of("tcp@free.pinggy.io", "tcp@a.pinggy.io");

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Process tunnelProcess;
    private Thread outputReaderThread;
    private volatile ServerEndpoint publicEndpoint;

    /**
     * Khởi chạy đường hầm công khai trỏ về cổng TCP nội bộ {@code localPort}.
     */
    public synchronized ServerEndpoint startPinggyTunnel(int localPort, long timeoutMillis) throws IOException {
        if (localPort < 1 || localPort > 65535) {
            throw new IllegalArgumentException("Cổng localPort không hợp lệ: " + localPort);
        }
        Path keyFile = ensureTunnelSshKey();
        IOException lastError = null;
        long perTargetTimeout = Math.max(6_000L, timeoutMillis / PINGGY_TARGETS.size());

        for (String target : PINGGY_TARGETS) {
            try {
                return startWithCommand(buildPinggyCommand(localPort, keyFile, target), perTargetTimeout);
            } catch (IOException error) {
                lastError = error;
                close();
            }
        }
        throw lastError != null ? lastError
                : new IOException("Không thể mở Public TCP Tunnel (Pinggy SSH).");
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
            throw new IOException("Không thể khởi tạo Public Tunnel: "
                    + (cause != null ? cause.getMessage() : execution.getMessage()), cause);
        }
    }

    private static Path ensureTunnelSshKey() {
        try {
            Path keyDir = Path.of(System.getProperty("user.home", "."), ".bidion");
            Path keyFile = keyDir.resolve("tunnel_ed25519");
            if (Files.isRegularFile(keyFile)) {
                return keyFile;
            }
            Files.createDirectories(keyDir);
            Process keygen = new ProcessBuilder(
                    "ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", keyFile.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .start();
            keygen.waitFor(8, TimeUnit.SECONDS);
            if (Files.isRegularFile(keyFile)) {
                return keyFile;
            }
        } catch (Exception ignored) {
            // Nếu máy chưa có ssh-keygen vẫn thử chạy ssh mặc định
        }
        return null;
    }

    static List<String> buildPinggyCommand(int localPort, Path keyFile, String targetHost) {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String nullDevice = isWindows ? "NUL" : "/dev/null";
        List<String> command = new ArrayList<>();
        command.add("ssh");
        command.add("-T");
        command.add("-o");
        command.add("StrictHostKeyChecking=no");
        command.add("-o");
        command.add("UserKnownHostsFile=" + nullDevice);
        command.add("-o");
        command.add("ExitOnForwardFailure=yes");
        command.add("-o");
        command.add("ServerAliveInterval=30");
        command.add("-o");
        command.add("ServerAliveCountMax=3");
        command.add("-o");
        command.add("ConnectTimeout=10");
        if (keyFile != null && Files.isRegularFile(keyFile)) {
            command.add("-o");
            command.add("BatchMode=yes");
            command.add("-i");
            command.add(keyFile.toAbsolutePath().toString());
            command.add("-o");
            command.add("IdentitiesOnly=yes");
        }
        command.add("-p");
        command.add("443");
        command.add("-R");
        command.add("0:127.0.0.1:" + localPort);
        command.add(targetHost);
        return command;
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
