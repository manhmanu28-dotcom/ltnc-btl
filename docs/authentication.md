# Đăng ký và đăng nhập

Client mở màn hình Đăng nhập / Đăng ký trước danh sách phiên. Đăng ký cần tên
đăng nhập, email, mật khẩu và xác nhận mật khẩu. Tài khoản mới có vai trò BIDDER,
số dư ban đầu 0; không được chọn ADMIN hoặc tự thiết lập số dư từ client.

Nhấn nút hoặc liên kết Tạo tài khoản để mở form đăng ký. Sau khi tạo tài khoản
thành công, ứng dụng quay về form Đăng nhập để nhập lại mật khẩu.
Đăng nhập thành công sẽ mở danh sách phiên. Nút Đăng xuất quay lại màn hình tài khoản
và đóng kết nối đăng nhập cũ.

## Chạy thử trên Windows

Trong thư mục dự án, mở hai terminal PowerShell. Script dùng JDK trong JAVA_HOME
và các thư viện JavaFX 17.0.8, SQLite JDBC 3.45.1.0, SLF4J 1.7.36 đã tải trong
Maven cache. Nếu thiếu thư viện, cần reload Maven project trong IDE trước.

Terminal thứ nhất:

```powershell
.\run.ps1 Server
```

Terminal thứ hai:

```powershell
.\run.ps1 Client
```

Nếu PowerShell chặn script, có thể chạy từng lệnh qua
`powershell -ExecutionPolicy Bypass -File .\run.ps1 Server` và tương tự cho Client;
tham số này chỉ áp dụng cho tiến trình vừa mở, không thay đổi chính sách hệ thống.

Giữ server `127.0.0.1`, cổng `8888`. Đăng ký một tài khoản, đăng nhập, đăng xuất,
rồi dừng và chạy lại server/client để kiểm tra tài khoản vẫn đăng nhập được.
Phải khởi động lại server đang chạy phiên bản cũ để dùng giao thức xác thực mới.

## Dữ liệu và luồng xử lý

Server lưu tài khoản trong file `auction.db` tại thư mục làm việc (script luôn chạy
từ thư mục dự án). Không xóa file này nếu muốn giữ tài khoản. File database được
loại khỏi Git. Mật khẩu được băm PBKDF2-HMAC-SHA256 với salt riêng trước khi lưu.

`AuthenticationController` → `AuthenticationClientService` → Socket
`REGISTER` / `LOGIN` → `ClientHandler` → `AuthenticationService` → `UserDAO` → SQLite.

Đăng ký không tự đăng nhập. Đăng nhập thành công giữ kết nối để tải danh sách và
phục vụ thao tác sau này. Thao tác mạng chạy ở luồng nền; Task cập nhật UI trên
luồng JavaFX. Tài khoản được lưu lại, nhưng ứng dụng không lưu mật khẩu trên client
và không tự đăng nhập khi mở lại.

## Kiểm thử

`AuthenticationFlowTest` dùng database tạm và Socket thật để kiểm tra tài khoản
tồn tại sau khi khởi động lại server/database, tên trùng không ghi đè, mật khẩu sai,
dữ liệu đầu vào không hợp lệ, tài khoản bị khóa và không thể bỏ qua đăng nhập bằng userId.
