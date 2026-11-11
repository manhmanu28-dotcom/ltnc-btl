package com.auction.client.service;

import com.auction.server.network.AuctionSocketClient;
import com.auction.server.network.protocol.ActionType;
import com.auction.server.network.protocol.SocketRequest;
import com.auction.server.network.protocol.SocketResponse;

import java.io.IOException;

/** Được gọi trên một executor nền; giữ nguyên kết nối sau khi đăng nhập. */
public class AuthenticationClientService implements AutoCloseable {
    private AuctionSocketClient client;
    private String host;
    private int port;

    public SocketResponse submit(String host, int port, boolean register,
                                 String username, String password, String email) throws IOException {
        if (client == null || !client.isConnected() || !host.equals(this.host) || port != this.port) {
            close();
            client = new AuctionSocketClient(host, port);
            this.host = host;
            this.port = port;
            client.connect();
        }
        SocketRequest request = SocketRequest.of(register ? ActionType.REGISTER : ActionType.LOGIN)
                .putPayload("username", username)
                .putPayload("password", password);
        if (register) {
            request.putPayload("email", email);
        }
        SocketResponse response = client.sendRequest(request);
        if (!response.isSuccess()) {
            throw new IOException(response.getMessage());
        }
        return response;
    }

    public AuctionSocketClient getClient() {
        return client;
    }

    @Override
    public void close() {
        if (client != null) {
            client.close();
            client = null;
        }
    }
}
