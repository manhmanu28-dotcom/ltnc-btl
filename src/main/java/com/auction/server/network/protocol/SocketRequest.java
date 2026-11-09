package com.auction.server.network.protocol;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * SocketRequest: Cấu trúc gói tin JSON do Client gửi lên Server qua kết nối Socket (Nhiệm vụ Thành viên 2).
 *
 * Ví dụ gói tin JSON:
 * {
 *   "requestId": "REQ-A1B2C3D4",
 *   "action": "PLACE_BID",
 *   "userId": "BIDDER-01",
 *   "auctionId": "AUC-001",
 *   "timestamp": "2026-11-09T22:20:00",
 *   "payload": {
 *     "amount": 15500000.0,
 *     "username": "nguyen_hoang_viet",
 *     "balance": 50000000.0
 *   }
 * }
 */
public class SocketRequest {
    private String requestId;
    private ActionType action;
    private String userId;
    private String auctionId;
    private LocalDateTime timestamp;
    private Map<String, Object> payload;

    public SocketRequest() {
        this.requestId = "REQ-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        this.timestamp = LocalDateTime.now();
        this.payload = new LinkedHashMap<>();
    }

    public SocketRequest(ActionType action, String userId, String auctionId, Map<String, Object> payload) {
        this();
        if (action == null) {
            throw new IllegalArgumentException("ActionType của SocketRequest không được null!");
        }
        this.action = action;
        this.userId = userId;
        this.auctionId = auctionId;
        if (payload != null) {
            this.payload.putAll(payload);
        }
    }

    // =========================================================================
    // BUILDER TIỆN ÍCH CHO CLIENT GỬI YÊU CẦU NHANH
    // =========================================================================

    public static SocketRequest of(ActionType action) {
        return new SocketRequest(action, null, null, null);
    }

    public static SocketRequest of(ActionType action, String userId, String auctionId) {
        return new SocketRequest(action, userId, auctionId, null);
    }

    public SocketRequest withRequestId(String requestId) {
        if (requestId != null && !requestId.trim().isEmpty()) {
            this.requestId = requestId.trim();
        }
        return this;
    }

    public SocketRequest putPayload(String key, Object value) {
        if (key != null && !key.trim().isEmpty()) {
            this.payload.put(key.trim(), value);
        }
        return this;
    }

    // =========================================================================
    // TRUY XUẤT DỮ LIỆU PAYLOAD AN TOÀN KIỂU (TYPE-SAFE PAYLOAD EXTRACTORS)
    // =========================================================================

    public String getPayloadString(String key) {
        Object val = payload.get(key);
        return (val != null) ? String.valueOf(val).trim() : null;
    }

    public double getPayloadDouble(String key, double defaultValue) {
        Object val = payload.get(key);
        if (val == null) {
            return defaultValue;
        }
        if (val instanceof Number) {
            return ((Number) val).doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(val).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Trường '" + key + "' trong payload phải là số thực hợp lệ!");
        }
    }

    public double getRequiredPayloadDouble(String key) {
        if (!payload.containsKey(key) || payload.get(key) == null) {
            throw new IllegalArgumentException("Thiếu trường bắt buộc '" + key + "' trong payload!");
        }
        return getPayloadDouble(key, 0.0);
    }

    public int getPayloadInt(String key, int defaultValue) {
        Object val = payload.get(key);
        if (val == null) {
            return defaultValue;
        }
        if (val instanceof Number) {
            return ((Number) val).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(val).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Trường '" + key + "' trong payload phải là số nguyên hợp lệ!");
        }
    }

    // =========================================================================
    // GETTERS & SETTERS
    // =========================================================================

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public ActionType getAction() {
        return action;
    }

    public void setAction(ActionType action) {
        this.action = action;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getAuctionId() {
        return auctionId;
    }

    public void setAuctionId(String auctionId) {
        this.auctionId = auctionId;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public Map<String, Object> getPayload() {
        return Collections.unmodifiableMap(payload);
    }

    public void setPayload(Map<String, Object> payload) {
        this.payload.clear();
        if (payload != null) {
            this.payload.putAll(payload);
        }
    }

    public String toJson() {
        return JsonProtocolUtil.toJson(this);
    }

    public static SocketRequest fromJson(String json) {
        return JsonProtocolUtil.fromJsonRequest(json);
    }
}

