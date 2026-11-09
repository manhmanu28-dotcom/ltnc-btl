package com.auction.server.network;

import com.auction.server.model.auction.Auction;
import com.auction.server.model.auction.AuctionStatus;
import com.auction.server.model.auction.BidTransaction;
import com.auction.server.network.protocol.ActionType;
import com.auction.server.network.protocol.JsonProtocolUtil;
import com.auction.server.network.protocol.SocketResponse;
import com.auction.server.service.AuctionObserver;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * SocketBroadcastManager: Quản lý Realtime Update qua Socket theo mẫu thiết kế Observer nâng cao
 * (Yêu cầu 3.2.4, 3.6 & Nhiệm vụ Thành viên 2).
 *
 * Đặc điểm kỹ thuật:
 * - Cài đặt giao diện AuctionObserver để lắng nghe sự kiện trực tiếp từ Auction / AuctionManager.
 * - Quản lý danh sách các Client đang mở xem từng phiên đấu giá cụ thể (Room/Topic Subscription)
 *   cũng như danh sách seluruh Client đang kết nối.
 * - Khi có bid mới hợp lệ, gia hạn Anti-sniping hoặc chuyển trạng thái phiên, Server lập tức đẩy (Push/Broadcast)
 *   gói tin JSON xuống các Client liên quan mà KHÔNG SỬ DỤNG POLLING LIÊN TỤC.
 * - Đảm bảo Thread-safe tuyệt đối khi nhiều luồng vừa thêm/xóa Client vừa duyệt danh sách gửi tin
 *   nhờ ConcurrentHashMap và CopyOnWriteArraySet.
 */
public class SocketBroadcastManager implements AuctionObserver {

    // Danh sách tất cả các Client đang kết nối tới Server
    private final Set<ClientHandler> connectedClients;

    // Bản đồ ánh xạ: auctionId -> Tập hợp các Client đang mở xem trực tiếp phiên đấu giá đó
    private final Map<String, Set<ClientHandler>> auctionWatchers;

    public SocketBroadcastManager() {
        this.connectedClients = new CopyOnWriteArraySet<>();
        this.auctionWatchers = new ConcurrentHashMap<>();
    }

    // =========================================================================
    // 1. QUẢN LÝ ĐĂNG KÝ THEO DÕI PHIÊN (THREAD-SAFE SUBSCRIPTION MANAGEMENT)
    // =========================================================================

    /**
     * Đăng ký một kết nối Client mới khi vừa kết nối tới Server.
     */
    public void registerClient(ClientHandler client) {
        if (client != null) {
            this.connectedClients.add(client);
        }
    }

    /**
     * Hủy đăng ký kết nối Client khi ngắt kết nối và dọn dẹp khỏi mọi phòng đấu giá.
     */
    public void unregisterClient(ClientHandler client) {
        if (client == null) {
            return;
        }
        this.connectedClients.remove(client);
        for (Set<ClientHandler> watchers : this.auctionWatchers.values()) {
            watchers.remove(client);
        }
    }

    /**
     * Đăng ký cho một Client theo dõi trực tiếp (Watch) một phiên đấu giá cụ thể.
     */
    public void watchAuction(String auctionId, ClientHandler client) {
        if (auctionId == null || auctionId.trim().isEmpty() || client == null) {
            return;
        }
        this.connectedClients.add(client);
        this.auctionWatchers
                .computeIfAbsent(auctionId.trim(), k -> new CopyOnWriteArraySet<>())
                .add(client);
    }

    /**
     * Hủy theo dõi một phiên đấu giá cụ thể khi Client rời màn hình phòng đấu giá.
     */
    public void unwatchAuction(String auctionId, ClientHandler client) {
        if (auctionId == null || client == null) {
            return;
        }
        Set<ClientHandler> watchers = this.auctionWatchers.get(auctionId.trim());
        if (watchers != null) {
            watchers.remove(client);
        }
    }

    public int getWatcherCount(String auctionId) {
        if (auctionId == null) {
            return 0;
        }
        Set<ClientHandler> watchers = this.auctionWatchers.get(auctionId.trim());
        return (watchers != null) ? watchers.size() : 0;
    }

    public int getConnectedClientCount() {
        return this.connectedClients.size();
    }

    // =========================================================================
    // 2. OBSERVER CALLBACKS: PHÁT REALTIME UPDATE KHI CÓ BID MỚI / ĐỔI TRẠNG THÁI
    // =========================================================================

    @Override
    public void onNewBid(Auction auction, BidTransaction transaction, boolean timeExtended) {
        String message = timeExtended
                ? String.format("Có lượt đặt giá mới %,.0f VNĐ từ %s! Phiên đấu giá được gia hạn Anti-sniping đến %s.",
                transaction.getAmount(), transaction.getBidderUsername(), auction.getEndTime())
                : String.format("Có lượt đặt giá mới %,.0f VNĐ từ %s!",
                transaction.getAmount(), transaction.getBidderUsername());

        SocketResponse broadcastMsg = SocketResponse.broadcast(ActionType.BID_UPDATE, auction.getId(), message)
                .putData("eventType", "NEW_BID")
                .putData("timeExtended", timeExtended)
                .putData("newEndTime", auction.getEndTime().toString())
                .putData("currentHighestBid", auction.getCurrentHighestBid())
                .putData("minimumNextBid", auction.getMinimumNextBid())
                .putData("currentWinnerId", auction.getCurrentWinnerId())
                .putData("currentWinnerUsername", auction.getCurrentWinnerUsername())
                .putData("transaction", JsonProtocolUtil.toBidTransactionDto(transaction))
                .putData("auction", JsonProtocolUtil.toAuctionDto(auction, false));

        broadcastToAuction(auction.getId(), broadcastMsg);
    }

    @Override
    public void onStatusChanged(Auction auction, AuctionStatus oldStatus, AuctionStatus newStatus) {
        String message = String.format("Phiên đấu giá [%s] chuyển trạng thái từ %s sang %s.",
                auction.getId(), oldStatus, newStatus);

        SocketResponse broadcastMsg = SocketResponse.broadcast(ActionType.STATUS_UPDATE, auction.getId(), message)
                .putData("eventType", "STATUS_CHANGED")
                .putData("oldStatus", oldStatus != null ? oldStatus.name() : null)
                .putData("newStatus", newStatus.name())
                .putData("currentHighestBid", auction.getCurrentHighestBid())
                .putData("currentWinnerId", auction.getCurrentWinnerId())
                .putData("currentWinnerUsername", auction.getCurrentWinnerUsername())
                .putData("auction", JsonProtocolUtil.toAuctionDto(auction, false));

        broadcastToAuction(auction.getId(), broadcastMsg);
    }

    // =========================================================================
    // 3. CƠ CHẾ PHÁT GÓI TIN AN TOÀN ĐA LUỒNG (THREAD-SAFE DISPATCH)
    // =========================================================================

    /**
     * Gửi bản tin Realtime tới các Client đang xem phiên đấu giá chỉ định
     * (Nếu một Client đang ở màn hình danh sách chung nhưng chưa vào phòng cụ thể,
     * vẫn nhận được thông báo nếu chưa có watcher riêng hoặc gửi cho tất cả watcher của phiên).
     */
    public void broadcastToAuction(String auctionId, SocketResponse response) {
        if (response == null) {
            return;
        }
        String jsonLine = response.toJson();
        Set<ClientHandler> watchers = (auctionId != null) ? this.auctionWatchers.get(auctionId.trim()) : null;

        if (watchers != null && !watchers.isEmpty()) {
            for (ClientHandler client : watchers) {
                if (!client.sendJsonLine(jsonLine)) {
                    unregisterClient(client);
                }
            }
        } else {
            // Nếu chưa phân phòng cụ thể, phát cho tất cả các Client đang kết nối
            broadcastToAll(jsonLine);
        }
    }

    /**
     * Phát bản tin JSON tới toàn bộ Client đang kết nối trên hệ thống.
     */
    public void broadcastToAll(String jsonLine) {
        for (ClientHandler client : this.connectedClients) {
            if (!client.sendJsonLine(jsonLine)) {
                unregisterClient(client);
            }
        }
    }
}

