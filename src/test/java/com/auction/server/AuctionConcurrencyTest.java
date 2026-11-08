package com.auction.server;

import com.auction.server.exception.AuctionClosedException;
import com.auction.server.exception.InvalidBidException;
import com.auction.server.exception.InvalidStateTransitionException;
import com.auction.server.model.auction.Auction;
import com.auction.server.model.auction.AuctionStatus;
import com.auction.server.model.auction.BidTransaction;
import com.auction.server.model.entity.Admin;
import com.auction.server.model.entity.Bidder;
import com.auction.server.model.entity.Seller;
import com.auction.server.model.item.Item;
import com.auction.server.model.item.ItemFactory;
import com.auction.server.service.AuctionManager;
import com.auction.server.service.AuctionObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bộ kiểm thử toàn diện cho Core Architecture & Concurrency Engine (Thành viên 1).
 */
class AuctionConcurrencyTest {

    private AuctionManager auctionManager;
    private Item macbookItem;
    private Seller seller;
    private LocalDateTime baseStartTime;
    private LocalDateTime baseEndTime;

    @BeforeEach
    void setUp() {
        auctionManager = AuctionManager.getInstance();
        auctionManager.clearAll();

        Map<String, Object> attrs = new HashMap<>();
        attrs.put("brand", "Apple");
        attrs.put("warrantyMonths", 24);
        macbookItem = ItemFactory.createItem(
                ItemFactory.ItemType.ELECTRONICS,
                "ITEM-001",
                "MacBook Pro M3 Max",
                "Laptop cấu hình cao nguyên seal",
                10_000_000,
                attrs
        );

        seller = new Seller("SELLER-01", "apple_store", "hash123", "store@apple.com");
        baseStartTime = LocalDateTime.now().minusMinutes(5);
        baseEndTime = LocalDateTime.now().plusMinutes(30);
    }

    @AfterEach
    void tearDown() {
        auctionManager.clearAll();
    }

    @Test
    @DisplayName("1. Kiểm tra tính đa hình OOP (User, Item, Factory Method & printInfo)")
    void testOopPolymorphismAndFactory() {
        Bidder bidder = new Bidder("BIDDER-01", "nguyen_van_a", "hash", "a@vnu.edu.vn", 50_000_000);
        Admin admin = new Admin("ADMIN-01", "root_admin", "hash", "admin@vnu.edu.vn");

        assertTrue(bidder.canBid());
        assertFalse(bidder.canCreateAuction());
        assertFalse(seller.canBid());
        assertTrue(seller.canCreateAuction());
        assertFalse(admin.canBid());

        assertTrue(macbookItem.printInfo().contains("Apple"));
        assertEquals(100_000, macbookItem.calculateShippingFee(10.0));
        assertTrue(bidder.printInfo().contains("BIDDER"));
        assertTrue(seller.printInfo().contains("SELLER"));
    }

    @Test
    @DisplayName("2. Kiểm tra vòng đời trạng thái phiên (OPEN -> RUNNING -> FINISHED -> PAID)")
    void testAuctionStateMachineAndLifecycle() {
        LocalDateTime futureStart = LocalDateTime.now().plusMinutes(10);
        LocalDateTime futureEnd = futureStart.plusMinutes(60);

        Auction auction = new Auction("AUC-STATE", macbookItem, seller.getId(), 500_000, futureStart, futureEnd);
        auctionManager.registerAuction(auction);

        assertEquals(AuctionStatus.OPEN, auction.getStatus());

        // Chưa đến giờ mở phiên -> đặt giá phải bị từ chối bằng AuctionClosedException
        Bidder bidder = new Bidder("B1", "user1", "pw", "u1@mail.com", 50_000_000);
        assertThrows(AuctionClosedException.class, () -> auction.placeBid(bidder, 11_000_000));

        // Không thể chuyển thẳng từ OPEN sang PAID
        assertThrows(InvalidStateTransitionException.class, auction::markAsPaid);

        // Giả lập đồng hồ điểm tới thời gian bắt đầu -> tự động chuyển sang RUNNING
        auctionManager.tickLifecycle(futureStart.plusSeconds(1));
        assertEquals(AuctionStatus.RUNNING, auction.getStatus());

        // Đặt giá hợp lệ khi đang RUNNING
        auction.placeBid(bidder, 10_500_000, futureStart.plusMinutes(5));
        assertEquals(10_500_000, auction.getCurrentHighestBid());
        assertEquals("B1", auction.getCurrentWinnerId());

        // Giả lập đồng hồ vượt qua thời gian kết thúc -> tự động đóng phiên (FINISHED)
        auctionManager.tickLifecycle(futureEnd.plusSeconds(1));
        assertEquals(AuctionStatus.FINISHED, auction.getStatus());

        // Đặt giá sau khi phiên đã đóng -> ném AuctionClosedException
        Bidder bidder2 = new Bidder("B2", "user2", "pw", "u2@mail.com", 50_000_000);
        assertThrows(AuctionClosedException.class,
                () -> auction.placeBid(bidder2, 15_000_000, futureEnd.plusSeconds(2)));

        // Chuyển sang PAID hợp lệ
        auction.markAsPaid();
        assertEquals(AuctionStatus.PAID, auction.getStatus());
    }

    @Test
    @DisplayName("3. Kiểm tra Concurrency: 50 luồng đặt CÙNG MỘT MỨC GIÁ đồng thời -> Chỉ duy nhất 1 người thắng")
    void testConcurrentSamePriceBids_OnlyOneSucceeds() throws InterruptedException {
        Auction auction = new Auction("AUC-CONC-1", macbookItem, seller.getId(), 500_000, baseStartTime, baseEndTime);
        auction.startAuction();

        int numThreads = 50;
        double sameBidAmount = 12_000_000;

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch readyLatch = new CountDownLatch(numThreads);
        CountDownLatch startGun = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);

        for (int i = 1; i <= numThreads; i++) {
            final Bidder bidder = new Bidder("BIDDER-" + i, "user_" + i, "pw", "u" + i + "@mail.com", 100_000_000);
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startGun.await(); // Tất cả 50 luồng xuất phát cùng một thời điểm
                    auction.placeBid(bidder, sameBidAmount);
                    successCount.incrementAndGet();
                } catch (InvalidBidException e) {
                    rejectedCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startGun.countDown(); // Bắn súng xuất phát!
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "Các luồng xử lý quá thời gian chờ!");
        executor.shutdown();

        // Kiểm chứng: Đúng 1 người duy nhất đặt thành công mức giá 12.000.000, 49 người còn lại bị từ chối
        assertEquals(1, successCount.get(), "Chỉ duy nhất 1 luồng được phép thắng ở cùng một mức giá!");
        assertEquals(numThreads - 1, rejectedCount.get(), "49 luồng còn lại phải bị từ chối vì giá không còn đủ bước giá!");
        assertEquals(1, auction.getBidHistory().size());
        assertEquals(sameBidAmount, auction.getCurrentHighestBid());
        assertNotNull(auction.getCurrentWinnerId());
    }

    @Test
    @DisplayName("4. Kiểm tra Concurrency: 100 luồng đặt các mức giá cạnh tranh -> Không Lost Update, Không Rollback giá")
    void testConcurrentCompetingBids_NoLostUpdateOrPriceRollback() throws InterruptedException {
        Auction auction = new Auction("AUC-CONC-2", macbookItem, seller.getId(), 100_000, baseStartTime, baseEndTime);
        auction.startAuction();

        int numThreads = 100;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch readyLatch = new CountDownLatch(numThreads);
        CountDownLatch startGun = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);

        for (int i = 1; i <= numThreads; i++) {
            final int index = i;
            final Bidder bidder = new Bidder("B-" + index, "bidder_" + index, "pw", "b@mail.com", 500_000_000);
            // Mỗi luồng đặt một mức giá khác nhau từ 10.100.000 đến 20.000.000
            final double bidPrice = 10_000_000 + (index * 100_000L);

            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startGun.await();
                    auction.placeBid(bidder, bidPrice);
                } catch (InvalidBidException ignored) {
                    // Các luồng đến sau nhưng có mức giá thấp hơn giá hiện tại sẽ bị từ chối hợp lệ
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startGun.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        List<BidTransaction> history = auction.getBidHistory();
        assertFalse(history.isEmpty(), "Phải có ít nhất 1 giao dịch thành công!");

        // Kiểm chứng chuỗi lịch sử giá phải TĂNG NGHIÊM NGẶT (không bao giờ bị rollback hay trùng giá)
        double previousPrice = 0;
        for (BidTransaction tx : history) {
            assertTrue(tx.getAmount() > previousPrice,
                    String.format("Phát hiện lỗi Rollback/Trùng giá: Giá sau (%,.0f) không lớn hơn giá trước (%,.0f)!",
                            tx.getAmount(), previousPrice));
            previousPrice = tx.getAmount();
        }

        // Giá cao nhất hiện tại của phiên phải khớp tuyệt đối với giao dịch cuối cùng trong lịch sử (Không Lost Update)
        BidTransaction lastTx = history.get(history.size() - 1);
        assertEquals(lastTx.getAmount(), auction.getCurrentHighestBid());
        assertEquals(lastTx.getBidderId(), auction.getCurrentWinnerId());
    }

    @Test
    @DisplayName("5. Kiểm tra thuật toán Anti-sniping: Đặt giá ở 10 giây cuối tự động gia hạn thêm 60 giây")
    void testAntiSnipingAlgorithmAndObserverNotification() {
        LocalDateTime plannedEnd = LocalDateTime.of(2026, 11, 9, 20, 0, 0);
        LocalDateTime plannedStart = plannedEnd.minusHours(1);

        Auction auction = new Auction("AUC-SNIPE", macbookItem, seller.getId(), 500_000, plannedStart, plannedEnd);
        auction.setAntiSnipingConfig(30, 60); // 30 giây cuối -> cộng thêm 60 giây
        auction.startAuction();

        AtomicBoolean observerTriggeredWithExtension = new AtomicBoolean(false);
        auction.addObserver(new AuctionObserver() {
            @Override
            public void onNewBid(Auction a, BidTransaction tx, boolean timeExtended) {
                if (timeExtended) {
                    observerTriggeredWithExtension.set(true);
                }
            }

            @Override
            public void onStatusChanged(Auction a, AuctionStatus oldStatus, AuctionStatus newStatus) {
            }
        });

        Bidder sniper = new Bidder("SNIPER-01", "sniper_pro", "pw", "sniper@mail.com", 50_000_000);
        // Đặt giá lúc 19:59:50 (chỉ còn 10 giây trước 20:00:00 - đúng ví dụ mục 3.2.3 trong đề bài)
        LocalDateTime bidAtLast10Sec = LocalDateTime.of(2026, 11, 9, 19, 59, 50);
        auction.placeBid(sniper, 11_000_000, bidAtLast10Sec);

        // Kỳ vọng thời gian kết thúc mới được kéo dài đến 20:01:00
        LocalDateTime expectedNewEnd = LocalDateTime.of(2026, 11, 9, 20, 1, 0);
        assertEquals(expectedNewEnd, auction.getEndTime());
        assertTrue(observerTriggeredWithExtension.get(), "Observer phải nhận được cờ timeExtended = true!");
    }

    @Test
    @DisplayName("6. Kiểm tra Auto-Bidding (PriorityQueue): Tự động đấu giá và ưu tiên người đăng ký trước khi hòa maxBid")
    void testAutoBiddingPriorityQueueResolution() {
        Auction auction = new Auction("AUC-AUTO", macbookItem, seller.getId(), 500_000, baseStartTime, baseEndTime);
        auction.startAuction();

        Bidder bidderA = new Bidder("BA", "bidder_A", "pw", "a@mail.com", 50_000_000);
        Bidder bidderB = new Bidder("BB", "bidder_B", "pw", "b@mail.com", 50_000_000);
        Bidder bidderC = new Bidder("BC", "bidder_C", "pw", "c@mail.com", 50_000_000);

        LocalDateTime t1 = baseStartTime.plusMinutes(1);
        // Bidder A đăng ký Auto-Bid đầu tiên với maxBid = 15.000.000, bước giá 500.000
        auction.registerAutoBid(bidderA, 15_000_000, 500_000, t1);
        // Vì chưa có ai đặt giá, hệ thống tự động đặt giá khởi điểm 10.000.000 cho Bidder A
        assertEquals("BA", auction.getCurrentWinnerId());
        assertEquals(10_000_000, auction.getCurrentHighestBid());

        // Bidder B đặt giá thủ công 12.000.000 -> Auto-Bid của Bidder A lập tức tự nâng lên 12.500.000
        LocalDateTime t2 = t1.plusSeconds(10);
        auction.placeBid(bidderB, 12_000_000, t2);
        assertEquals("BA", auction.getCurrentWinnerId());
        assertEquals(12_500_000, auction.getCurrentHighestBid());

        // Bidder C đăng ký Auto-Bid sau (t3 > t1) nhưng có CÙNG maxBid = 15.000.000 như Bidder A
        // Theo mục 3.2.1: Ưu tiên theo thời điểm đăng ký auto-bid -> Bidder A phải giữ ngôi đầu ở mức 15.000.000!
        LocalDateTime t3 = t2.plusSeconds(10);
        auction.registerAutoBid(bidderC, 15_000_000, 500_000, t3);
        assertEquals("BA", auction.getCurrentWinnerId(), "Bidder A đăng ký Auto-Bid sớm hơn nên phải thắng khi hòa maxBid!");
        assertEquals(15_000_000, auction.getCurrentHighestBid());
    }

    /**
     * Cho phép chạy trực tiếp toàn bộ JUnit 5 Test Suite từ CLI khi chưa cài Maven toàn cục.
     */
    public static void main(String[] args) throws Exception {
        AuctionConcurrencyTest suite = new AuctionConcurrencyTest();
        String[] testNames = {
                "1. testOopPolymorphismAndFactory",
                "2. testAuctionStateMachineAndLifecycle",
                "3. testConcurrentSamePriceBids_OnlyOneSucceeds (50 threads)",
                "4. testConcurrentCompetingBids_NoLostUpdateOrPriceRollback (100 threads)",
                "5. testAntiSnipingAlgorithmAndObserverNotification",
                "6. testAutoBiddingPriorityQueueResolution"
        };
        Runnable[] tests = {
                suite::testOopPolymorphismAndFactory,
                suite::testAuctionStateMachineAndLifecycle,
                () -> {
                    try {
                        suite.testConcurrentSamePriceBids_OnlyOneSucceeds();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                },
                () -> {
                    try {
                        suite.testConcurrentCompetingBids_NoLostUpdateOrPriceRollback();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                },
                suite::testAntiSnipingAlgorithmAndObserverNotification,
                suite::testAutoBiddingPriorityQueueResolution
        };

        System.out.println("==================================================================");
        System.out.println(" RUNNING CORE ARCHITECTURE & CONCURRENCY ENGINE TEST SUITE (JUnit 5)");
        System.out.println("==================================================================");
        int passed = 0;
        for (int i = 0; i < tests.length; i++) {
            suite.setUp();
            long start = System.currentTimeMillis();
            try {
                tests[i].run();
                long elapsed = System.currentTimeMillis() - start;
                System.out.printf("[PASS] %s (%d ms)%n", testNames[i], elapsed);
                passed++;
            } finally {
                suite.tearDown();
            }
        }
        System.out.println("==================================================================");
        System.out.printf(" RESULT: %d/%d TESTS PASSED SUCCESSFULLY!%n", passed, tests.length);
        System.out.println("==================================================================");
    }
}
