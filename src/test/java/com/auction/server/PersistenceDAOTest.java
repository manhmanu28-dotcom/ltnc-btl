package com.auction.server;

import com.auction.server.dao.AuctionDAO;
import com.auction.server.dao.BidDAO;
import com.auction.server.dao.ItemDAO;
import com.auction.server.database.DBConnection;
import com.auction.server.model.auction.BidTransaction;
import com.auction.server.model.item.ArtItem;
import com.auction.server.model.item.ElectronicsItem;
import com.auction.server.model.item.Item;
import com.auction.server.model.item.ItemFactory;
import com.auction.server.model.item.VehicleItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
}