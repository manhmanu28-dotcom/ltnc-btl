package com.auction.server.service;

import com.auction.server.exception.AuctionException;
import com.auction.server.model.auction.Auction;
import com.auction.server.model.auction.AuctionStatus;
import com.auction.server.model.auction.AutoBidConfig;
import com.auction.server.model.auction.BidTransaction;
import com.auction.server.model.entity.Bidder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * AuctionManager: Quản lý trung tâm toàn bộ phiên đấu giá trên Server (Yêu cầu 3.1.4 & 3.6).
 *
 * Áp dụng:
 * - Singleton Pattern (Bill Pugh Thread-safe Holder)
 * - ConcurrentHashMap quản lý truy cập phiên đấu giá đa luồng
 * - ScheduledExecutorService tự động kích hoạt (OPEN -> RUNNING) và đóng phiên (RUNNING -> FINISHED)
 */
public class AuctionManager {

    private final Map<String, Auction> auctionRegistry;
    private final List<AuctionObserver> globalObservers;
    private final AuctionObserver forwardingObserver;
    private ScheduledExecutorService lifecycleScheduler;

    private AuctionManager() {
        this.auctionRegistry = new ConcurrentHashMap<>();
        this.globalObservers = new CopyOnWriteArrayList<>();
        this.forwardingObserver = new AuctionObserver() {
            @Override
            public void onNewBid(Auction auction, BidTransaction transaction, boolean timeExtended) {
                for (AuctionObserver observer : globalObservers) {
                    observer.onNewBid(auction, transaction, timeExtended);
                }
            }

            @Override
            public void onStatusChanged(Auction auction, AuctionStatus oldStatus, AuctionStatus newStatus) {
                for (AuctionObserver observer : globalObservers) {
                    observer.onStatusChanged(auction, oldStatus, newStatus);
                }
            }
        };
    }

    private static final class InstanceHolder {
        private static final AuctionManager INSTANCE = new AuctionManager();
    }

    /**
     * Lấy thể hiện duy nhất của AuctionManager (Singleton Pattern).
     */
    public static AuctionManager getInstance() {
        return InstanceHolder.INSTANCE;
    }

    /**
     * Đăng ký phiên đấu giá mới vào hệ thống quản lý.
     */
    public void registerAuction(Auction auction) {
        if (auction == null) {
            throw new IllegalArgumentException("Auction không được null!");
        }
        auction.addObserver(this.forwardingObserver);
        this.auctionRegistry.put(auction.getId(), auction);
    }

    /**
     * Tìm phiên đấu giá theo ID, ném ngoại lệ nếu không tồn tại.
     */
    public Auction getAuctionOrThrow(String auctionId) {
        if (auctionId == null) {
            throw new AuctionException("Mã phiên đấu giá không được null!");
        }
        Auction auction = this.auctionRegistry.get(auctionId);
        if (auction == null) {
            throw new AuctionException("Không tìm thấy phiên đấu giá với ID: " + auctionId);
        }
        return auction;
    }

    public Auction getAuction(String auctionId) {
        return (auctionId != null) ? this.auctionRegistry.get(auctionId) : null;
    }

    public Collection<Auction> getAllAuctions() {
        return Collections.unmodifiableCollection(new ArrayList<>(this.auctionRegistry.values()));
    }

    public List<Auction> getAuctionsByStatus(AuctionStatus status) {
        return this.auctionRegistry.values().stream()
                .filter(a -> a.getStatus() == status)
                .collect(Collectors.toList());
    }

    /**
     * Điều phối đặt giá thủ công thông qua AuctionManager.
     */
    public BidTransaction placeBid(String auctionId, Bidder bidder, double amount) {
        Auction auction = getAuctionOrThrow(auctionId);
        return auction.placeBid(bidder, amount);
    }

    /**
     * Điều phối đăng ký Auto-Bid thông qua AuctionManager.
     */
    public AutoBidConfig registerAutoBid(String auctionId, Bidder bidder, double maxBid, double increment) {
        Auction auction = getAuctionOrThrow(auctionId);
        return auction.registerAutoBid(bidder, maxBid, increment);
    }

    /**
     * Quét và cập nhật trạng thái tự động (OPEN -> RUNNING -> FINISHED) cho toàn bộ phiên tại mốc thời gian chỉ định.
     */
    public void tickLifecycle(LocalDateTime currentTime) {
        for (Auction auction : this.auctionRegistry.values()) {
            auction.checkAndUpdateLifecycle(currentTime);
        }
    }

    /**
     * Khởi chạy bộ định thời nền tự động đóng/mở phiên mỗi intervalMillis mili-giây.
     */
    public synchronized void startLifecycleMonitor(long intervalMillis) {
        if (this.lifecycleScheduler != null && !this.lifecycleScheduler.isShutdown()) {
            return;
        }
        this.lifecycleScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Auction-Lifecycle-Monitor");
            thread.setDaemon(true);
            return thread;
        });
        long safeInterval = Math.max(100L, intervalMillis);
        this.lifecycleScheduler.scheduleAtFixedRate(
                () -> tickLifecycle(LocalDateTime.now()),
                0L,
                safeInterval,
                TimeUnit.MILLISECONDS
        );
    }

    /**
     * Dừng bộ định thời nền an toàn.
     */
    public synchronized void stopLifecycleMonitor() {
        if (this.lifecycleScheduler != null) {
            this.lifecycleScheduler.shutdownNow();
            this.lifecycleScheduler = null;
        }
    }

    public void addGlobalObserver(AuctionObserver observer) {
        if (observer != null && !this.globalObservers.contains(observer)) {
            this.globalObservers.add(observer);
        }
    }

    public void removeGlobalObserver(AuctionObserver observer) {
        this.globalObservers.remove(observer);
    }

    /**
     * Xóa toàn bộ dữ liệu phiên trong bộ nhớ (phục vụ cô lập môi trường khi chạy Unit Test).
     */
    public void clearAll() {
        stopLifecycleMonitor();
        this.auctionRegistry.clear();
        this.globalObservers.clear();
    }
}
