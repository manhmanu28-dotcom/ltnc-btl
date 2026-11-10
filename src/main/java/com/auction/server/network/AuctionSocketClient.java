package com.auction.server.network;

import com.auction.server.network.protocol.ActionType;
import com.auction.server.network.protocol.SocketRequest;
import com.auction.server.network.protocol.SocketResponse;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * AuctionSocketClient: Bộ kết nối Socket đa luồng phía Client (hỗ trợ trực tiếp cho Thành viên 3 & 4 làm JavaFX GUI).
 *
 * Đặc điểm kỹ thuật:
 * - Duy trì 1 luồng đọc nền (Listener Thread) liên tục nhận các dòng JSON từ Server.
 * - Tự động phân loại bản tin:
 *   1. Nếu gói tin có 'requestId' khớp với yêu cầu vừa gửi -> Hoàn tất CompletableFuture của lệnh đó (Request-Response đồng bộ/bất đồng bộ).
 *   2. Nếu gói tin là bản tin Realtime Push từ Server (BID_UPDATE, STATUS_UPDATE, NOTIFY_UPDATE) -> Kích hoạt ngay lập tức danh sách callback Consumer<SocketResponse> để GUI cập nhật qua Platform.runLater().
 */
public class AuctionSocketClient implements Closeable {

    private final String host;
    private final int port;
    private final Map<String, CompletableFuture<SocketResponse>> pendingRequests;
    private final List<Consumer<SocketResponse>> realtimeListeners;
    private final AtomicBoolean connected;

    private Socket socket;
    private BufferedReader reader;
    private PrintWriter writer;
    private Thread listenerThread;

    public AuctionSocketClient(String host, int port) {
        this.host = (host != null && !host.trim().isEmpty()) ? host.trim() : "127.0.0.1";
        this.port = port;
        this.pendingRequests = new ConcurrentHashMap<>();
        this.realtimeListeners = new CopyOnWriteArrayList<>();
        this.connected = new AtomicBoolean(false);
    }

    /**
     * Mở kết nối TCP tới AuctionServer và khởi chạy luồng lắng nghe Realtime.
     */
    public synchronized void connect() throws IOException {
        if (this.connected.get()) {
            return;
        }
        Socket newSocket = new Socket();
        try {
            newSocket.connect(new InetSocketAddress(this.host, this.port), 5000);
        } catch (IOException | RuntimeException error) {
            newSocket.close();
            throw error;
        }
        this.socket = newSocket;
        this.socket.setTcpNoDelay(true);
        this.reader = new BufferedReader(
                new InputStreamReader(this.socket.getInputStream(), StandardCharsets.UTF_8));
        this.writer = new PrintWriter(
                new OutputStreamWriter(this.socket.getOutputStream(), StandardCharsets.UTF_8), true);
        this.connected.set(true);

        this.listenerThread = new Thread(this::listenLoop, "Auction-Client-Listener");
        this.listenerThread.setDaemon(true);
        this.listenerThread.start();
    }

    private void listenLoop() {
        try {
            String line;
            while (this.connected.get() && (line = this.reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                SocketResponse response = SocketResponse.fromJson(trimmed);
                String reqId = response.getRequestId();

                // Nếu là phản hồi trực tiếp cho một Request đang chờ
                if (reqId != null && this.pendingRequests.containsKey(reqId)) {
                    CompletableFuture<SocketResponse> future = this.pendingRequests.remove(reqId);
                    if (future != null) {
                        future.complete(response);
                    }
                    continue;
                }

                // Nếu là gói tin Realtime Broadcast từ Server đẩy về (BID_UPDATE, STATUS_UPDATE, NOTIFY_UPDATE)
                notifyRealtimeListeners(response);
            }
        } catch (Exception ignored) {
            // Đóng kết nối
        } finally {
            close();
        }
    }

    /**
     * Gửi yêu cầu lên Server và nhận kết quả phản hồi đồng bộ (có timeout an toàn).
     */
    public SocketResponse sendRequest(SocketRequest request, long timeoutMillis) {
        if (!this.connected.get()) {
            throw new IllegalStateException("Client chưa kết nối tới AuctionServer!");
        }
        CompletableFuture<SocketResponse> future = new CompletableFuture<>();
        this.pendingRequests.put(request.getRequestId(), future);

        synchronized (this) {
            this.writer.println(request.toJson());
        }

        try {
            return future.get(Math.max(500L, timeoutMillis), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            this.pendingRequests.remove(request.getRequestId());
            return SocketResponse.error(request, "Quá thời gian chờ phản hồi từ Server: " + e.getMessage());
        }
    }

    public SocketResponse sendRequest(SocketRequest request) {
        return sendRequest(request, 5000L);
    }

    /**
     * Đăng ký listener nhận thông báo Realtime (BID_UPDATE, STATUS_UPDATE) từ Server.
     * Trên JavaFX, Thành viên 3 chỉ cần gọi:
     * client.addRealtimeListener(resp -> Platform.runLater(() -> updateUI(resp)));
     */
    public void addRealtimeListener(Consumer<SocketResponse> listener) {
        if (listener != null) {
            this.realtimeListeners.add(listener);
        }
    }

    public void removeRealtimeListener(Consumer<SocketResponse> listener) {
        this.realtimeListeners.remove(listener);
    }

    private void notifyRealtimeListeners(SocketResponse response) {
        for (Consumer<SocketResponse> listener : this.realtimeListeners) {
            try {
                listener.accept(response);
            } catch (Exception ignored) {
            }
        }
    }

    public boolean isConnected() {
        return this.connected.get();
    }

    @Override
    public synchronized void close() {
        if (this.connected.compareAndSet(true, false)) {
            for (CompletableFuture<SocketResponse> future : this.pendingRequests.values()) {
                future.cancel(true);
            }
            this.pendingRequests.clear();
            if (this.socket != null && !this.socket.isClosed()) {
                try {
                    this.socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}

