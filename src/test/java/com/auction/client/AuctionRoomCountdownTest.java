package com.auction.client;

import com.auction.client.model.AuctionRoomSnapshot;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AuctionRoomCountdownTest {
    private AuctionRoomSnapshot snapshot(long durationNanos) {
        var serverTime = LocalDateTime.of(2026, 1, 1, 12, 0);
        return new AuctionRoomSnapshot("AUC", "Item", "", "RUNNING", 100, 100, 100, 10,
                "", serverTime.plusNanos(durationNanos), serverTime, 0, List.of());
    }

    @Test void fractionalServerTimeAndLatePulsesDoNotCauseEarlySecondChanges() {
        var value = snapshot(3_250_000_000L);
        assertEquals(4, value.remainingSeconds(0));
        assertEquals(3, value.remainingSeconds(999_000_000L));
        assertEquals(3, value.remainingSeconds(1_001_000_000L));
        assertEquals(2, value.remainingSeconds(1_999_000_000L));
        assertEquals(2, value.remainingSeconds(2_001_000_000L));
        assertEquals(1, value.remainingSeconds(3_249_999_999L));
        assertEquals(0, value.remainingSeconds(3_250_000_000L));
        assertEquals(0, value.remainingSeconds(5_000_000_000L));
    }

    @Test void delayedUiUsesActualElapsedTimeAndNewSnapshotCanExtendDeadline() {
        var original = snapshot(10_000_000_000L);
        assertEquals(10, original.remainingSeconds(0));
        assertEquals(8, original.remainingSeconds(2_400_000_000L));
        assertEquals(0, original.remainingSeconds(12_000_000_000L));
        assertEquals(58, snapshot(70_000_000_000L).remainingSeconds(12_000_000_000L));
    }
}
