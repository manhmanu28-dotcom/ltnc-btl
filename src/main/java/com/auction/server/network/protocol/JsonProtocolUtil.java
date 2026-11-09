package com.auction.server.network.protocol;

import com.auction.server.model.auction.Auction;
import com.auction.server.model.auction.BidTransaction;
import com.auction.server.model.item.ArtItem;
import com.auction.server.model.item.ElectronicsItem;
import com.auction.server.model.item.Item;
import com.auction.server.model.item.VehicleItem;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JsonProtocolUtil: Bộ đóng gói (Serializer) và giải mã (Parser) bản tin JSON cho giao thức Socket (Nhiệm vụ Thành viên 2).
 *
 * Đặc điểm thiết kế:
 * - Đóng gói đầy đủ SocketRequest, SocketResponse, Auction DTO, BidTransaction DTO thành chuỗi JSON chuẩn RFC 8259 trên 1 dòng (Single-line JSON) để truyền qua BufferedReader.readLine() / PrintWriter.println().
 * - Giải mã (Parse) chuỗi JSON lồng nhau thành cấu trúc Map/List/Primitive và khôi phục thành SocketRequest / SocketResponse.
 * - Tương thích 100% với định dạng JSON của Gson/Jackson trên phía Client mà không bị lỗi InaccessibleObjectException với java.time.LocalDateTime trên JDK 17+.
 */
public final class JsonProtocolUtil {

    private JsonProtocolUtil() {
    }

    // =========================================================================
    // 1. CHUYỂN ĐỔI MODEL NGHIỆP VỤ SANG BẢN ĐỒ DỮ LIỆU JSON (DTO MAPPERS)
    // =========================================================================

    /**
     * Chuyển đổi đối tượng Auction sang Map chuẩn JSON để gửi cho Client.
     */
    public static Map<String, Object> toAuctionDto(Auction auction, boolean includeBidHistory) {
        Map<String, Object> map = new LinkedHashMap<>();
        Item item = auction.getItem();

        map.put("auctionId", auction.getId());
        map.put("sellerId", auction.getSellerId());
        map.put("status", auction.getStatus().name());
        map.put("currentHighestBid", auction.getCurrentHighestBid());
        map.put("minimumNextBid", auction.getMinimumNextBid());
        map.put("minIncrement", auction.getMinIncrement());
        map.put("currentWinnerId", auction.getCurrentWinnerId());
        map.put("currentWinnerUsername", auction.getCurrentWinnerUsername());
        map.put("startTime", auction.getStartTime().toString());
        map.put("endTime", auction.getEndTime().toString());

        if (item != null) {
            Map<String, Object> itemMap = new LinkedHashMap<>();
            itemMap.put("itemId", item.getId());
            itemMap.put("title", item.getTitle());
            itemMap.put("description", item.getDescription());
            itemMap.put("startingPrice", item.getStartingPrice());
            itemMap.put("category", resolveItemCategory(item));
            itemMap.put("specificDetails", item.getSpecificDetails());
            map.put("item", itemMap);
        }

        List<BidTransaction> history = auction.getBidHistory();
        map.put("totalBids", history.size());
        if (includeBidHistory) {
            List<Map<String, Object>> historyList = new ArrayList<>(history.size());
            for (BidTransaction tx : history) {
                historyList.add(toBidTransactionDto(tx));
            }
            map.put("bidHistory", historyList);
        }

        return map;
    }

    private static String resolveItemCategory(Item item) {
        if (item instanceof ElectronicsItem) {
            return "ELECTRONICS";
        }
        if (item instanceof ArtItem) {
            return "ART";
        }
        if (item instanceof VehicleItem) {
            return "VEHICLE";
        }
        return item.getClass().getSimpleName();
    }

    /**
     * Chuyển đổi giao dịch đặt giá BidTransaction sang Map chuẩn JSON.
     */
    public static Map<String, Object> toBidTransactionDto(BidTransaction tx) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("transactionId", tx.getId());
        map.put("auctionId", tx.getAuctionId());
        map.put("bidderId", tx.getBidderId());
        map.put("bidderUsername", tx.getBidderUsername());
        map.put("amount", tx.getAmount());
        map.put("bidTime", tx.getBidTime().toString());
        map.put("autoBid", tx.isAutoBid());
        return map;
    }

    // =========================================================================
    // 2. SERIALIZE SOCKET REQUEST / RESPONSE RA CHUỖI JSON 1 DÒNG
    // =========================================================================

    public static String toJson(SocketRequest request) {
        if (request == null) {
            return "null";
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("requestId", request.getRequestId());
        root.put("action", request.getAction() != null ? request.getAction().name() : null);
        root.put("userId", request.getUserId());
        root.put("auctionId", request.getAuctionId());
        root.put("timestamp", request.getTimestamp() != null ? request.getTimestamp().toString() : null);
        root.put("payload", request.getPayload());
        return serializeValue(root);
    }

    public static String toJson(SocketResponse response) {
        if (response == null) {
            return "null";
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("requestId", response.getRequestId());
        root.put("action", response.getAction() != null ? response.getAction().name() : null);
        root.put("success", response.isSuccess());
        root.put("message", response.getMessage());
        root.put("auctionId", response.getAuctionId());
        root.put("timestamp", response.getTimestamp() != null ? response.getTimestamp().toString() : null);
        root.put("data", response.getData());
        return serializeValue(root);
    }

    /**
     * Khôi phục đối tượng SocketRequest từ chuỗi JSON.
     */
    @SuppressWarnings("unchecked")
    public static SocketRequest fromJsonRequest(String json) {
        if (json == null || json.trim().isEmpty()) {
            throw new IllegalArgumentException("Chuỗi JSON Request không được rỗng!");
        }
        Object parsed = new SimpleJsonParser(json).parse();
        if (!(parsed instanceof Map)) {
            throw new IllegalArgumentException("Cấu trúc JSON Request phải là một JSON Object!");
        }
        Map<String, Object> map = (Map<String, Object>) parsed;

        SocketRequest req = new SocketRequest();
        if (map.get("requestId") != null) {
            req.setRequestId(String.valueOf(map.get("requestId")));
        }
        if (map.get("action") != null) {
            req.setAction(ActionType.fromString(String.valueOf(map.get("action"))));
        } else {
            throw new IllegalArgumentException("Bản tin JSON Request thiếu trường 'action'!");
        }
        if (map.get("userId") != null) {
            req.setUserId(String.valueOf(map.get("userId")));
        }
        if (map.get("auctionId") != null) {
            req.setAuctionId(String.valueOf(map.get("auctionId")));
        }
        if (map.get("timestamp") != null) {
            req.setTimestamp(LocalDateTime.parse(String.valueOf(map.get("timestamp"))));
        }
        Object payloadObj = map.get("payload");
        if (payloadObj instanceof Map) {
            req.setPayload((Map<String, Object>) payloadObj);
        }
        return req;
    }

    /**
     * Khôi phục đối tượng SocketResponse từ chuỗi JSON.
     */
    @SuppressWarnings("unchecked")
    public static SocketResponse fromJsonResponse(String json) {
        if (json == null || json.trim().isEmpty()) {
            throw new IllegalArgumentException("Chuỗi JSON Response không được rỗng!");
        }
        Object parsed = new SimpleJsonParser(json).parse();
        if (!(parsed instanceof Map)) {
            throw new IllegalArgumentException("Cấu trúc JSON Response phải là một JSON Object!");
        }
        Map<String, Object> map = (Map<String, Object>) parsed;

        SocketResponse resp = new SocketResponse();
        if (map.get("requestId") != null) {
            resp.setRequestId(String.valueOf(map.get("requestId")));
        }
        if (map.get("action") != null) {
            resp.setAction(ActionType.fromString(String.valueOf(map.get("action"))));
        }
        if (map.get("success") != null) {
            resp.setSuccess(Boolean.parseBoolean(String.valueOf(map.get("success"))));
        }
        if (map.get("message") != null) {
            resp.setMessage(String.valueOf(map.get("message")));
        }
        if (map.get("auctionId") != null) {
            resp.setAuctionId(String.valueOf(map.get("auctionId")));
        }
        if (map.get("timestamp") != null) {
            resp.setTimestamp(LocalDateTime.parse(String.valueOf(map.get("timestamp"))));
        }
        Object dataObj = map.get("data");
        if (dataObj instanceof Map) {
            resp.setData((Map<String, Object>) dataObj);
        }
        return resp;
    }

    // =========================================================================
    // 3. BỘ SERIALIZER & RECURSIVE DESCENT JSON PARSER NỘI BỘ (CHUẨN RFC 8259)
    // =========================================================================

    public static String serializeValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String || value instanceof LocalDateTime || value instanceof Enum<?>) {
            return "\"" + escapeJsonString(String.valueOf(value)) + "\"";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Map<?, ?>) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) {
                    sb.append(",");
                }
                first = false;
                sb.append("\"").append(escapeJsonString(String.valueOf(entry.getKey()))).append("\":");
                sb.append(serializeValue(entry.getValue()));
            }
            sb.append("}");
            return sb.toString();
        }
        if (value instanceof Collection<?>) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : (Collection<?>) value) {
                if (!first) {
                    sb.append(",");
                }
                first = false;
                sb.append(serializeValue(item));
            }
            sb.append("]");
            return sb.toString();
        }
        return "\"" + escapeJsonString(String.valueOf(value)) + "\"";
    }

    private static String escapeJsonString(String input) {
        StringBuilder sb = new StringBuilder(input.length() + 8);
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    private static final class SimpleJsonParser {
        private final String src;
        private int pos;

        SimpleJsonParser(String src) {
            this.src = src;
            this.pos = 0;
        }

        Object parse() {
            skipWhitespace();
            Object val = parseValue();
            skipWhitespace();
            return val;
        }

        private Object parseValue() {
            skipWhitespace();
            if (pos >= src.length()) {
                throw new IllegalArgumentException("JSON kết thúc bất ngờ tại vị trí " + pos);
            }
            char c = src.charAt(pos);
            if (c == '{') {
                return parseObject();
            }
            if (c == '[') {
                return parseArray();
            }
            if (c == '"') {
                return parseString();
            }
            if (c == 't' || c == 'f') {
                return parseBoolean();
            }
            if (c == 'n') {
                return parseNull();
            }
            if (c == '-' || (c >= '0' && c <= '9')) {
                return parseNumber();
            }
            throw new IllegalArgumentException("Ký tự JSON không hợp lệ '" + c + "' tại vị trí " + pos);
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // Bỏ qua '{'
            skipWhitespace();
            if (pos < src.length() && src.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (pos < src.length()) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                if (pos >= src.length() || src.charAt(pos) != ':') {
                    throw new IllegalArgumentException("Thiếu dấu ':' sau khóa '" + key + "' tại vị trí " + pos);
                }
                pos++; // Bỏ qua ':'
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                if (pos < src.length() && src.charAt(pos) == ',') {
                    pos++;
                    continue;
                }
                if (pos < src.length() && src.charAt(pos) == '}') {
                    pos++;
                    return map;
                }
                throw new IllegalArgumentException("Thiếu dấu ',' hoặc '}' trong JSON Object tại vị trí " + pos);
            }
            throw new IllegalArgumentException("JSON Object chưa được đóng dấu '}'!");
        }

        private List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            pos++; // Bỏ qua '['
            skipWhitespace();
            if (pos < src.length() && src.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (pos < src.length()) {
                Object element = parseValue();
                list.add(element);
                skipWhitespace();
                if (pos < src.length() && src.charAt(pos) == ',') {
                    pos++;
                    continue;
                }
                if (pos < src.length() && src.charAt(pos) == ']') {
                    pos++;
                    return list;
                }
                throw new IllegalArgumentException("Thiếu dấu ',' hoặc ']' trong JSON Array tại vị trí " + pos);
            }
            throw new IllegalArgumentException("JSON Array chưa được đóng dấu ']'!");
        }

        private String parseString() {
            if (src.charAt(pos) != '"') {
                throw new IllegalArgumentException("Chuỗi JSON phải bắt đầu bằng dấu '\"' tại vị trí " + pos);
            }
            pos++; // Bỏ qua '"'
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= src.length()) {
                        throw new IllegalArgumentException("Chuỗi thoát JSON không hợp lệ ở cuối văn bản!");
                    }
                    char esc = src.charAt(pos++);
                    switch (esc) {
                        case '"':
                            sb.append('"');
                            break;
                        case '\\':
                            sb.append('\\');
                            break;
                        case '/':
                            sb.append('/');
                            break;
                        case 'b':
                            sb.append('\b');
                            break;
                        case 'f':
                            sb.append('\f');
                            break;
                        case 'n':
                            sb.append('\n');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'u':
                            if (pos + 4 > src.length()) {
                                throw new IllegalArgumentException("Mã Unicode \\uXXXX không hợp lệ!");
                            }
                            String hex = src.substring(pos, pos + 4);
                            sb.append((char) Integer.parseInt(hex, 16));
                            pos += 4;
                            break;
                        default:
                            sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("Chuỗi JSON chưa được đóng dấu '\"'!");
        }

        private Boolean parseBoolean() {
            if (src.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (src.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("Giá trị boolean không hợp lệ tại vị trí " + pos);
        }

        private Object parseNull() {
            if (src.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new IllegalArgumentException("Giá trị null không hợp lệ tại vị trí " + pos);
        }

        private Number parseNumber() {
            int start = pos;
            if (src.charAt(pos) == '-') {
                pos++;
            }
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                pos++;
            }
            boolean isFloating = false;
            if (pos < src.length() && src.charAt(pos) == '.') {
                isFloating = true;
                pos++;
                while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                    pos++;
                }
            }
            if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
                isFloating = true;
                pos++;
                if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) {
                    pos++;
                }
                while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                    pos++;
                }
            }
            String numStr = src.substring(start, pos);
            if (isFloating) {
                return Double.parseDouble(numStr);
            }
            long longVal = Long.parseLong(numStr);
            if (longVal >= Integer.MIN_VALUE && longVal <= Integer.MAX_VALUE) {
                return (int) longVal;
            }
            return longVal;
        }

        private void skipWhitespace() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }
    }
}

