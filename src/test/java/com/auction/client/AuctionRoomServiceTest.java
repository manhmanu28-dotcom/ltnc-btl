package com.auction.client;

import com.auction.client.model.AuctionRoomSnapshot;
import com.auction.client.service.AuctionRoomService;
import com.auction.server.dao.UserDAO;
import com.auction.server.database.DBConnection;
import com.auction.server.model.auction.Auction;
import com.auction.server.model.entity.Bidder;
import com.auction.server.model.item.ArtItem;
import com.auction.server.network.AuctionServer;
import com.auction.server.network.AuctionSocketClient;
import com.auction.server.network.protocol.ActionType;
import com.auction.server.network.protocol.SocketRequest;
import com.auction.server.service.AuctionManager;
import com.auction.server.util.PasswordUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.concurrent.*;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;

class AuctionRoomServiceTest {
    @TempDir Path directory;
    private AuctionServer server;
    private Auction auction;
    private AuctionSocketClient first, second;
    private AuctionRoomService roomA, roomB;
    private final BlockingQueue<AuctionRoomSnapshot> updatesA = new LinkedBlockingQueue<>();
    private final BlockingQueue<AuctionRoomSnapshot> updatesB = new LinkedBlockingQueue<>();
    private final BlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();

    @BeforeEach void start() throws Exception {
        DBConnection.resetInstance();
        DBConnection.getInstance("jdbc:sqlite:" + directory.resolve("room.db"));
        AuctionManager.getInstance().clearAll();
        var users = new UserDAO();
        for (String name : new String[]{"room_alice", "room_bob"}) {
            users.create(new Bidder(name, name, PasswordUtil.hashPassword("test-password"),
                    name + "@example.com", 100_000_000));
        }
        auction = new Auction("LIVE-ROOM", new ArtItem("ITEM", "Room test", "Live product", 1_000,
                "Artist", false), "SELLER", 100, LocalDateTime.now().minusMinutes(1),
                LocalDateTime.now().plusMinutes(10));
        auction.startAuction();
        AuctionManager.getInstance().registerAuction(auction);
        server = new AuctionServer(0);
        server.start();
        first = login("room_alice");
        second = login("room_bob");
        roomA = new AuctionRoomService(first, auction.getId(), updatesA::add, errors::add);
        roomB = new AuctionRoomService(second, auction.getId(), updatesB::add, errors::add);
        roomA.start();
        roomB.start();
        await(updatesA, s -> s.bids().isEmpty());
        await(updatesB, s -> s.bids().isEmpty());
    }

    private AuctionSocketClient login(String name) throws Exception {
        var client = new AuctionSocketClient("127.0.0.1", server.getLocalPort());
        client.connect();
        var response = client.sendRequest(SocketRequest.of(ActionType.LOGIN)
                .putPayload("username", name).putPayload("password", "test-password"));
        assertTrue(response.isSuccess(), response.getMessage());
        return client;
    }

    @AfterEach void stop() {
        if (roomA != null) roomA.close();
        if (roomB != null) roomB.close();
        if (first != null) first.close();
        if (second != null) second.close();
        if (server != null) server.close();
        AuctionManager.getInstance().clearAll();
        DBConnection.resetInstance();
    }

    @Test void twoRoomsReceiveRealBidsAndExistingHistoryWithoutDuplicateTransactions() throws Exception {
        roomA.placeBid(1_000).get(5, TimeUnit.SECONDS);
        var firstBid = await(updatesB, s -> s.bids().size() == 1);
        assertEquals("room_alice", firstBid.winner());
        assertEquals(1_100, firstBid.minimumNextBid());
        roomB.placeBid(1_100).get(5, TimeUnit.SECONDS);
        var secondBid = await(updatesA, s -> s.bids().size() == 2);
        assertEquals("room_bob", secondBid.winner());
        assertEquals(1_100, secondBid.highest());
        assertEquals(2, secondBid.bids().stream().map(AuctionRoomSnapshot.Bid::id).distinct().count());
        roomB.close();
        roomB = new AuctionRoomService(second, auction.getId(), updatesB::add, errors::add);
        roomB.start();
        assertEquals(2, await(updatesB, s -> s.bids().size() == 2).bids().size());
        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test void serverRejectsLowBidAndPushesExtensionAndCancellation() throws Exception {
        assertThrows(ExecutionException.class, () -> roomA.placeBid(999).get(5, TimeUnit.SECONDS));
        assertTrue(auction.getBidHistory().isEmpty());
        auction.setAntiSnipingConfig(3600, 60);
        LocalDateTime previousEnd = auction.getEndTime();
        roomA.placeBid(1_000).get(5, TimeUnit.SECONDS);
        var extended = await(updatesB, s -> s.bids().size() == 1);
        assertTrue(extended.endTime().isAfter(previousEnd));
        auction.cancelAuction();
        assertEquals("CANCELED", await(updatesB, s -> "CANCELED".equals(s.status())).status());
        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test void leavingUnwatchesRoomAndPreservesAuthenticatedConnection() throws Exception {
        assertEquals(2, server.getBroadcastManager().getWatcherCount(auction.getId()));
        roomA.close();
        // UNWATCH runs in the background; wait for the server to process it.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (server.getBroadcastManager().getWatcherCount(auction.getId()) != 1 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(1, server.getBroadcastManager().getWatcherCount(auction.getId()));
        assertTrue(first.isConnected());
        assertTrue(first.sendRequest(SocketRequest.of(ActionType.GET_AUCTIONS)).isSuccess());
    }

    @Test void serverPublishesFinalWinnerToBothRooms() throws Exception {
        roomA.placeBid(1_000).get(5, TimeUnit.SECONDS);
        auction.closeAuction();
        var resultA = await(updatesA, s -> "FINISHED".equals(s.status()));
        var resultB = await(updatesB, s -> "FINISHED".equals(s.status()));
        assertEquals("room_alice", resultA.winner());
        assertEquals(resultA.winner(), resultB.winner());
        assertEquals(1_000, resultB.highest());
    }

    private AuctionRoomSnapshot await(BlockingQueue<AuctionRoomSnapshot> queue,
            Predicate<AuctionRoomSnapshot> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            var snapshot = queue.poll(100, TimeUnit.MILLISECONDS);
            if (snapshot != null && condition.test(snapshot)) return snapshot;
        }
        fail("Không nhận được snapshot mong đợi. " + errors);
        return null;
    }
}
