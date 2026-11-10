package com.auction.client;

import com.auction.client.model.AuctionSummary;
import com.auction.client.service.AuctionListService;
import com.auction.server.model.auction.Auction;
import com.auction.server.model.item.Item;
import com.auction.server.model.item.ItemFactory;
import com.auction.server.network.AuctionServer;
import com.auction.server.service.AuctionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AuctionListServiceTest {

    private AuctionServer server;
    private final AuctionListService service = new AuctionListService();
    private final AuctionManager manager = AuctionManager.getInstance();

    @BeforeEach
    void startServer() throws IOException {
        manager.clearAll();
        server = new AuctionServer(0);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.close();
        manager.clearAll();
    }

    @Test
    void readsAuctionFromRealSocketServer() throws IOException {
        Item item = ItemFactory.createItem(ItemFactory.ItemType.ART,
                "ITEM-CLIENT", "Tranh phố cổ", "Mô tả tiếng Việt", 2_500_000,
                Map.of("artistName", "Họa sĩ", "hasCertificate", true));
        Auction auction = new Auction("AUC-CLIENT", item, "SELLER-CLIENT", 50_000,
                LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(2));
        manager.registerAuction(auction);

        List<AuctionSummary> auctions = service.fetchAuctions("127.0.0.1", server.getLocalPort());

        assertEquals(1, auctions.size());
        assertEquals(new AuctionSummary("AUC-CLIENT", "Tranh phố cổ", 2_500_000, "OPEN"),
                auctions.get(0));
    }

    @Test
    void returnsEmptyListWhenServerHasNoAuctions() throws IOException {
        assertTrue(service.fetchAuctions("127.0.0.1", server.getLocalPort()).isEmpty());
    }

    @Test
    void canRetryAfterConnectionFailure() throws IOException {
        int closedPort;
        try (ServerSocket unused = new ServerSocket(0)) {
            closedPort = unused.getLocalPort();
        }
        assertThrows(IOException.class, () -> service.fetchAuctions("127.0.0.1", closedPort));
        assertTrue(service.fetchAuctions("127.0.0.1", server.getLocalPort()).isEmpty());
    }
}
