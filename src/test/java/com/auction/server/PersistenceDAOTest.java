package com.auction.server;

import com.auction.server.dao.AuctionDAO;
import com.auction.server.dao.BidDAO;
import com.auction.server.dao.ItemDAO;
import com.auction.server.dao.UserDAO;
import com.auction.server.database.DBConnection;
import com.auction.server.model.auction.Auction;
import com.auction.server.model.auction.AuctionStatus;
import com.auction.server.model.auction.BidTransaction;
import com.auction.server.model.entity.Bidder;
import com.auction.server.model.item.ArtItem;
import com.auction.server.model.item.ElectronicsItem;
import com.auction.server.model.item.Item;
import com.auction.server.model.item.ItemFactory;
import com.auction.server.model.item.VehicleItem;
import com.auction.server.service.AuctionManager;
import com.auction.server.service.AuctionPersistenceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

public class PersistenceDAOTest {

    private ItemDAO itemDAO;
    private AuctionDAO auctionDAO;
    private BidDAO bidDAO;

    @BeforeEach
    void setUp() throws Exception {
        DBConnection.resetInstance();
        DBConnection db = DBConnection.getInstance("jdbc:sqlite::memory:");
        Connection conn = db.getConnection();

        // Tạo dữ liệu user mẫu để thỏa mãn khóa ngoại (FOREIGN KEY)
        insertTestUser(conn, "seller-1", "seller_one", "SELLER");
        insertTestUser(conn, "bidder-1", "bidder_one", "BIDDER");
        insertTestUser(conn, "bidder-2", "bidder_two", "BIDDER");

        itemDAO = new ItemDAO();
        auctionDAO = new AuctionDAO();
        bidDAO = new BidDAO();
    }

    @AfterEach
    void tearDown() {
        AuctionManager.getInstance().clearAll();
        DBConnection.resetInstance();
    }

    private void insertTestUser(Connection conn, String id, String username, String role) throws Exception {
        String sql = "INSERT INTO users (id, username, password_hash, role, balance) VALUES (?, ?, 'hash123', ?, 10000000)";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, id);
            pstmt.setString(2, username);
            pstmt.setString(3, role);
            pstmt.executeUpdate();
        }
    }

    @Test
    void testItemDAOCrudAndPolymorphism() {
        Item laptop = ItemFactory.createItem(
                ItemFactory.ItemType.ELECTRONICS, "item-1", "MacBook Pro", "M3 Pro", 35000000,
                Map.of("brand", "Apple", "warrantyMonths", 12));
        Item painting = ItemFactory.createItem(
                ItemFactory.ItemType.ART, "item-2", "Tranh Sơn Dầu", "Bản gốc", 15000000,
                Map.of("artistName", "Nguyễn A", "hasCertificate", true));
        Item car = ItemFactory.createItem(
                ItemFactory.ItemType.VEHICLE, "item-3", "Civic RS", "Xe lướt", 700000000,
                Map.of("mileage", 5000, "manufactureYear", 2024));

        assertTrue(itemDAO.saveItem(laptop, "seller-1"));
        assertTrue(itemDAO.saveItem(painting, "seller-1"));
        assertTrue(itemDAO.saveItem(car, "seller-1"));

        Optional<Item> foundLaptop = itemDAO.findById("item-1");
        assertTrue(foundLaptop.isPresent());
        assertInstanceOf(ElectronicsItem.class, foundLaptop.get());
        assertEquals("MacBook Pro", foundLaptop.get().getTitle());

        assertInstanceOf(ArtItem.class, itemDAO.findById("item-2").orElseThrow());
        assertInstanceOf(VehicleItem.class, itemDAO.findById("item-3").orElseThrow());

        assertEquals(3, itemDAO.findBySellerId("seller-1").size());
        assertEquals(3, itemDAO.findAll().size());

        assertTrue(itemDAO.deleteById("item-3"));
        assertTrue(itemDAO.findById("item-3").isEmpty());
        assertEquals(2, itemDAO.findAll().size());
    }

    @Test
    void testAuctionDAOCrudAndUpdates() {
        Item item = ItemFactory.createItem(
                ItemFactory.ItemType.ELECTRONICS, "item-10", "iPhone 16", "New", 20000000, Map.of());
        assertTrue(itemDAO.saveItem(item, "seller-1"));

        String startTime = "2026-11-23T20:00:00";
        String endTime = "2026-11-23T21:00:00";
        assertTrue(auctionDAO.createAuction(
                "auc-1", "item-10", "seller-1", 500000, 20000000, "OPEN", startTime, endTime));

        AuctionDAO.AuctionRecord record = auctionDAO.findById("auc-1").orElseThrow();
        assertEquals("OPEN", record.status());
        assertEquals(20000000, record.currentPrice());
        assertEquals(1, auctionDAO.findByStatus("OPEN").size());

        // Cập nhật trạng thái, giá cao nhất và gia hạn Anti-Sniping
        assertTrue(auctionDAO.updateStatus("auc-1", "RUNNING"));
        assertTrue(auctionDAO.updateHighestBid("auc-1", 21000000, "bidder-1", "bidder_one"));
        assertTrue(auctionDAO.updateEndTime("auc-1", "2026-11-23T21:01:00"));

        AuctionDAO.AuctionRecord updated = auctionDAO.findById("auc-1").orElseThrow();
        assertEquals("RUNNING", updated.status());
        assertEquals(21000000, updated.currentPrice());
        assertEquals("bidder-1", updated.currentWinnerId());
        assertEquals("bidder_one", updated.currentWinnerUsername());
        assertEquals("2026-11-23T21:01:00", updated.endTime());

        assertTrue(auctionDAO.deleteById("auc-1"));
        assertTrue(auctionDAO.findById("auc-1").isEmpty());
    }

    @Test
    void testBidDAOCrudAndHighestBid() {
        Item item = ItemFactory.createItem(
                ItemFactory.ItemType.ART, "item-20", "Tượng Đồng", "Cổ", 5000000, Map.of());
        assertTrue(itemDAO.saveItem(item, "seller-1"));
        assertTrue(auctionDAO.createAuction(
                "auc-2", "item-20", "seller-1", 200000, 5000000, "RUNNING",
                "2026-11-23T20:00:00", "2026-11-23T22:00:00"));

        BidTransaction bid1 = new BidTransaction(
                "bid-1", "auc-2", "bidder-1", "bidder_one", 5200000, LocalDateTime.now());
        BidTransaction bid2 = new BidTransaction(
                "bid-2", "auc-2", "bidder-2", "bidder_two", 6000000, LocalDateTime.now().plusSeconds(10));

        assertTrue(bidDAO.saveBid(bid1));
        assertTrue(bidDAO.saveBid(bid2));

        assertEquals(2, bidDAO.countByAuctionId("auc-2"));

        List<BidTransaction> history = bidDAO.findByAuctionId("auc-2");
        assertEquals(2, history.size());
        assertEquals(6000000, history.get(0).getAmount()); // Giá cao nhất xếp đầu

        BidTransaction highest = bidDAO.findHighestBid("auc-2").orElseThrow();
        assertEquals("bid-2", highest.getId());
        assertEquals("bidder_two", highest.getBidderUsername());

        assertEquals(1, bidDAO.findByBidderId("bidder-1").size());
    }

    @Test
    void testUserSaveDoesNotCascadeDeleteItemsAuctionsAndBids() {
        UserDAO userDAO = new UserDAO();
        Item item = ItemFactory.createItem(
                ItemFactory.ItemType.ART, "item-keep", "AWP Dragon Lore", "FN", 50_000_000, Map.of());
        assertTrue(itemDAO.saveItem(item, "seller-1"));
        assertTrue(auctionDAO.createAuction(
                "auc-keep", "item-keep", "seller-1", 1_000_000, 50_000_000, "RUNNING",
                "2026-11-27T08:00:00", "2026-11-27T20:00:00"));
        assertTrue(bidDAO.saveBid(new BidTransaction(
                "bid-keep", "auc-keep", "bidder-1", "bidder_one", 52_000_000, LocalDateTime.now())));

        // Cập nhật lại thông tin người dùng qua UserDAO.save() không được làm mất phiên hay lượt bid
        Bidder updatedBidder = new Bidder("bidder-1", "bidder_one", "newHash", "b1@example.com", 95_000_000);
        assertTrue(userDAO.save(updatedBidder));

        assertTrue(itemDAO.findById("item-keep").isPresent());
        assertTrue(auctionDAO.findById("auc-keep").isPresent());
        assertEquals(1, bidDAO.countByAuctionId("auc-keep"));
    }

    @Test
    void testLegacyMigrationAndFullServerRestartRecovery(@TempDir Path tempDir) throws Exception {
        Path legacyDbFile = tempDir.resolve("legacy-auction.db");
        DBConnection legacyDb = DBConnection.getInstance("jdbc:sqlite:" + legacyDbFile);
        UserDAO legacyUsers = new UserDAO(legacyDb);
        Bidder sellerAccount = new Bidder("u-seller", "manh_seller", "hashA", "m@example.com", 20_000_000);
        Bidder bidderAccount = new Bidder("u-bidder", "nam_bidder", "hashB", "n@example.com", 80_000_000);
        assertTrue(legacyUsers.create(sellerAccount));
        assertTrue(legacyUsers.create(bidderAccount));
        DBConnection.resetInstance();

        // Mở DB bền vững mới và gộp dữ liệu từ legacy DB (giả lập re-clone / chuyển thư mục sau khi commit)
        Path persistentDbFile = tempDir.resolve("persistent-auction.db");
        DBConnection persistentDb = DBConnection.getInstance("jdbc:sqlite:" + persistentDbFile);
        persistentDb.importFromSqliteFile(legacyDbFile);

        UserDAO users = new UserDAO(persistentDb);
        assertTrue(users.findByUsername("manh_seller").isPresent());
        assertTrue(users.findByUsername("nam_bidder").isPresent());

        // Khởi tạo phiên, đặt giá và lưu tự động xuống DB
        AuctionManager manager = AuctionManager.getInstance();
        manager.clearAll();
        ConcurrentHashMap<String, Bidder> activeBidders = new ConcurrentHashMap<>();
        AuctionPersistenceService.getInstance().initializeAndLoad(manager, activeBidders);

        Item skin = ItemFactory.createItem(
                ItemFactory.ItemType.ART, "item-persist", "Karambit Lore", "MW", 30_000_000, Map.of());
        Auction liveAuction = new Auction(
                "auc-persist", skin, sellerAccount.getId(), 1_000_000,
                LocalDateTime.now().minusMinutes(5), LocalDateTime.now().plusMinutes(45));
        liveAuction.startAuction();
        manager.registerAuction(liveAuction);
        AuctionPersistenceService.getInstance().persistCreatedAuction(liveAuction);

        manager.placeBid("auc-persist", bidderAccount, 35_000_000);

        // Giả lập tắt hẳn Server & xóa sạch bộ nhớ RAM sau khi commit code mới
        manager.clearAll();
        DBConnection.resetInstance();

        // Khởi động lại Server trên cùng file persistent DB -> toàn bộ tài khoản, phiên, giá thắng & lịch sử phải còn nguyên
        DBConnection.getInstance("jdbc:sqlite:" + persistentDbFile);
        ConcurrentHashMap<String, Bidder> restartedBidders = new ConcurrentHashMap<>();
        int loaded = AuctionPersistenceService.getInstance().initializeAndLoad(manager, restartedBidders);

        assertEquals(1, loaded);
        assertEquals(2, new UserDAO().findAll().size());
        Auction restored = manager.getAuctionOrThrow("auc-persist");
        assertEquals(AuctionStatus.RUNNING, restored.getStatus());
        assertEquals(35_000_000, restored.getCurrentHighestBid());
        assertEquals("u-bidder", restored.getCurrentWinnerId());
        assertEquals(1, restored.getBidHistory().size());
    }

    @Test
    void testCloudDatabaseConfigNormalizationAndPropertiesFile(@TempDir Path tempDir) throws Exception {
        // 1. Chuỗi URI copy trực tiếp từ Neon / Supabase (PostgreSQL)
        DBConnection.DatabaseConfig neon = DBConnection.normalizeConfig(
                "postgresql://neondb_owner:my%40secret@ep-cool-123.ap-southeast-1.aws.neon.tech/neondb?sslmode=require",
                null, null);
        assertEquals(DBConnection.Dialect.POSTGRESQL, neon.dialect());
        assertTrue(neon.isCloudDatabase());
        assertEquals("jdbc:postgresql://ep-cool-123.ap-southeast-1.aws.neon.tech:5432/neondb?sslmode=require",
                neon.jdbcUrl());
        assertEquals("neondb_owner", neon.username());
        assertEquals("my@secret", neon.password());

        // 2. Chuỗi URI / JDBC từ TiDB Cloud / Aiven (MySQL)
        DBConnection.DatabaseConfig tidb = DBConnection.normalizeConfig(
                "mysql://tidb_user:tidb_pass@gateway01.ap-southeast-1.prod.aws.tidbcloud.com:4000/test?sslMode=VERIFY_IDENTITY",
                null, null);
        assertEquals(DBConnection.Dialect.MYSQL, tidb.dialect());
        assertTrue(tidb.isCloudDatabase());
        assertEquals("jdbc:mysql://gateway01.ap-southeast-1.prod.aws.tidbcloud.com:4000/test?sslMode=VERIFY_IDENTITY",
                tidb.jdbcUrl());
        assertEquals("tidb_user", tidb.username());
        assertEquals("tidb_pass", tidb.password());

        // 3. Đọc cấu hình từ tệp db.properties
        Path props = tempDir.resolve("db.properties");
        Files.writeString(props,
                "db.url=jdbc:postgresql://db.example.supabase.co:5432/postgres?sslmode=require\n"
                        + "db.user=postgres\n"
                        + "db.password=super_secret_123\n");
        DBConnection.DatabaseConfig fromFile = DBConnection.readConfigFile(props);
        assertNotNull(fromFile);
        assertEquals(DBConnection.Dialect.POSTGRESQL, fromFile.dialect());
        assertEquals("postgres", fromFile.username());
        assertEquals("super_secret_123", fromFile.password());
    }
}