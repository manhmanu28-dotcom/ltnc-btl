package com.auction.server.network.protocol;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SocketResponse: Cấu trúc gói tin JSON do Server phản hồi lại Request của Client
 * hoặc chủ động Broadcast (Realtime Update) tới các Client đang theo dõi phiên đấu giá (Nhiệm vụ Thành viên 2).
 *
 * Ví dụ gói tin JSON:
 * {
 *   "requestId": "REQ-A1B2C3D4",
 *   "action": "NOTIFY_UPDATE",
 *   "success": true,
 *   "message": "Có lượt đặt giá mới hợp lệ!",
 *   "auctionId": "AUC-001",
 *   "timestamp": "2026-11-09T22:20:01",
 *   "data": {
 *     "eventType": "NEW_BID",
 *     "currentHighestBid": 15500000.0,
 *     "currentWinnerId": "BIDDER-01",
 *     "timeExtended": false
 *   }
 * }
 */
public class SocketResponse {
    private String requestId;
    private ActionType action;
    private boolean success;
    private String message;
    private String auctionId;
    private LocalDateTime timestamp;
    private Map<String, Object> data;

    public SocketResponse() {
        this.timestamp = LocalDateTime.now();
        this.data = new LinkedHashMap<>();
    }

    public SocketResponse(String requestId, ActionType action, boolean success,
                          String message, String auctionId, Map<String, Object> data) {
        this();
        this.requestId = requestId;
        this.action = action;
        this.success = success;
        this.message = message;
        this.auctionId = auctionId;
        if (data != null) {
            this.data.putAll(data);
        }
    }

    // =========================================================================
    // FACTORY METHODS TẠO GÓI TIN PHẢN HỒI & REALTIME BROADCAST
    // =========================================================================

    public static SocketResponse ok(SocketRequest request, String message) {
        String reqId = (request != null) ? request.getRequestId() : null;
        ActionType act = (request != null) ? request.getAction() : ActionType.PING;
        String aucId = (request != null) ? request.getAuctionId() : null;
        return new SocketResponse(reqId, act, true, message, aucId, null);
    }

    public static SocketResponse error(SocketRequest request, String errorMessage) {
        String reqId = (request != null) ? request.getRequestId() : null;
        ActionType act = (request != null && request.getAction() != null) ? request.getAction() : ActionType.ERROR;
        String aucId = (request != null) ? request.getAuctionId() : null;
        return new SocketResponse(reqId, act, false, errorMessage, aucId, null);
    }

    public static SocketResponse broadcast(ActionType action, String auctionId, String message) {
        return new SocketResponse(null, action, true, message, auctionId, null);
    }

    public SocketResponse putData(String key, Object value) {
        if (key != null && !key.trim().isEmpty()) {
            this.data.put(key.trim(), value);
        }
        return this;
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

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
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

    public Map<String, Object> getData() {
        return Collections.unmodifiableMap(data);
    }

    public void setData(Map<String, Object> data) {
        this.data.clear();
        if (data != null) {
            this.data.putAll(data);
        }
    }

    public String toJson() {
        return JsonProtocolUtil.toJson(this);
    }

    public static SocketResponse fromJson(String json) {
        return JsonProtocolUtil.fromJsonResponse(json);
    }
}

