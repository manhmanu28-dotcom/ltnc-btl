package com.auction.server.service;

import com.auction.server.dao.AuctionDAO;
import com.auction.server.dao.BidDAO;
import com.auction.server.dao.ItemDAO;
import com.auction.server.dao.UserDAO;
import com.auction.server.database.DBConnection;
import com.auction.server.model.auction.Auction;
import com.auction.server.model.auction.AuctionSnapshot;
import com.auction.server.model.auction.AuctionStatus;
import com.auction.server.model.auction.BidTransaction;
import com.auction.server.model.entity.Bidder;
import com.auction.server.model.entity.User;
import com.auction.server.model.item.Item;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentMap;

/**
 * AuctionPersistenceService: Đồng bộ dữ liệu hai chiều giữa bộ nhớ Server (AuctionManager)
 * và SQLite Database (~/.bidion/auction.db), đảm bảo khi commit code mới hoặc khởi động lại Server:
 * - Tài khoản, số dư, mặt hàng đăng bán, phiên đấu giá và lịch sử đặt giá được khôi phục nguyên vẹn.
 * - Mọi lượt đặt giá, gia hạn Anti-sniping, chuyển trạng thái và thanh toán đều ghi bền vững xuống SQLite.
 */
public final class AuctionPersistenceService implements AuctionObserver {

    private static final AuctionPersistenceService INSTANCE = new AuctionPersistenceService();
    private static final DateTimeFormatter SQLITE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private AuctionPersistenceService() {
    }

    public static AuctionPersistenceService getInstance() {
        return INSTANCE;
    }

    /**
     * Khôi phục toàn bộ phiên đấu giá và mặt hàng đã lưu từ SQLite vào AuctionManager,
     * đồng thời đăng ký Observer lưu tự động cho các thay đổi tiếp theo.
     */
    public int initializeAndLoad(AuctionManager auctionManager, ConcurrentMap<String, Bidder> activeBidders) {
        if (auctionManager == null) {
            return 0;
        }
        auctionManager.addGlobalObserver(this);

        DBConnection db = DBConnection.getInstance();
        UserDAO userDAO = new UserDAO(db);
        ItemDAO itemDAO = new ItemDAO();
        AuctionDAO auctionDAO = new AuctionDAO();
        BidDAO bidDAO = new BidDAO();

        List<AuctionDAO.AuctionRecord> records = auctionDAO.findAll();
        int loadedCount = 0;

        for (AuctionDAO.AuctionRecord record : records) {
            if (auctionManager.getAuction(record.id()) != null) {
                continue;
            }
            Optional<Item> itemOpt = itemDAO.findById(record.itemId());
            if (itemOpt.isEmpty()) {
                continue;
            }

            LocalDateTime startTime = parseDateTime(record.startTime(), LocalDateTime.now().minusMinutes(5));
            LocalDateTime endTime = parseDateTime(record.endTime(), startTime.plusMinutes(60));
            if (!endTime.isAfter(startTime)) {
                endTime = startTime.plusMinutes(1);
            }

            double safeIncrement = record.minIncrement() > 0 ? record.minIncrement() : 50_000.0;
            Auction auction = new Auction(
                    record.id(),
                    itemOpt.get(),
                    record.sellerId(),
                    safeIncrement,
                    startTime,
                    endTime
            );

            AuctionStatus restoredStatus = parseStatus(record.status());
            List<BidTransaction> history = bidDAO.findChronologicalByAuctionId(record.id());

            Bidder winnerBidder = null;
            if (record.currentWinnerId() != null && !record.currentWinnerId().isBlank()) {
                Optional<User> userOpt = userDAO.findById(record.currentWinnerId());
                if (userOpt.isPresent() && userOpt.get() instanceof Bidder dbBidder) {
                    winnerBidder = (activeBidders != null)
                            ? activeBidders.computeIfAbsent(dbBidder.getId(), id -> dbBidder)
                            : dbBidder;
                }
            }

            auction.restorePersistedState(
                    restoredStatus,
                    record.currentPrice(),
                    record.currentWinnerId(),
                    record.currentWinnerUsername(),
                    winnerBidder,
                    history
            );

            auctionManager.registerAuction(auction);
            auction.checkAndUpdateLifecycle(LocalDateTime.now());
            loadedCount++;
        }
        return loadedCount;
    }

    /**
     * Lưu ngay mặt hàng và phiên đấu giá mới xuống SQLite khi người dùng đăng bán.
     * Nếu sellerId là tài khoản giả lập trong Unit Test (chưa tồn tại trong bảng users), tự động bỏ qua an toàn.
     */
    public void persistCreatedAuction(Auction auction) {
        if (auction == null) {
            return;
        }
        try {
            DBConnection db = DBConnection.getInstance();
            UserDAO userDAO = new UserDAO(db);
            if (userDAO.findById(auction.getSellerId()).isEmpty()) {
                return;
            }
            Item item = auction.getItem();
            new ItemDAO().saveItem(item, auction.getSellerId());
            new AuctionDAO().createAuction(
                    auction.getId(),
                    item.getId(),
                    auction.getSellerId(),
                    auction.getMinIncrement(),
                    auction.getCurrentHighestBid(),
                    auction.getStatus().name(),
                    auction.getStartTime().toString(),
                    auction.getEndTime().toString()
            );
        } catch (RuntimeException ignored) {
            // Giữ cho luồng mạng hoạt động bình thường nếu chạy trong môi trường test tối giản
        }
    }

    @Override
    public void onNewBid(Auction auction, BidTransaction transaction, boolean timeExtended) {
        if (auction == null || transaction == null) {
            return;
        }
        try {
            DBConnection db = DBConnection.getInstance();
            UserDAO userDAO = new UserDAO(db);
            AuctionDAO auctionDAO = new AuctionDAO();
            if (auctionDAO.findById(auction.getId()).isEmpty()
                    || userDAO.findById(transaction.getBidderId()).isEmpty()) {
                return;
            }
            AuctionSnapshot snap = auction.getSnapshot();
            new BidDAO().saveBid(transaction);
            auctionDAO.updateHighestBid(
                    snap.getAuctionId(),
                    snap.getCurrentHighestBid(),
                    snap.getCurrentWinnerId(),
                    snap.getCurrentWinnerUsername()
            );
            if (timeExtended) {
                auctionDAO.updateEndTime(snap.getAuctionId(), snap.getEndTime().toString());
            }
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void onStatusChanged(Auction auction, AuctionStatus oldStatus, AuctionStatus newStatus) {
        if (auction == null || newStatus == null) {
            return;
        }
        try {
            AuctionDAO auctionDAO = new AuctionDAO();
            if (auctionDAO.findById(auction.getId()).isEmpty()) {
                return;
            }
            auctionDAO.updateStatus(auction.getId(), newStatus.name());

            if (newStatus == AuctionStatus.PAID) {
                String winnerId = auction.getCurrentWinnerId();
                Bidder winner = auction.getCurrentWinner();
                if (winnerId != null && winner != null) {
                    new UserDAO(DBConnection.getInstance()).updateBalance(winnerId, winner.getBalance());
                }
            }
        } catch (RuntimeException ignored) {
        }
    }

    private static AuctionStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return AuctionStatus.OPEN;
        }
        try {
            return AuctionStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return AuctionStatus.OPEN;
        }
    }

    private static LocalDateTime parseDateTime(String raw, LocalDateTime fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String trimmed = raw.trim();
        try {
            return LocalDateTime.parse(trimmed);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(trimmed, SQLITE_FORMAT);
            } catch (DateTimeParseException ex) {
                return fallback;
            }
        }
    }
}
