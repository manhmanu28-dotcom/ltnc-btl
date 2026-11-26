package com.auction.client.service;

import com.auction.client.model.AuctionRoomSnapshot;
import com.auction.server.network.AuctionSocketClient;
import com.auction.server.network.protocol.ActionType;
import com.auction.server.network.protocol.SocketRequest;
import com.auction.server.network.protocol.SocketResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Serializes room requests; refreshes complete history on server push, without polling. */
public final class AuctionRoomService implements AutoCloseable {
    private final AuctionSocketClient client;
    private final String auctionId;
    private final Consumer<AuctionRoomSnapshot> onSnapshot;
    private final Consumer<Throwable> onError;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Auction-Room-Network");
        thread.setDaemon(true);
        return thread;
    });
    private final Consumer<SocketResponse> listener = this::receive;
    private boolean started;
    private volatile boolean closed;

    public AuctionRoomService(AuctionSocketClient client, String auctionId,
            Consumer<AuctionRoomSnapshot> onSnapshot, Consumer<Throwable> onError) {
        this.client = client;
        this.auctionId = auctionId;
        this.onSnapshot = onSnapshot;
        this.onError = onError;
    }

    public synchronized void start() {
        if (started || closed) return;
        started = true;
        client.addRealtimeListener(listener);
        execute(() -> publish(request(ActionType.WATCH_AUCTION)));
    }

    private void receive(SocketResponse event) {
        if (auctionId.equals(event.getAuctionId())
                && (event.getAction() == ActionType.BID_UPDATE || event.getAction() == ActionType.STATUS_UPDATE)) {
            refresh();
        }
    }

    public void refresh() {
        execute(() -> publish(request(ActionType.WATCH_AUCTION)));
    }

    public synchronized CompletableFuture<String> placeBid(double amount) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Phòng đã đóng."));
        return CompletableFuture.supplyAsync(() -> {
            SocketResponse response = checked(client.sendRequest(
                    SocketRequest.of(ActionType.PLACE_BID, null, auctionId).putPayload("amount", amount)));
            publish(request(ActionType.GET_AUCTION_DETAIL));
            return response.getMessage();
        }, worker);
    }

    private synchronized void execute(Runnable task) {
        if (closed) return;
        worker.execute(() -> {
            if (closed) return;
            try { task.run(); }
            catch (Exception error) { if (!closed) onError.accept(error); }
        });
    }

    private SocketResponse request(ActionType action) {
        return checked(client.sendRequest(SocketRequest.of(action, null, auctionId)));
    }

    private SocketResponse checked(SocketResponse response) {
        if (!response.isSuccess()) throw new IllegalStateException(response.getMessage());
        return response;
    }

    private void publish(SocketResponse response) {
        if (!closed) onSnapshot.accept(AuctionRoomSnapshot.from(response));
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        client.removeRealtimeListener(listener);
        // Ordered after any in-flight WATCH; the login connection belongs to the application.
        worker.execute(() -> {
            try {
                if (started && client.isConnected()) client.sendRequest(
                        SocketRequest.of(ActionType.UNWATCH_AUCTION, null, auctionId));
            } catch (IllegalStateException ignored) {
                // The app may disconnect the shared client while the room is closing.
            }
        });
        worker.shutdown();
    }
}
