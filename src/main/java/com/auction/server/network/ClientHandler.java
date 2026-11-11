package com.auction.server.network;

import com.auction.server.exception.AuctionException;
import com.auction.server.model.auction.Auction;
import com.auction.server.model.auction.AuctionStatus;
import com.auction.server.model.auction.AutoBidConfig;
import com.auction.server.model.auction.BidTransaction;
import com.auction.server.model.entity.Bidder;
import com.auction.server.model.entity.User;
import com.auction.server.service.AuthenticationService;
import com.auction.server.model.item.Item;
import com.auction.server.model.item.ItemFactory;
import com.auction.server.network.protocol.ActionType;
import com.auction.server.network.protocol.JsonProtocolUtil;
import com.auction.server.network.protocol.SocketRequest;
import com.auction.server.network.protocol.SocketResponse;
import com.auction.server.service.AuctionManager;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ClientHandler: Luồng xử lý chuyên biệt cho từng kết nối Client trên Server Socket
 * (Yêu cầu 3.4 & Nhiệm vụ Thành viên 2).
 *
 * Chức năng chính:
 * 1. Đọc từng dòng bản tin JSON Request từ luồng Socket Input.
 * 2. Giải mã sang đối tượng SocketRequest và điều phối tới AuctionManager theo ActionType:
 *    - LOGIN, PING, LOGOUT
 *    - GET_AUCTIONS, GET_AUCTION_DETAIL
 *    - WATCH_AUCTION, UNWATCH_AUCTION (Đăng ký Observer nhận Realtime Push)
 *    - PLACE_BID (Đặt giá thủ công, tự động kiểm tra Anti-sniping)
 *    - AUTO_BID (Đăng ký đấu giá tự động với maxBid, increment vào PriorityQueue)
 * 3. Bắt và chuyển đổi toàn bộ ngoại lệ nghiệp vụ (InvalidBidException, AuctionClosedException...)
 *    thành bản tin JSON Response hợp lệ để Client hiển thị thông báo rõ ràng.
 * 4. Hỗ trợ gửi gói tin đồng bộ (synchronized writeLock) tránh xung đột dữ liệu trên đường truyền
 *    khi luồng phản hồi trực tiếp và luồng Broadcast Observer cùng ghi ra một Socket.
 */
public class ClientHandler implements Runnable, Closeable {

    private final String clientId;
    private final Socket socket;
    private final AuctionManager auctionManager;
    private final SocketBroadcastManager broadcastManager;
    private final ConcurrentMap<String, Bidder> activeBidders;

    private final Object writeLock = new Object();
    private final AtomicBoolean running = new AtomicBoolean(true);

    private BufferedReader reader;
    private PrintWriter writer;
    private Bidder authenticatedBidder;

    public ClientHandler(Socket socket,
                         AuctionManager auctionManager,
                         SocketBroadcastManager broadcastManager,
                         ConcurrentMap<String, Bidder> activeBidders) {
        this.clientId = "CLIENT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        this.socket = socket;
        this.auctionManager = auctionManager;
        this.broadcastManager = broadcastManager;
        this.activeBidders = activeBidders;
    }

    @Override
    public void run() {
        try {
            this.reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            this.writer = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

            broadcastManager.registerClient(this);

            String line;
            while (running.get() && (line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                SocketResponse response = processJsonMessage(trimmed);
                if (response != null) {
                    sendResponse(response);
                }
            }
        } catch (IOException ignored) {
            // Client ngắt kết nối đột ngột hoặc đóng cửa sổ ứng dụng
        } finally {
            close();
        }
    }

    /**
     * Xử lý một chuỗi bản tin JSON gửi lên từ Client và trả về đối tượng SocketResponse tương ứng.
     * Phương thức được tách riêng để có thể kiểm thử trực tiếp bằng Unit Test mà không cần mở cổng mạng.
     */
    public SocketResponse processJsonMessage(String rawJson) {
        SocketRequest request = null;
        try {
            request = SocketRequest.fromJson(rawJson);
            return dispatchRequest(request);
        } catch (AuctionException | IllegalArgumentException e) {
            return SocketResponse.error(request, e.getMessage());
        } catch (Exception e) {
            return SocketResponse.error(request, "Lỗi xử lý yêu cầu trên Server: " + e.getMessage());
        }
    }

    /**
     * Điều phối thực thi yêu cầu theo ActionType.
     */
    public SocketResponse dispatchRequest(SocketRequest request) {
        if (request == null || request.getAction() == null) {
            throw new IllegalArgumentException("Yêu cầu không hợp lệ: Thiếu trường 'action'!");
        }

        ActionType action = request.getAction();
        switch (action) {
            case PING:
                return SocketResponse.ok(request, "PONG")
                        .putData("clientId", clientId);

            case LOGIN:
                return handleLogin(request);

            case REGISTER:
                return handleRegister(request);

            case LOGOUT:
                return handleLogout(request);

            case GET_AUCTIONS:
                return handleGetAuctions(request);

            case GET_AUCTION_DETAIL:
                return handleGetAuctionDetail(request);

            case WATCH_AUCTION:
                return handleWatchAuction(request);

            case UNWATCH_AUCTION:
                return handleUnwatchAuction(request);

            case PLACE_BID:
                return handlePlaceBid(request);

            case AUTO_BID:
                return handleAutoBid(request);

            case CREATE_AUCTION:
                return handleCreateAuction(request);

            case CANCEL_AUCTION:
                return handleCancelAuction(request);

            default:
                return SocketResponse.error(request, "Chức năng chưa được hỗ trợ: " + action);
        }
    }

    // =========================================================================
    // CÁC PHƯƠNG THỨC XỬ LÝ NGHIỆP VỤ CHI TIẾT CHO TỪNG ACTION TYPE
    // =========================================================================

    private SocketResponse handleLogin(SocketRequest request) {
        this.authenticatedBidder = null;
        User user = new AuthenticationService().login(request.getPayloadString("username"), passwordOf(request));
        if (user instanceof Bidder bidder) {
            this.authenticatedBidder = activeBidders.computeIfAbsent(bidder.getId(), id -> bidder);
        }
        return SocketResponse.ok(request, "Đăng nhập thành công!")
                .putData("clientId", clientId)
                .putData("userId", user.getId())
                .putData("username", user.getUsername())
                .putData("role", user.getRoleName());
    }

    private SocketResponse handleRegister(SocketRequest request) {
        User user = new AuthenticationService().register(request.getPayloadString("username"),
                passwordOf(request), request.getPayloadString("email"));
        return SocketResponse.ok(request, "Đăng ký thành công. Bạn có thể đăng nhập ngay.")
                .putData("userId", user.getId())
                .putData("username", user.getUsername());
    }

    private String passwordOf(SocketRequest request) {
        Object password = request.getPayload().get("password");
        return password instanceof String value ? value : null;
    }

    private SocketResponse handleLogout(SocketRequest request) {
        this.authenticatedBidder = null;
        return SocketResponse.ok(request, "Đã đăng xuất khỏi phiên làm việc.");
    }

    private SocketResponse handleGetAuctions(SocketRequest request) {
        String statusFilter = request.getPayloadString("status");
        Collection<Auction> auctions;
        if (statusFilter != null && !statusFilter.isEmpty()) {
            AuctionStatus status = AuctionStatus.valueOf(statusFilter.toUpperCase());
            auctions = auctionManager.getAuctionsByStatus(status);
        } else {
            auctions = auctionManager.getAllAuctions();
        }

        List<Map<String, Object>> auctionList = new ArrayList<>(auctions.size());
        for (Auction auction : auctions) {
            auctionList.add(JsonProtocolUtil.toAuctionDto(auction, false));
        }

        return SocketResponse.ok(request, "Lấy danh sách phiên đấu giá thành công.")
                .putData("total", auctionList.size())
                .putData("auctions", auctionList);
    }

    private SocketResponse handleGetAuctionDetail(SocketRequest request) {
        String auctionId = requireAuctionId(request);
        Auction auction = auctionManager.getAuctionOrThrow(auctionId);

        return SocketResponse.ok(request, "Lấy chi tiết phiên đấu giá thành công.")
                .putData("auction", JsonProtocolUtil.toAuctionDto(auction, true));
    }

    private SocketResponse handleWatchAuction(SocketRequest request) {
        String auctionId = requireAuctionId(request);
        Auction auction = auctionManager.getAuctionOrThrow(auctionId);
        broadcastManager.watchAuction(auction.getId(), this);

        return SocketResponse.ok(request, "Đã đăng ký nhận cập nhật Realtime cho phiên: " + auction.getId())
                .putData("watcherCount", broadcastManager.getWatcherCount(auction.getId()))
                .putData("auction", JsonProtocolUtil.toAuctionDto(auction, true));
    }

    private SocketResponse handleUnwatchAuction(SocketRequest request) {
        String auctionId = requireAuctionId(request);
        broadcastManager.unwatchAuction(auctionId, this);
        return SocketResponse.ok(request, "Đã hủy theo dõi phiên đấu giá: " + auctionId);
    }

    private SocketResponse handlePlaceBid(SocketRequest request) {
        String auctionId = requireAuctionId(request);
        Bidder bidder = resolveBidder(request);
        double amount = request.getRequiredPayloadDouble("amount");

        // Tự động cho Client vào danh sách theo dõi phiên khi đặt giá
        broadcastManager.watchAuction(auctionId, this);

        BidTransaction transaction = auctionManager.placeBid(auctionId, bidder, amount);
        Auction updatedAuction = auctionManager.getAuctionOrThrow(auctionId);

        return SocketResponse.ok(request, "Đặt giá thành công!")
                .putData("transaction", JsonProtocolUtil.toBidTransactionDto(transaction))
                .putData("currentHighestBid", updatedAuction.getCurrentHighestBid())
                .putData("minimumNextBid", updatedAuction.getMinimumNextBid())
                .putData("currentWinnerId", updatedAuction.getCurrentWinnerId())
                .putData("currentWinnerUsername", updatedAuction.getCurrentWinnerUsername())
                .putData("endTime", updatedAuction.getEndTime().toString());
    }

    private SocketResponse handleAutoBid(SocketRequest request) {
        String auctionId = requireAuctionId(request);
        Bidder bidder = resolveBidder(request);
        double maxBid = request.getRequiredPayloadDouble("maxBid");
        Auction auction = auctionManager.getAuctionOrThrow(auctionId);
        double increment = request.getPayloadDouble("increment", auction.getMinIncrement());

        broadcastManager.watchAuction(auctionId, this);

        AutoBidConfig config = auctionManager.registerAutoBid(auctionId, bidder, maxBid, increment);

        return SocketResponse.ok(request, "Đăng ký Auto-Bid thành công!")
                .putData("bidderId", config.getBidderId())
                .putData("maxBid", config.getMaxBid())
                .putData("increment", config.getIncrement())
                .putData("currentHighestBid", auction.getCurrentHighestBid())
                .putData("currentWinnerId", auction.getCurrentWinnerId())
                .putData("currentWinnerUsername", auction.getCurrentWinnerUsername());
    }

    private SocketResponse handleCreateAuction(SocketRequest request) {
        String sellerId = request.getUserId();
        if (sellerId == null || sellerId.trim().isEmpty()) {
            sellerId = request.getPayloadString("sellerId");
        }
        if (sellerId == null || sellerId.trim().isEmpty()) {
            throw new IllegalArgumentException("Thiếu mã người bán (sellerId/userId) khi tạo phiên đấu giá!");
        }

        String categoryStr = request.getPayloadString("category");
        ItemFactory.ItemType itemType = (categoryStr != null && !categoryStr.isEmpty())
                ? ItemFactory.ItemType.valueOf(categoryStr.trim().toUpperCase())
                : ItemFactory.ItemType.ELECTRONICS;

        String itemId = request.getPayloadString("itemId");
        if (itemId == null || itemId.isEmpty()) {
            itemId = "ITEM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        }
        String title = request.getPayloadString("title");
        if (title == null || title.isEmpty()) {
            throw new IllegalArgumentException("Tên sản phẩm đấu giá (title) không được để trống!");
        }
        String description = request.getPayloadString("description");
        if (description == null) {
            description = "";
        }
        double startingPrice = request.getRequiredPayloadDouble("startingPrice");
        double minIncrement = request.getPayloadDouble("minIncrement", Math.max(10_000.0, startingPrice * 0.05));

        Item item = ItemFactory.createItem(itemType, itemId, title, description, startingPrice, request.getPayload());

        String auctionId = request.getAuctionId();
        if (auctionId == null || auctionId.trim().isEmpty()) {
            auctionId = "AUC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        }

        String startTimeStr = request.getPayloadString("startTime");
        String endTimeStr = request.getPayloadString("endTime");
        int durationMinutes = request.getPayloadInt("durationMinutes", 60);

        LocalDateTime startTime = (startTimeStr != null && !startTimeStr.isEmpty())
                ? LocalDateTime.parse(startTimeStr)
                : LocalDateTime.now().minusSeconds(1);
        LocalDateTime endTime = (endTimeStr != null && !endTimeStr.isEmpty())
                ? LocalDateTime.parse(endTimeStr)
                : startTime.plusMinutes(Math.max(1, durationMinutes));

        Auction auction = new Auction(auctionId.trim(), item, sellerId.trim(), minIncrement, startTime, endTime);
        boolean startImmediately = !"false".equalsIgnoreCase(request.getPayloadString("startImmediately"));
        if (startImmediately && !startTime.isAfter(LocalDateTime.now())) {
            auction.startAuction();
        }

        auctionManager.registerAuction(auction);

        SocketResponse response = SocketResponse.ok(request, "Tạo phiên đấu giá mới thành công!")
                .putData("auction", JsonProtocolUtil.toAuctionDto(auction, false));

        // Thông báo cho toàn bộ Client biết có phiên đấu giá mới được mở
        SocketResponse notifyNewAuction = SocketResponse.broadcast(
                        ActionType.STATUS_UPDATE,
                        auction.getId(),
                        "Phiên đấu giá mới vừa được mở: " + item.getTitle())
                .putData("eventType", "AUCTION_CREATED")
                .putData("auction", JsonProtocolUtil.toAuctionDto(auction, false));
        broadcastManager.broadcastToAll(notifyNewAuction.toJson());

        return response;
    }

    private SocketResponse handleCancelAuction(SocketRequest request) {
        String auctionId = requireAuctionId(request);
        Auction auction = auctionManager.getAuctionOrThrow(auctionId);
        auction.cancelAuction();

        return SocketResponse.ok(request, "Đã hủy phiên đấu giá [" + auctionId + "] thành công!")
                .putData("auction", JsonProtocolUtil.toAuctionDto(auction, false));
    }

    // =========================================================================
    // CÁC PHƯƠNG THỨC HỖ TRỢ NỘI BỘ & GỬI DỮ LIỆU ĐỒNG BỘ (THREAD-SAFE WRITE)
    // =========================================================================

    private String requireAuctionId(SocketRequest request) {
        String auctionId = request.getAuctionId();
        if (auctionId == null || auctionId.trim().isEmpty()) {
            auctionId = request.getPayloadString("auctionId");
        }
        if (auctionId == null || auctionId.trim().isEmpty()) {
            throw new IllegalArgumentException("Thiếu mã phiên đấu giá (auctionId)!");
        }
        return auctionId.trim();
    }

    private Bidder resolveBidder(SocketRequest request) {
        if (this.authenticatedBidder != null) {
            String requestedId = request.getUserId();
            if (requestedId != null && !requestedId.equals(this.authenticatedBidder.getId())) {
                throw new IllegalArgumentException("Tài khoản đặt giá không khớp phiên đăng nhập.");
            }
            return this.authenticatedBidder;
        }
        throw new IllegalArgumentException("Vui lòng đăng nhập tài khoản người đấu giá trước khi đặt giá.");
    }

    /**
     * Gửi một đối tượng SocketResponse dưới dạng 1 dòng JSON xuống Client một cách Thread-safe.
     */
    public boolean sendResponse(SocketResponse response) {
        if (response == null) {
            return false;
        }
        return sendJsonLine(response.toJson());
    }

    /**
     * Gửi trực tiếp 1 dòng chuỗi JSON qua luồng Output của Socket.
     * Đồng bộ hóa qua writeLock để đảm bảo các gói tin Broadcast và Response không bị trộn lẫn ký tự.
     */
    public boolean sendJsonLine(String jsonLine) {
        if (!running.get() || jsonLine == null) {
            return false;
        }
        synchronized (writeLock) {
            if (writer == null) {
                return false;
            }
            writer.println(jsonLine);
            return !writer.checkError();
        }
    }

    public String getClientId() {
        return clientId;
    }

    @Override
    public void close() {
        if (running.compareAndSet(true, false)) {
            broadcastManager.unregisterClient(this);
            try {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException ignored) {
            }
        }
    }
}

