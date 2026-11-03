# HỌC PHẦN: LẬP TRÌNH NÂNG CAO (ADVANCED PROGRAMMING)
## KẾ HOẠCH & PHÂN CÔNG CÔNG VIỆC BÀI TẬP LỚN
**Đề tài:** Phát Triển Hệ Thống Đấu Giá Trực Tuyến (Online Auction System)  
* **Quy mô nhóm:** 4 thành viên  
* **Kiến trúc:** Client - Server (JavaFX + Socket/JSON)  
* **Mục tiêu:** 10.0 Bắt buộc + 1.5 Điểm thưởng  

---

> ### ⚠️ QUY TẮC ĐÁNH GIÁ SỐNG CÒN TỪ ĐỀ BÀI (PHÒNG CHỐNG ĐIỂM 0):
> 1. *"Nếu bất kỳ thành viên nào không hiểu hoặc không thể giải thích bất kỳ phần mã nguồn nào, toàn bộ nhóm sẽ bị chấm 0 điểm."*
> 2. *"Sinh viên phải commit mã nguồn thường xuyên lên GitHub để chứng minh tiến độ; không chấp nhận chỉ có một commit duy nhất vào thời điểm cuối."*
> 3. *"Sau khi có điểm tổng, các thành viên tự phân chia điểm theo mức độ đóng góp minh bạch."*
> 
> 👉 **Giải pháp phân bổ:** Chia đều 4 mảng chuyên trách độc lập, mỗi thành viên chịu trách nhiệm 2.5 điểm trong thang 10 + 1.5 điểm, có lộ trình review chéo để tất cả thành viên đều hiểu 100% code của nhau.

---

## 1. BẢNG TỔNG QUAN PHÂN CÔNG & THANG ĐIỂM BAREM (10 + 1.5 ĐIỂM)

| Thành viên | Vai trò chính | Module & Trọng tâm kỹ thuật | Điểm phụ trách |
| :--- | :--- | :--- | :--- |
| **Thành viên 1**<br>*(Trưởng nhóm)* | **Core Architecture & Concurrency Engine** | • Phân cấp lớp OOP (Entity, User, Item, Auction, BidTransaction)<br>• Quản lý vòng đời phiên (State Machine: OPEN → RUNNING → FINISHED)<br>• Xử lý đấu giá đồng thời (Thread-safe, Concurrency Lock, chống Lost Update) | **2.5 Điểm**<br>*(0.5đ Model + 1.0đ OOP + 1.0đ Concurrency)* |
| **Thành viên 2** | **Networking, Protocol & Bidding Algorithms** | • Socket Server đa luồng (ClientHandler, ThreadPoolExecutor)<br>• Giao thức trao đổi dữ liệu JSON Request/Response<br>• Realtime Broadcast (Observer Pattern không dùng polling)<br>• Auto-Bidding (PriorityQueue) & Anti-sniping (Gia hạn phiên cuối) | **2.5 Điểm**<br>*(0.5đ Socket + 0.5đ Observer + 1.0đ Nâng cao)* |
| **Thành viên 3** | **Client Core & Realtime Live Bidding GUI** | • Giao diện JavaFX/FXML: Danh sách phiên, Chi tiết sản phẩm<br>• Màn hình Đấu giá trực tiếp (Live Bidding Room) với đếm ngược realtime<br>• Client Observer Listener (cập nhật UI qua `Platform.runLater`)<br>• Trực quan hóa giá: Biểu đồ đường Realtime LineChart | **2.5 Điểm**<br>*(1.0đ Core GUI + 1.0đ Bidding UX + 0.5đ LineChart)* |
| **Thành viên 4** | **Data Persistence, Admin GUI & QA / CI-CD** | • Database SQLite/MySQL & Tầng DAO (Singleton DB Connection)<br>• GUI Đăng nhập/Đăng ký & Màn hình Quản lý sản phẩm (Seller)<br>• Unit Test JUnit 5 cho toàn bộ logic nghiệp vụ cốt lõi<br>• CI/CD GitHub Actions & Báo cáo kỹ thuật tổng kết | **2.5 Điểm**<br>*(1.0đ User/Item + 0.5đ DAO + 0.5đ Test + 0.5đ CI/CD)* |

---

## 2. BẢN MÔ TẢ CHI TIẾT NHIỆM VỤ

### THÀNH VIÊN 1: KIẾN TRÚC HỆ THỐNG, OOP DOMAIN & CONCURRENCY CONTROL `MỤC TIÊU: 2.5 ĐIỂM`
**Mục tiêu:** Đảm bảo tính hướng đối tượng chặt chẽ, mở rộng linh hoạt và xử lý an toàn tuyệt đối khi nhiều bidder đặt giá đồng thời.

* **Khởi tạo dự án & Kiến trúc:** Khởi tạo Maven project theo mô hình phân tầng chuẩn MVC Server; thiết lập coding convention theo Google Java Style Guide.
* **Xây dựng cây kế thừa OOP (Yêu cầu 3.3):**
  * `Entity` (Abstract base class): chứa `id`, `createdAt`.
  * `User` (Abstract) $\rightarrow$ `Bidder`, `Seller`, `Admin` (Đa hình hóa quyền hạn và vai trò).
  * `Item` (Abstract) $\rightarrow$ `Electronics`, `Art`, `Vehicle` (Áp dụng Encapsulation, Polymorphism qua phương thức `getSpecificDetails()`, `calculateShipping()`).
  * Áp dụng **Factory Method Pattern** để sinh đối tượng `Item` tương ứng.
* **Quản lý vòng đời phiên đấu giá (Auction State Machine - 3.1.4):**
  * Điều khiển chuyển trạng thái: `OPEN` $\rightarrow$ `RUNNING` $\rightarrow$ `FINISHED` $\rightarrow$ `PAID` / `CANCELED`.
  * Tự động đóng phiên khi hết thời gian, xác định người chiến thắng và mức giá cuối cùng.
* **Xử lý đấu giá đồng thời (Concurrent Bidding - 3.2.2):**
  * Cài đặt cơ chế đồng bộ luồng: Dùng `ReentrantLock` hoặc `synchronized` trên từng đối tượng phiên đấu giá.
  * Triệt tiêu hoàn toàn các lỗi: *Lost update*, *Giá bị rollback*, *Hai người cùng thắng một mức giá*.

---

### THÀNH VIÊN 2: HẠ TẦNG MẠNG SOCKET, REALTIME OBSERVER & THUẬT TOÁN NÂNG CAO `MỤC TIÊU: 2.5 ĐIỂM`
**Mục tiêu:** Xây dựng kênh truyền thông Client-Server tin cậy, thông báo tức thời không dùng polling và cài đặt các giải thuật đấu giá cao cấp.

* **Hạ tầng Socket Client - Server (Yêu cầu 3.4):**
  * Xây dựng `AuctionServer` sử dụng `ServerSocket` và `ThreadPoolExecutor` để quản lý hàng nghìn kết nối song song qua `ClientHandler`.
  * Thiết kế cấu trúc bản tin JSON (sử dụng thư viện Gson hoặc Jackson) cho các hành động: `LOGIN`, `GET_AUCTIONS`, `PLACE_BID`, `NOTIFY_UPDATE`, `AUTO_BID`.
* **Cơ chế Realtime Broadcast (Observer Pattern nâng cao - 3.2.4):**
  * Cài đặt Observer Pattern tại Server: Quản lý danh sách các client đang mở xem một phiên đấu giá cụ thể.
  * Khi có lượt bid hợp lệ, Server ngay lập tức bắn gói tin cập nhật tới tất cả client liên quan mà **không dùng polling liên tục**. Đảm bảo Thread-safe khi duyệt danh sách observer.
* **Thuật toán Auto-Bidding (Đấu giá tự động - 3.2.1 - Điểm cộng 0.5đ):**
  * Cho phép bidder thiết lập `maxBid` và `increment`.
  * Sử dụng hàng đợi ưu tiên `PriorityQueue` để tự động so khớp giá khi có bid mới, tuân thủ nguyên tắc không vượt quá `maxBid` và ưu tiên người đăng ký trước.
* **Thuật toán Anti-sniping (Gia hạn phiên cuối - 3.2.3 - Điểm cộng 0.5đ):**
  * Kiểm tra nếu có bid mới trong $X$ giây cuối $\rightarrow$ Tự động dời thời gian kết thúc thêm $Y$ giây và đồng bộ thời gian mới cho toàn bộ client.

---

### THÀNH VIÊN 3: GIAO DIỆN CLIENT JAVAFX & TRỰC QUAN HÓA BIẾN ĐỘNG GIÁ `MỤC TIÊU: 2.5 ĐIỂM`
**Mục tiêu:** Đem lại trải nghiệm sàn đấu giá trực quan, tương tác mượt mà, đồng bộ với Server và trực quan hóa dữ liệu theo thời gian thực.

* **Kiến trúc JavaFX Client (Yêu cầu 3.1.6):**
  * Xây dựng cấu trúc Client chuẩn MVC với Scene Builder, FXML và CSS đồng bộ.
  * Màn hình Danh sách phiên: Hiển thị dạng thẻ (Card View), có ô tìm kiếm, bộ lọc trạng thái và danh mục sản phẩm.
  * Màn hình Chi tiết sản phẩm: Hiển thị hình ảnh, mô tả và các thuộc tính đặc thù của từng loại mặt hàng (Điện tử, Nghệ thuật, Phương tiện).
* **Màn hình Đấu giá trực tiếp (Live Bidding Room):**
  * Đồng hồ đếm ngược thời gian phiên (Countdown Timer) chính xác theo giây.
  * Bảng hiển thị giá cao nhất hiện tại, tên người dẫn đầu và lịch sử các lượt đặt giá gần nhất.
  * Khu vực đặt giá: Nút gợi ý bước giá nhanh (+50k, +100k), form kích hoạt Auto-Bid.
* **Client Observer Listener & An toàn đa luồng giao diện:**
  * Lắng nghe gói tin từ Socket ở luồng nền và cập nhật giao diện người dùng an toàn thông qua `Platform.runLater()`, chống lag/treo ứng dụng.
* **Bid History Visualization - Realtime Price Curve (3.2.5 - Điểm cộng 0.5đ):**
  * Tích hợp `LineChart` của JavaFX: Trục X (thời gian timestamp), Trục Y (mức giá đấu).
  * Mỗi khi nhận thông báo bid hợp lệ mới, biểu đồ lập tức cập nhật điểm mới và nối đường giá tự động mà không cần refresh giao diện.

---

### THÀNH VIÊN 4: DATABASE PERSISTENCE, ADMIN GUI & KIỂM THỬ TỰ ĐỘNG (QA / CI-CD) `MỤC TIÊU: 2.5 ĐIỂM`
**Mục tiêu:** Đảm bảo dữ liệu bền vững, hoàn thiện các luồng quản trị tài khoản/sản phẩm và bảo chứng chất lượng mã nguồn bằng Unit Test.

* **Tầng lưu trữ dữ liệu (Database & DAO Layer - Yêu cầu 3.4):**
  * Thiết kế Schema CSDL chuẩn hóa (SQLite hoặc MySQL): các bảng `users`, `items`, `auctions`, `bid_transactions`.
  * Viết các lớp DAO: `UserDAO`, `ItemDAO`, `AuctionDAO`, `BidDAO`.
  * Áp dụng **Singleton Pattern** cho lớp quản lý kết nối CSDL (`DBConnection`). Chỉ duy nhất Server truy cập CSDL, Client không được kết nối trực tiếp.
* **Giao diện Quản trị & Xác thực người dùng (3.1.1 & 3.1.2):**
  * Màn hình Đăng ký / Đăng nhập, mã hóa mật khẩu, phân luồng giao diện theo quyền (`Bidder`, `Seller`, `Admin`).
  * Màn hình Quản lý sản phẩm dành cho Seller: Thêm mới sản phẩm, sửa thông tin, chọn thời gian bắt đầu/kết thúc.
  * Màn hình Quản trị hệ thống (Admin): Khóa tài khoản vi phạm, hủy phiên bất thường.
* **Kiểm thử tự động - Unit Test JUnit 5 (Yêu cầu 3.5):**
  * Viết test case kiểm tra tính hợp lệ của giá đấu (bid thấp hơn giá hiện tại, bid sau khi đóng phiên).
  * Viết bài test đa luồng giả lập nhiều bidder cùng đặt giá song song để kiểm tra tính toàn vẹn của logic concurrency do TV1 viết.
* **Thiết lập CI/CD & Báo cáo kỹ thuật:**
  * Cấu hình GitHub Actions (`.github/workflows/ci.yml`) tự động build và chạy JUnit test khi tạo Pull Request.
  * Tổng hợp tài liệu báo cáo: Vẽ sơ đồ lớp (Class Diagram), sơ đồ kiến trúc và bảng phân công đóng góp.

---
