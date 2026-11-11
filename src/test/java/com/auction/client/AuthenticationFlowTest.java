package com.auction.client;

import com.auction.server.dao.UserDAO;
import com.auction.server.database.DBConnection;
import com.auction.server.network.AuctionServer;
import com.auction.server.network.AuctionSocketClient;
import com.auction.server.network.protocol.ActionType;
import com.auction.server.network.protocol.SocketRequest;
import com.auction.server.network.protocol.SocketResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AuthenticationFlowTest {
    @TempDir
    Path directory;
    private String databaseUrl;
    private AuctionServer server;
    private AuctionSocketClient client;

    @BeforeEach
    void start() throws Exception {
        databaseUrl = "jdbc:sqlite:" + directory.resolve("accounts.db");
        DBConnection.resetInstance();
        DBConnection.getInstance(databaseUrl);
        startServer();
    }

    private void startServer() throws Exception {
        server = new AuctionServer(0);
        server.start();
        client = new AuctionSocketClient("127.0.0.1", server.getLocalPort());
        client.connect();
    }

    @AfterEach
    void stop() {
        client.close();
        server.close();
        DBConnection.resetInstance();
    }

    private SocketResponse register(String username, String password) {
        return client.sendRequest(SocketRequest.of(ActionType.REGISTER)
                .putPayload("username", username).putPayload("password", password)
                .putPayload("email", "user@example.com")
                .putPayload("role", "ADMIN").putPayload("balance", 100_000_000));
    }

    private SocketResponse login(String username, String password) {
        return client.sendRequest(SocketRequest.of(ActionType.LOGIN)
                .putPayload("username", username).putPayload("password", password));
    }

    @Test
    void registrationPersistsAcrossServerAndDatabaseRestart() throws Exception {
        assertTrue(register("saved_user", " strongPassword123 ").isSuccess());
        var stored = new UserDAO().findByUsername("saved_user").orElseThrow();
        assertTrue(stored.getPasswordHash().startsWith("pbkdf2$"));
        assertNotEquals(" strongPassword123 ", stored.getPasswordHash());
        assertEquals("BIDDER", stored.getRoleName());
        client.close();
        server.close();
        DBConnection.resetInstance();
        DBConnection.getInstance(databaseUrl);
        startServer();

        SocketResponse response = login("saved_user", " strongPassword123 ");
        assertTrue(response.isSuccess(), response.getMessage());
        assertEquals(stored.getId(), response.getData().get("userId"));
        assertFalse(response.toJson().contains(stored.getPasswordHash()));
        assertFalse(login("saved_user", "strongPassword123").isSuccess());
    }

    @Test
    void duplicateRegistrationDoesNotReplaceOriginalPassword() {
        assertTrue(register("same_user", "originalPassword").isSuccess());
        assertFalse(register("same_user", "replacementPassword").isSuccess());
        assertTrue(login("same_user", "originalPassword").isSuccess());
        assertFalse(login("same_user", "replacementPassword").isSuccess());
        assertEquals(1, new UserDAO().findAll().size());
    }

    @Test
    void rejectsInvalidRegistrationAndWrongCredentials() {
        assertFalse(register("ab", "longPassword").isSuccess());
        assertFalse(register("valid_name", "short").isSuccess());
        assertFalse(client.sendRequest(SocketRequest.of(ActionType.REGISTER)
                .putPayload("username", "valid_name").putPayload("password", "longPassword")
                .putPayload("email", "invalid-email")).isSuccess());
        assertFalse(login("unknown", "longPassword").isSuccess());
        assertTrue(new UserDAO().findAll().isEmpty());
    }

    @Test
    void rejectsLockedAccount() {
        assertTrue(register("locked_user", "longPassword").isSuccess());
        var user = new UserDAO().findByUsername("locked_user").orElseThrow();
        new UserDAO().setUserLocked(user.getId(), true);
        assertFalse(login("locked_user", "longPassword").isSuccess());
    }

    @Test
    void cannotBypassLoginWithUserIdOrBidAfterLogout() {
        assertTrue(register("bid_user", "longPassword").isSuccess());
        SocketResponse registered = login("bid_user", "longPassword");
        assertTrue(registered.isSuccess());
        assertTrue(client.sendRequest(SocketRequest.of(ActionType.LOGOUT)).isSuccess());
        SocketResponse bid = client.sendRequest(SocketRequest.of(ActionType.PLACE_BID,
                String.valueOf(registered.getData().get("userId")), "any-auction")
                .putPayload("amount", 100_000).putPayload("balance", 100_000_000));
        assertFalse(bid.isSuccess());
        assertTrue(bid.getMessage().contains("đăng nhập"));
    }
}
