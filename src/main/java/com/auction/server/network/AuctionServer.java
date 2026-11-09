package com.auction.server.network;

import com.auction.server.model.entity.Bidder;
import com.auction.server.service.AuctionManager;

import java.io.Closeable;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AuctionServer: Máy chủ đấu giá đa luồng sử dụng ServerSocket và ThreadPoolExecutor
 * (Yêu cầu 3.4 & Nhiệm vụ Thành viên 2).
 *
 * Đặc điểm kỹ thuật:
 * - Lắng nghe kết nối TCP/IP từ nhiều Client đồng thời (hỗ trợ cấu hình cổng động port = 0 cho môi trường kiểm thử).
 * - Quản lý luồng xử lý bằng ThreadPoolExecutor nhằm kiểm soát tài nguyên hệ thống và chịu tải cao.
 * - Tự động kết nối SocketBroadcastManager (Observer Pattern) vào AuctionManager để đẩy thông báo Realtime
 *   ngay khi có lượt đặt giá mới, gia hạn Anti-sniping hoặc chuyển trạng thái phiên.
 */
public class AuctionServer implements Closeable {

    public static final int DEFAULT_PORT = 8888;
    public static final int DEFAULT_CORE_POOL_SIZE = 16;
    public static final int DEFAULT_MAX_POOL_SIZE = 128;

    private final int configuredPort;
    private final AuctionManager auctionManager;
    private final SocketBroadcastManager broadcastManager;
    private final ConcurrentMap<String, Bidder> activeBidders;
    private final ExecutorService clientThreadPool;
    private final AtomicBoolean running;

    private ServerSocket serverSocket;
    private Thread acceptorThread;

    public AuctionServer() {
        this(DEFAULT_PORT);
    }

    public AuctionServer(int port) {
        this(port, DEFAULT_CORE_POOL_SIZE, DEFAULT_MAX_POOL_SIZE);
    }

    public AuctionServer(int port, int corePoolSize, int maxPoolSize) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Cổng mạng (port) phải nằm trong khoảng 0..65535!");
        }
        this.configuredPort = port;
        this.auctionManager = AuctionManager.getInstance();
        this.broadcastManager = new SocketBroadcastManager();
        this.activeBidders = new ConcurrentHashMap<>();
        this.running = new AtomicBoolean(false);

        AtomicInteger threadCounter = new AtomicInteger(1);
        ThreadFactory threadFactory = runnable -> {
            Thread t = new Thread(runnable, "Auction-Client-Worker-" + threadCounter.getAndIncrement());
            t.setDaemon(true);
            return t;
        };

        this.clientThreadPool = new ThreadPoolExecutor(
                Math.max(2, corePoolSize),
                Math.max(4, maxPoolSize),
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1024),
                threadFactory,
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    /**
     * Khởi động Server Socket trên một luồng nền (Non-blocking start),
     * đồng thời đăng ký Global Observer và bật bộ định thời vòng đời phiên đấu giá.
     */
    public synchronized void start() throws IOException {
        if (this.running.get()) {
            return;
        }
        this.serverSocket = new ServerSocket(this.configuredPort);
        this.running.set(true);

        // Đăng ký Observer để mọi sự kiện từ AuctionManager tự động Broadcast qua Socket
        this.auctionManager.addGlobalObserver(this.broadcastManager);
        this.auctionManager.startLifecycleMonitor(500L);

        this.acceptorThread = new Thread(this::acceptLoop, "Auction-Server-Acceptor");
        this.acceptorThread.setDaemon(true);
        this.acceptorThread.start();
    }

    private void acceptLoop() {
        while (this.running.get()) {
            try {
                Socket clientSocket = this.serverSocket.accept();
                clientSocket.setTcpNoDelay(true);
                ClientHandler handler = new ClientHandler(
                        clientSocket,
                        this.auctionManager,
                        this.broadcastManager,
                        this.activeBidders
                );
                this.clientThreadPool.execute(handler);
            } catch (SocketException e) {
                // ServerSocket đã đóng khi gọi stop()
                break;
            } catch (IOException e) {
                if (!this.running.get()) {
                    break;
                }
            }
        }
    }

    /**
     * Dừng Server an toàn, đóng ServerSocket và giải phóng ThreadPoolExecutor.
     */
    public synchronized void stop() {
        if (!this.running.compareAndSet(true, false)) {
            return;
        }
        this.auctionManager.removeGlobalObserver(this.broadcastManager);

        if (this.serverSocket != null && !this.serverSocket.isClosed()) {
            try {
                this.serverSocket.close();
            } catch (IOException ignored) {
            }
        }
        this.clientThreadPool.shutdownNow();
    }

    @Override
    public void close() {
        stop();
    }

    /**
     * Trả về cổng thực tế mà Server đang lắng nghe (hữu ích khi truyền port = 0 để hệ điều hành tự cấp cổng trống).
     */
    public int getLocalPort() {
        if (this.serverSocket != null && !this.serverSocket.isClosed()) {
            return this.serverSocket.getLocalPort();
        }
        return this.configuredPort;
    }

    public boolean isRunning() {
        return this.running.get();
    }

    public SocketBroadcastManager getBroadcastManager() {
        return this.broadcastManager;
    }

    public ConcurrentMap<String, Bidder> getActiveBidders() {
        return this.activeBidders;
    }
}

