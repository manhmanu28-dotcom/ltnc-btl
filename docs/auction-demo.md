# Demo giao diện phòng đấu giá

Mở PowerShell ở thư mục dự án và chạy:

```powershell
.\run.ps1 Demo
```

Hoặc chạy Client bình thường rồi bấm **Xem demo phòng đấu giá →** ở màn hình đăng nhập.
Nếu dùng Maven: `mvn javafx:run "-Djavafx.args=--demo"`.

Demo không cần server hay tài khoản. Phiên mẫu kéo dài 3 phút, giá khởi điểm
5.000.000 đồng, bước giá 500.000 đồng. Nhập số tiền bằng chữ số, hoặc bấm nút
chọn nhanh, rồi bấm **Đặt giá ngay**. Lịch sử hiển thị lượt mới nhất ở trên.
Biểu đồ **Diễn biến giá** hiển thị giá theo từng lượt trả giá (đơn vị triệu đồng),
bắt đầu từ giá khởi điểm ở lượt 0. Di chuột vào điểm để xem số tiền cụ thể.
Biểu đồ cũng cập nhật khi mô phỏng người khác trả giá và được xóa khi chơi lại.

- **Mô phỏng người khác trả giá**: tăng giá thêm một bước và đổi người dẫn đầu.
- **Thử 10 giây cuối**: đưa đồng hồ về 10 giây. Đặt giá lúc này sẽ cộng thêm 60 giây.
- **Chơi lại**: xóa các lượt trả giá và bắt đầu lại 3 phút.
- **Thoát demo**: quay về đăng nhập.

Khi hết thời gian, màn hình hiển thị kết quả và khóa đặt giá. Đây là mô phỏng
cục bộ để thử giao diện; không kết nối đấu giá giữa nhiều máy, không ghi vào
database và không thực hiện thanh toán.
