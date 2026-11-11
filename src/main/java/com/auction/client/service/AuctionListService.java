package com.auction.client.service;

import com.auction.client.model.AuctionSummary;
import com.auction.server.network.AuctionSocketClient;
import com.auction.server.network.protocol.ActionType;
import com.auction.server.network.protocol.SocketRequest;
import com.auction.server.network.protocol.SocketResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Gọi Socket và chuyển dữ liệu JSON thành các dòng của bảng, không phụ thuộc JavaFX. */
public class AuctionListService {

    // Hàm này chờ mạng nên controller phải gọi trên luồng nền.
    public List<AuctionSummary> fetchAuctions(String host, int port) throws IOException {
        try (AuctionSocketClient client = new AuctionSocketClient(host, port)) {
            client.connect();
            return fetchAuctions(client);
        }
    }

    /** Dùng kết nối của phiên đã đăng nhập, không đóng nó sau khi tải danh sách. */
    public List<AuctionSummary> fetchAuctions(AuctionSocketClient client) throws IOException {
        SocketResponse response = client.sendRequest(SocketRequest.of(ActionType.GET_AUCTIONS));
        if (!response.isSuccess()) {
            throw new IOException(response.getMessage());
        }

        Object data = response.getData().get("auctions");
        if (!(data instanceof List<?> auctions)) {
            throw new IOException("Server trả về danh sách phiên không hợp lệ.");
        }

        List<AuctionSummary> result = new ArrayList<>();
        for (Object entry : auctions) {
            if (!(entry instanceof Map<?, ?> auction)
                    || !(auction.get("auctionId") instanceof String id)
                    || !(auction.get("status") instanceof String status)
                    || !(auction.get("currentHighestBid") instanceof Number price)
                    || !(auction.get("item") instanceof Map<?, ?> item)
                    || !(item.get("title") instanceof String title)) {
                throw new IOException("Server trả về thông tin phiên không hợp lệ.");
            }
            result.add(new AuctionSummary(id, title, price.doubleValue(), status));
        }
        return result;
    }
}
