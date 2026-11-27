package com.auction.server.model.auction;

import com.auction.server.exception.AuctionClosedException;
import com.auction.server.exception.InvalidBidException;
import com.auction.server.exception.InvalidStateTransitionException;
import com.auction.server.model.entity.BaseEntity;
import com.auction.server.model.entity.Bidder;
import com.auction.server.model.item.Item;
import com.auction.server.service.AuctionObserver;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Auction: Lớp trung tâm quản lý phiên đấu giá và xử lý đồng thời (Yêu cầu 3.1.3, 3.1.4, 3.2.1, 3.2.2, 3.2.3).
 *
 * Đặc điểm kỹ thuật cốt lõi:
 * 1. Fine-Grained Concurrency Control: Mỗi đối tượng Auction sở hữu một ReentrantLock(true) (chế độ công bằng FIFO).
 * 2. Chống tuyệt đối: Lost update, Giá bị rollback, Hai người cùng thắng một mức giá.
 * 3. Tích hợp Anti-sniping: Tự động cộng thêm thời gian nếu có bid hợp lệ trong X giây cuối.
 * 4. Tích hợp Auto-Bidding: Sử dụng PriorityQueue<AutoBidConfig> tự động trả giá thay người dùng.
 * 5. Thread-safe Observer Notify: Phát sự kiện ra bên ngoài sau khi đã nhả khóa để tránh Deadlock.
 */
public class Auction extends BaseEntity {
    public static final int DEFAULT_ANTI_SNIPING_TRIGGER_SECONDS = 30;
    public static final int DEFAULT_ANTI_SNIPING_EXTEND_SECONDS = 60;

    private final Item item;
    private final String sellerId;
    private final double minIncrement;
    private final LocalDateTime startTime;
    private LocalDateTime endTime;

    private double currentHighestBid;
    private String currentWinnerId;
    private String currentWinnerUsername;
    private transient Bidder currentWinner;
    private double currentHeldAmount;
    private AuctionStatus status;

    private int antiSnipingTriggerSeconds;
    private int antiSnipingExtendSeconds;
    private boolean allowSelfOutbid;

    private final List<BidTransaction> bidHistory;
    private final PriorityQueue<AutoBidConfig> autoBidQueue;
    private final AtomicLong autoBidSequenceGenerator;

    // Khóa đồng thời riêng biệt cho từng phiên với cơ chế Fair FIFO
    private final transient ReentrantLock lock;
    private final transient List<AuctionObserver> observers;

    public Auction(String id, Item item, String sellerId, double minIncrement,
                   LocalDateTime startTime, LocalDateTime endTime) {
        super(id);
        if (item == null) {
            throw new IllegalArgumentException("Sản phẩm đấu giá (Item) không được null!");
        }
        if (sellerId == null || sellerId.trim().isEmpty()) {
            throw new IllegalArgumentException("Seller ID không được để trống!");
        }
        if (!Double.isFinite(minIncrement) || minIncrement <= 0) {
            throw new IllegalArgumentException("Bước giá tối thiểu (minIncrement) phải là số hữu hạn lớn hơn 0!");
        }
        if (startTime == null || endTime == null || !endTime.isAfter(startTime)) {
            throw new IllegalArgumentException("Thời gian kết thúc phải sau thời gian bắt đầu!");
        }

        this.item = item;
        this.sellerId = sellerId.trim();
        this.minIncrement = minIncrement;
        this.startTime = startTime;
        this.endTime = endTime;

        this.currentHighestBid = item.getStartingPrice();
        this.currentWinnerId = null;
        this.currentWinnerUsername = null;
        this.currentWinner = null;
        this.currentHeldAmount = 0.0;
        this.status = AuctionStatus.OPEN;

        this.antiSnipingTriggerSeconds = DEFAULT_ANTI_SNIPING_TRIGGER_SECONDS;
        this.antiSnipingExtendSeconds = DEFAULT_ANTI_SNIPING_EXTEND_SECONDS;
        this.allowSelfOutbid = false;

        this.bidHistory = new ArrayList<>();
        this.autoBidQueue = new PriorityQueue<>();
        this.autoBidSequenceGenerator = new AtomicLong(0);

        this.lock = new ReentrantLock(true);
        this.observers = new CopyOnWriteArrayList<>();
    }

    // =========================================================================
    // 1. QUẢN LÝ VÒNG ĐỜI PHIÊN (STATE MACHINE: OPEN -> RUNNING -> FINISHED -> PAID / CANCELED)
    // =========================================================================

    /**
     * Kích hoạt phiên đấu giá từ OPEN sang RUNNING.
     */
    public void startAuction() {
        transitionStatus(AuctionStatus.RUNNING);
    }

    /**
     * Kết thúc phiên đấu giá từ RUNNING sang FINISHED và chốt người chiến thắng.
     */
    public void closeAuction() {
        transitionStatus(AuctionStatus.FINISHED);
    }

    /**
     * Xác nhận người thắng cuộc đã thanh toán (FINISHED -> PAID).
     */
    public void markAsPaid() {
        transitionStatus(AuctionStatus.PAID);
    }

    /**
     * Hủy phiên đấu giá (chuyển sang CANCELED).
     */
    public void cancelAuction() {
        transitionStatus(AuctionStatus.CANCELED);
    }

    private void transitionStatus(AuctionStatus targetStatus) {
        AuctionStatus oldStatus;
        AuctionSnapshot snapshot;
        lock.lock();
        try {
            if (!this.status.canTransitionTo(targetStatus)) {
                throw new InvalidStateTransitionException(String.format(
                        "Không thể chuyển trạng thái phiên [%s] từ %s sang %s!",
                        getId(), this.status, targetStatus));
            }
            if (targetStatus == AuctionStatus.PAID) {
                if (this.currentWinnerId == null) {
                    throw new InvalidStateTransitionException(String.format(
                            "Không thể xác nhận thanh toán phiên [%s] vì chưa có người thắng!",
                            getId()));
                }
                settleCurrentWinnerHoldInternal();
            } else if (targetStatus == AuctionStatus.CANCELED) {
                releaseCurrentWinnerHoldInternal();
                this.autoBidQueue.clear();
            }
            oldStatus = this.status;
            this.status = targetStatus;
            snapshot = createSnapshotInternal();
        } finally {
            lock.unlock();
        }
        notifyStatusChanged(snapshot, oldStatus, targetStatus);
    }

    /**
     * Kiểm tra và tự động cập nhật trạng thái theo đồng hồ thời gian thực.
     * Được gọi định kỳ bởi AuctionManager Scheduler hoặc trước khi xử lý thao tác.
     */
    public void checkAndUpdateLifecycle(LocalDateTime now) {
        List<StatusTransitionRecord> transitions = new ArrayList<>(2);

        lock.lock();
        try {
            LocalDateTime currentTime = (now != null) ? now : LocalDateTime.now();
            if (this.status == AuctionStatus.OPEN && !currentTime.isBefore(this.startTime)) {
                AuctionStatus oldStatus = this.status;
                this.status = AuctionStatus.RUNNING;
                transitions.add(new StatusTransitionRecord(oldStatus, this.status, createSnapshotInternal()));
            }
            if (this.status == AuctionStatus.RUNNING && currentTime.isAfter(this.endTime)) {
                AuctionStatus oldStatus = this.status;
                this.status = AuctionStatus.FINISHED;
                transitions.add(new StatusTransitionRecord(oldStatus, this.status, createSnapshotInternal()));
            }
        } finally {
            lock.unlock();
        }

        for (StatusTransitionRecord transition : transitions) {
            notifyStatusChanged(transition.snapshot, transition.oldStatus, transition.newStatus);
        }
    }

    // =========================================================================
    // 2. XỬ LÝ ĐẤU GIÁ ĐỒNG THỜI & ANTI-SNIPING (CONCURRENT BIDDING ENGINE)
    // =========================================================================

    /**
     * Đặt giá tại thời điểm hiện tại.
     */
    public BidTransaction placeBid(Bidder bidder, double amount) {
        return placeBid(bidder, amount, LocalDateTime.now());
    }

    /**
     * Đặt giá vào phiên đấu giá một cách an toàn đa luồng (Thread-safe).
     * Ngăn chặn hoàn toàn:
     * - Lost update (mọi luồng đều đi qua ReentrantLock tuần tự)
     * - Giá bị rollback (giá mới bắt buộc >= currentHighestBid + minIncrement)
     * - Hai người cùng thắng (mỗi mức giá hợp lệ chỉ được 1 luồng đầu tiên chiếm giữ)
     *
     * @param bidder  Người đặt giá
     * @param amount  Số tiền đặt giá
     * @param bidTime Thời điểm đặt giá
     * @return Giao dịch đặt giá của người dùng vừa thực hiện
     */
    public BidTransaction placeBid(Bidder bidder, double amount, LocalDateTime bidTime) {
        List<BidEventRecord> generatedEvents = new ArrayList<>();
        AntiSnipingContext antiSnipingContext = new AntiSnipingContext();
        AuctionSnapshot autoClosedSnapshot = null;
        BidTransaction manualTransaction;

        lock.lock();
        try {
            LocalDateTime effectiveTime = (bidTime != null) ? bidTime : LocalDateTime.now();

            // 1. Kiểm tra trạng thái phiên và thời hạn phiên
            if (this.status == AuctionStatus.RUNNING && effectiveTime.isAfter(this.endTime)) {
                this.status = AuctionStatus.FINISHED;
                autoClosedSnapshot = createSnapshotInternal();
            }
            if (this.status != AuctionStatus.RUNNING) {
                throw new AuctionClosedException(String.format(
                        "Phiên đấu giá [%s] không ở trạng thái nhận giá (Trạng thái hiện tại: %s).",
                        getId(), this.status));
            }

            // 2. Chặn giá trị không hữu hạn (NaN / Infinity) và kiểm tra quyền Bidder
            if (!Double.isFinite(amount) || amount <= 0) {
                throw new InvalidBidException("Số tiền đặt giá phải là số hữu hạn lớn hơn 0!");
            }
            validateBidder(bidder, amount);

            // 3. Kiểm tra người dẫn đầu tự đặt đè lên chính mình
            if (!this.allowSelfOutbid && bidder.getId().equals(this.currentWinnerId)) {
                throw new InvalidBidException(
                        "Bạn đang là người dẫn đầu phiên đấu giá này!");
            }

            // 4. Kiểm tra mức giá tối thiểu hợp lệ (chống Rollback & chống trùng giá)
            double minimumAllowedBid = getMinimumNextBidInternal();
            if (amount < minimumAllowedBid) {
                throw new InvalidBidException(String.format(
                        "Giá đặt (%,.0f VNĐ) không hợp lệ! Giá tối thiểu cần đặt lúc này là %,.0f VNĐ.",
                        amount, minimumAllowedBid));
            }

            // 5. Giữ tiền khả dụng + ghi nhận giao dịch nguyên tử & kiểm tra Anti-sniping
            manualTransaction = recordBidInternal(
                    bidder, amount, effectiveTime, false, generatedEvents, antiSnipingContext);

            // 6. Kích hoạt hệ thống Auto-Bidding (nếu có đối thủ đã đăng ký Auto-Bid)
            resolveAutoBidsInternal(effectiveTime, generatedEvents, antiSnipingContext);

        } finally {
            lock.unlock();
        }

        // Phát thông báo Observer bên ngoài vùng khóa để tránh Deadlock I/O
        if (autoClosedSnapshot != null) {
            notifyStatusChanged(autoClosedSnapshot, AuctionStatus.RUNNING, AuctionStatus.FINISHED);
        }
        for (BidEventRecord event : generatedEvents) {
            notifyNewBid(event.snapshot, event.transaction, event.timeExtended);
        }

        return manualTransaction;
    }

    // =========================================================================
    // 3. ĐẤU GIÁ TỰ ĐỘNG (AUTO-BIDDING ENGINE VỚI PRIORITY QUEUE - MỤC 3.2.1)
    // =========================================================================

    /**
     * Đăng ký hoặc cập nhật cấu hình Auto-Bid cho người dùng.
     */
    public AutoBidConfig registerAutoBid(Bidder bidder, double maxBid, double increment) {
        return registerAutoBid(bidder, maxBid, increment, LocalDateTime.now());
    }

    /**
     * Đăng ký Auto-Bid an toàn luồng và tự động so khớp ngay lập tức nếu người dùng chưa dẫn đầu.
     */
    public AutoBidConfig registerAutoBid(Bidder bidder, double maxBid, double increment, LocalDateTime registeredAt) {
        List<BidEventRecord> generatedEvents = new ArrayList<>();
        AntiSnipingContext antiSnipingContext = new AntiSnipingContext();
        AuctionSnapshot autoClosedSnapshot = null;
        AutoBidConfig config;

        lock.lock();
        try {
            LocalDateTime effectiveTime = (registeredAt != null) ? registeredAt : LocalDateTime.now();

            if (this.status == AuctionStatus.RUNNING && effectiveTime.isAfter(this.endTime)) {
                this.status = AuctionStatus.FINISHED;
                autoClosedSnapshot = createSnapshotInternal();
            }
            if (this.status != AuctionStatus.RUNNING) {
                throw new AuctionClosedException(String.format(
                        "Không thể đăng ký Auto-Bid vì phiên [%s] đang ở trạng thái %s.",
                        getId(), this.status));
            }

            if (!Double.isFinite(maxBid) || maxBid <= 0) {
                throw new InvalidBidException("Giá tối đa Auto-Bid phải là số hữu hạn lớn hơn 0!");
            }
            if (!Double.isFinite(increment) || increment <= 0) {
                throw new InvalidBidException("Bước giá Auto-Bid phải là số hữu hạn lớn hơn 0!");
            }

            validateBidder(bidder, maxBid);
            if (increment < this.minIncrement) {
                throw new InvalidBidException(String.format(
                        "Bước giá Auto-Bid (%,.0f VNĐ) không được nhỏ hơn bước giá tối thiểu của phiên (%,.0f VNĐ)!",
                        increment, this.minIncrement));
            }

            double minimumAllowedBid = getMinimumNextBidInternal();
            if (!bidder.getId().equals(this.currentWinnerId) && maxBid < minimumAllowedBid) {
                throw new InvalidBidException(String.format(
                        "Giá tối đa Auto-Bid (%,.0f VNĐ) phải tối thiểu từ %,.0f VNĐ trở lên!",
                        maxBid, minimumAllowedBid));
            }

            // Xóa cấu hình cũ của cùng Bidder (nếu có) và thêm cấu hình mới vào PriorityQueue
            this.autoBidQueue.removeIf(existing -> existing.getBidderId().equals(bidder.getId()));
            config = new AutoBidConfig(
                    bidder,
                    maxBid,
                    increment,
                    effectiveTime,
                    this.autoBidSequenceGenerator.incrementAndGet()
            );
            this.autoBidQueue.offer(config);

            // Chạy bộ giải quyết tự động so khớp giữa các Auto-Bid và giá hiện tại
            resolveAutoBidsInternal(effectiveTime, generatedEvents, antiSnipingContext);

        } finally {
            lock.unlock();
        }

        if (autoClosedSnapshot != null) {
            notifyStatusChanged(autoClosedSnapshot, AuctionStatus.RUNNING, AuctionStatus.FINISHED);
        }
        for (BidEventRecord event : generatedEvents) {
            notifyNewBid(event.snapshot, event.transaction, event.timeExtended);
        }

        return config;
    }

    /**
     * Thuật toán giải quyết cạnh tranh giữa các Auto-Bid trong PriorityQueue:
     * - So sánh nhiều Auto-Bid cùng lúc.
     * - Không bao giờ vượt quá maxBid của từng người.
     * - Ưu tiên người đăng ký Auto-Bid sớm hơn nếu hai người có cùng mức maxBid.
     */
    private void resolveAutoBidsInternal(LocalDateTime eventTime, List<BidEventRecord> generatedEvents,
                                         AntiSnipingContext antiSnipingContext) {
        while (true) {
            // Loại bỏ các Auto-Bid không còn đủ số dư khả dụng hoặc đã tụt lại phía sau giá hiện tại
            this.autoBidQueue.removeIf(cfg -> cfg.getMaxBid() < this.currentHighestBid
                    || effectiveAvailableBalanceInternal(cfg.getBidder()) < cfg.getMaxBid());

            if (this.autoBidQueue.isEmpty()) {
                return;
            }

            List<AutoBidConfig> sortedConfigs = new ArrayList<>(this.autoBidQueue);
            Collections.sort(sortedConfigs);

            AutoBidConfig leader = sortedConfigs.get(0);
            AutoBidConfig runnerUp = sortedConfigs.size() > 1 ? sortedConfigs.get(1) : null;

            double requiredMin = getMinimumNextBidInternal();
            double step = Math.max(this.minIncrement, leader.getIncrement());
            double targetAmount;

            if (runnerUp == null) {
                if (leader.getBidderId().equals(this.currentWinnerId)) {
                    return;
                }
                targetAmount = this.currentWinnerId == null
                        ? this.item.getStartingPrice()
                        : Math.min(leader.getMaxBid(), this.currentHighestBid + step);
            } else {
                targetAmount = runnerUp.getMaxBid() + step;
                if (!leader.getBidderId().equals(this.currentWinnerId)) {
                    targetAmount = Math.max(targetAmount, this.currentHighestBid + step);
                }
                targetAmount = Math.min(targetAmount, leader.getMaxBid());
            }

            if ((targetAmount >= requiredMin && targetAmount > this.currentHighestBid)
                    || (this.currentWinnerId == null && targetAmount >= this.item.getStartingPrice())) {
                try {
                    recordBidInternal(leader.getBidder(), targetAmount, eventTime, true,
                            generatedEvents, antiSnipingContext);
                } catch (InvalidBidException insufficientFunds) {
                    // Bidder không còn đủ số dư khả dụng (do đã thắng ở phiên khác) -> loại và xét người tiếp theo
                    this.autoBidQueue.removeIf(cfg -> cfg.getBidderId().equals(leader.getBidderId()));
                    continue;
                }
            }
            break;
        }

        // Giữ cấu hình người đang dẫn đầu để họ có thể phản ứng với đối thủ mới.
        double nextMinAfterResolution = getMinimumNextBidInternal();
        this.autoBidQueue.removeIf(cfg -> !cfg.getBidderId().equals(this.currentWinnerId)
                && cfg.getMaxBid() < nextMinAfterResolution);
    }

    // =========================================================================
    // 4. CÁC PHƯƠNG THỨC NỘI BỘ (INTERNAL HELPERS CHẠY TRONG VÙNG KHÓA)
    // =========================================================================

    private void validateBidder(Bidder bidder, double requiredAmount) {
        if (bidder == null) {
            throw new InvalidBidException("Thông tin người đấu giá (Bidder) không được null!");
        }
        if (!bidder.canBid()) {
            throw new InvalidBidException("Tài khoản của bạn không có quyền tham gia đặt giá!");
        }
        if (bidder.getId().equals(this.sellerId)) {
            throw new InvalidBidException("Người bán không được tự đặt giá vào phiên của chính mình!");
        }
        double effectiveAvailable = effectiveAvailableBalanceInternal(bidder);
        if (effectiveAvailable + 1e-6 < requiredAmount) {
            throw new InvalidBidException(String.format(
                    "Số dư khả dụng (%,.0f VNĐ) không đủ để đảm bảo mức giá %,.0f VNĐ!",
                    effectiveAvailable, requiredAmount));
        }
    }

    private double effectiveAvailableBalanceInternal(Bidder bidder) {
        double available = bidder.getAvailableBalance();
        if (this.currentWinner == bidder) {
            return available + this.currentHeldAmount;
        }
        return available;
    }

    private void holdBidFundsInternal(Bidder bidder, double amount) {
        if (this.currentWinner == bidder) {
            double delta = amount - this.currentHeldAmount;
            if (delta > 0.0 && !bidder.tryHold(delta)) {
                throw new InvalidBidException(String.format(
                        "Số dư khả dụng (%,.0f VNĐ) không đủ để nâng giá thêm %,.0f VNĐ!",
                        bidder.getAvailableBalance(), delta));
            }
            this.currentHeldAmount = amount;
            return;
        }

        if (!bidder.tryHold(amount)) {
            throw new InvalidBidException(String.format(
                    "Số dư khả dụng (%,.0f VNĐ) không đủ để đảm bảo mức giá %,.0f VNĐ!",
                    bidder.getAvailableBalance(), amount));
        }
        releaseCurrentWinnerHoldInternal();
        this.currentWinner = bidder;
        this.currentHeldAmount = amount;
    }

    private void releaseCurrentWinnerHoldInternal() {
        if (this.currentWinner != null && this.currentHeldAmount > 0.0) {
            this.currentWinner.releaseHold(this.currentHeldAmount);
        }
        this.currentHeldAmount = 0.0;
    }

    private void settleCurrentWinnerHoldInternal() {
        if (this.currentWinner != null && this.currentHeldAmount > 0.0) {
            this.currentWinner.settleHold(this.currentHeldAmount);
        }
        this.currentHeldAmount = 0.0;
    }

    private double getMinimumNextBidInternal() {
        if (this.currentWinnerId == null && this.bidHistory.isEmpty()) {
            return this.item.getStartingPrice();
        }
        return this.currentHighestBid + this.minIncrement;
    }

    private BidTransaction recordBidInternal(Bidder bidder, double amount, LocalDateTime bidTime,
                                             boolean isAutoBid, List<BidEventRecord> generatedEvents,
                                             AntiSnipingContext antiSnipingContext) {
        // Giữ tiền khả dụng trước khi thay đổi trạng thái phiên để đảm bảo tính nguyên tử
        holdBidFundsInternal(bidder, amount);

        this.currentHighestBid = amount;
        this.currentWinnerId = bidder.getId();
        this.currentWinnerUsername = bidder.getUsername();

        // Gia hạn tối đa một lần cho mỗi thao tác đặt giá. Một thao tác có thể
        // sinh nhiều auto-bid cùng timestamp; không cộng Y giây lặp lại cho từng giao dịch.
        boolean timeExtended = false;
        if (!antiSnipingContext.extended
                && this.antiSnipingTriggerSeconds > 0 && this.antiSnipingExtendSeconds > 0) {
            Duration remaining = Duration.between(bidTime, this.endTime);
            if (!remaining.isNegative()
                    && remaining.compareTo(Duration.ofSeconds(this.antiSnipingTriggerSeconds)) <= 0) {
                this.endTime = this.endTime.plusSeconds(this.antiSnipingExtendSeconds);
                antiSnipingContext.extended = true;
                timeExtended = true;
            }
        }

        String txId = "BID-" + UUID.randomUUID().toString().toUpperCase();
        BidTransaction transaction = new BidTransaction(
                txId,
                getId(),
                bidder.getId(),
                bidder.getUsername(),
                amount,
                bidTime,
                isAutoBid
        );
        this.bidHistory.add(transaction);
        generatedEvents.add(new BidEventRecord(transaction, timeExtended, createSnapshotInternal()));
        return transaction;
    }

    private AuctionSnapshot createSnapshotInternal() {
        return new AuctionSnapshot(
                getId(),
                this.item.getTitle(),
                this.status,
                this.currentHighestBid,
                getMinimumNextBidInternal(),
                this.currentWinnerId,
                this.currentWinnerUsername,
                this.endTime,
                this.bidHistory.size()
        );
    }

    private static final class BidEventRecord {
        private final BidTransaction transaction;
        private final boolean timeExtended;
        private final AuctionSnapshot snapshot;

        private BidEventRecord(BidTransaction transaction, boolean timeExtended, AuctionSnapshot snapshot) {
            this.transaction = transaction;
            this.timeExtended = timeExtended;
            this.snapshot = snapshot;
        }
    }

    private static final class StatusTransitionRecord {
        private final AuctionStatus oldStatus;
        private final AuctionStatus newStatus;
        private final AuctionSnapshot snapshot;

        private StatusTransitionRecord(AuctionStatus oldStatus, AuctionStatus newStatus, AuctionSnapshot snapshot) {
            this.oldStatus = oldStatus;
            this.newStatus = newStatus;
            this.snapshot = snapshot;
        }
    }

    /** Theo dõi trạng thái gia hạn trong một thao tác bid và các auto-bid phát sinh từ nó. */
    private static final class AntiSnipingContext {
        private boolean extended;
    }

    // =========================================================================
    // 5. QUẢN LÝ OBSERVER & TRUY VẤN THREAD-SAFE (GETTERS / SETTERS)
    // =========================================================================

    public void addObserver(AuctionObserver observer) {
        if (observer != null && !this.observers.contains(observer)) {
            this.observers.add(observer);
        }
    }

    public void removeObserver(AuctionObserver observer) {
        this.observers.remove(observer);
    }

    private void notifyNewBid(AuctionSnapshot snapshot, BidTransaction transaction, boolean timeExtended) {
        for (AuctionObserver observer : this.observers) {
            observer.onNewBid(snapshot, transaction, timeExtended);
            observer.onNewBid(this, transaction, timeExtended);
        }
    }

    private void notifyStatusChanged(AuctionSnapshot snapshot, AuctionStatus oldStatus, AuctionStatus newStatus) {
        for (AuctionObserver observer : this.observers) {
            observer.onStatusChanged(snapshot, oldStatus, newStatus);
            observer.onStatusChanged(this, oldStatus, newStatus);
        }
    }

    /**
     * Trả về bản chụp trạng thái nhất quán (Snapshot) dưới một lần khóa duy nhất.
     */
    public AuctionSnapshot getSnapshot() {
        lock.lock();
        try {
            return createSnapshotInternal();
        } finally {
            lock.unlock();
        }
    }

    public double getMinimumNextBid() {
        lock.lock();
        try {
            return getMinimumNextBidInternal();
        } finally {
            lock.unlock();
        }
    }

    public Item getItem() {
        return item;
    }

    public String getSellerId() {
        return sellerId;
    }

    public double getMinIncrement() {
        return minIncrement;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public LocalDateTime getEndTime() {
        lock.lock();
        try {
            return endTime;
        } finally {
            lock.unlock();
        }
    }

    public double getCurrentHighestBid() {
        lock.lock();
        try {
            return currentHighestBid;
        } finally {
            lock.unlock();
        }
    }

    public String getCurrentWinnerId() {
        lock.lock();
        try {
            return currentWinnerId;
        } finally {
            lock.unlock();
        }
    }

    public String getCurrentWinnerUsername() {
        lock.lock();
        try {
            return currentWinnerUsername;
        } finally {
            lock.unlock();
        }
    }

    public AuctionStatus getStatus() {
        lock.lock();
        try {
            return status;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Trả về bản sao bất biến (Defensive Copy) của lịch sử đặt giá để tránh ConcurrentModificationException.
     */
    public List<BidTransaction> getBidHistory() {
        lock.lock();
        try {
            return Collections.unmodifiableList(new ArrayList<>(bidHistory));
        } finally {
            lock.unlock();
        }
    }

    public int getAntiSnipingTriggerSeconds() {
        lock.lock();
        try {
            return antiSnipingTriggerSeconds;
        } finally {
            lock.unlock();
        }
    }

    public void setAntiSnipingConfig(int triggerSeconds, int extendSeconds) {
        lock.lock();
        try {
            this.antiSnipingTriggerSeconds = Math.max(0, triggerSeconds);
            this.antiSnipingExtendSeconds = Math.max(0, extendSeconds);
        } finally {
            lock.unlock();
        }
    }

    public boolean isAllowSelfOutbid() {
        lock.lock();
        try {
            return allowSelfOutbid;
        } finally {
            lock.unlock();
        }
    }

    public void setAllowSelfOutbid(boolean allowSelfOutbid) {
        lock.lock();
        try {
            this.allowSelfOutbid = allowSelfOutbid;
        } finally {
            lock.unlock();
        }
    }

    public Bidder getCurrentWinner() {
        lock.lock();
        try {
            return currentWinner;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Khôi phục trạng thái phiên và lịch sử đặt giá từ SQLite khi khởi động lại Server.
     */
    public void restorePersistedState(AuctionStatus restoredStatus,
                                      double restoredHighestBid,
                                      String restoredWinnerId,
                                      String restoredWinnerUsername,
                                      Bidder restoredWinner,
                                      List<BidTransaction> chronologicalBids) {
        lock.lock();
        try {
            this.status = (restoredStatus != null) ? restoredStatus : AuctionStatus.OPEN;
            if (Double.isFinite(restoredHighestBid) && restoredHighestBid >= this.item.getStartingPrice()) {
                this.currentHighestBid = restoredHighestBid;
            }
            this.currentWinnerId = (restoredWinnerId != null && !restoredWinnerId.isBlank())
                    ? restoredWinnerId.trim() : null;
            this.currentWinnerUsername = (restoredWinnerUsername != null && !restoredWinnerUsername.isBlank())
                    ? restoredWinnerUsername.trim() : null;

            releaseCurrentWinnerHoldInternal();
            this.currentWinner = restoredWinner;
            if (restoredWinner != null && this.currentWinnerId != null
                    && (this.status == AuctionStatus.RUNNING || this.status == AuctionStatus.FINISHED)
                    && this.currentHighestBid > 0) {
                if (restoredWinner.tryHold(this.currentHighestBid)) {
                    this.currentHeldAmount = this.currentHighestBid;
                }
            }

            this.bidHistory.clear();
            if (chronologicalBids != null) {
                this.bidHistory.addAll(chronologicalBids);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String printInfo() {
        lock.lock();
        try {
            return String.format(
                    "Auction[ID=%s, Sản phẩm='%s', Trạng thái=%s, Giá hiện tại=%,.0f VNĐ, Người dẫn đầu=%s, Số lượt bid=%d, Kết thúc=%s]",
                    getId(),
                    item.getTitle(),
                    status,
                    currentHighestBid,
                    (currentWinnerUsername != null ? currentWinnerUsername : "Chưa có"),
                    bidHistory.size(),
                    endTime
            );
        } finally {
            lock.unlock();
        }
    }
}
