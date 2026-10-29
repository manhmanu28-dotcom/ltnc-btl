# 📘 Hướng Dẫn & Quy Tắc Làm Việc Nhóm (Team Collaboration Guide)

Để dự án không bị xung đột (conflict) code và tiến độ làm việc trơn tru, mọi thành viên vui lòng đọc kỹ và tuân thủ các quy tắc dưới đây.

---

## 1. ⚠️ Quy tắc Vàng: KHÔNG push trực tiếp lên nhánh `main`
- Nhánh `main` là nhánh sản phẩm chính, chỉ chứa code đã chạy ổn định và được review.
- Tất cả tính năng, sửa lỗi đều phải làm trên **nhánh riêng (feature branch)**, sau đó tạo **Pull Request (PR)** để gộp vào `main`.

---

## 2. 🌿 Quy ước đặt tên nhánh (Branch Naming)
Đặt tên nhánh bằng chữ thường, nối nhau bằng dấu gạch ngang `-`, theo cấu trúc:
- Thêm tính năng mới: `feature/<ten-tinh-nang>`  
  *(Ví dụ: `feature/login-ui`, `feature/game-loop`, `feature/sound-manager`)*
- Sửa lỗi: `fix/<ten-loi>`  
  *(Ví dụ: `fix/null-pointer-exception`, `fix/collision-bug`)*
- Tái cấu trúc code: `refactor/<ten-module>`  
  *(Ví dụ: `refactor/player-controller`)*
- Thêm tài liệu/báo cáo: `docs/<ten-tai-lieu>`  
  *(Ví dụ: `docs/uml-diagram`, `docs/bao-cao-btl`)*

---

## 3. 📝 Quy ước Commit Message (Conventional Commits)
Hãy viết commit rõ ràng để cả nhóm cùng hiểu bạn vừa làm gì:
- `feat: ...` -> Thêm chức năng mới (vd: `feat: add player movement control`)
- `fix: ...` -> Sửa lỗi (vd: `fix: resolve character collision with wall`)
- `refactor: ...` -> Tối ưu code mà không đổi chức năng (vd: `refactor: simplify score calculation`)
- `style: ...` -> Định dạng code, căn lề, đổi tên biến (không đổi logic)
- `docs: ...` -> Viết tài liệu, comment, báo cáo
- `test: ...` -> Viết hoặc cập nhật Unit Test

---

## 4. 🔄 Quy trình làm việc hàng ngày (Daily Workflow)

### Bước 1: Luôn cập nhật code mới nhất từ `main` trước khi làm
```bash
git checkout main
git pull origin main
```

### Bước 2: Tạo nhánh mới cho nhiệm vụ của bạn
```bash
git checkout -b feature/ten-chuc-nang
```

### Bước 3: Viết code và commit thường xuyên
```bash
git status
git add .
git commit -m "feat: mo ta ngan gon ve chuc nang vua code"
```

### Bước 4: Đẩy nhánh của bạn lên GitHub
```bash
git push -u origin feature/ten-chuc-nang
```

### Bước 5: Tạo Pull Request (PR)
1. Lên trang GitHub của nhóm: https://github.com/manhmanu28-dotcom/ltnc-btl
2. GitHub sẽ hiện nút xanh **Compare & pull request**, nhấn vào đó.
3. Điền thông tin vào mẫu PR (đã có sẵn template).
4. Gửi link PR vào nhóm chat để ít nhất 1 bạn khác review và kiểm tra.
5. Sau khi review ổn định, thực hiện **Merge pull request** vào `main`.

---

## 5. ⚡ Cách xử lý khi bị Xung Đột Code (Merge Conflict)
Nếu bạn bị conflict khi pull hoặc merge:
1. Đừng hoảng, mở các file bị conflict trong IntelliJ/VS Code.
2. Tìm các đoạn mã có `<<<<<<< HEAD`, `=======`, `>>>>>>>`.
3. Bàn bạc với bạn làm cùng phần đó để quyết định giữ dòng code nào.
4. Xóa các ký tự đánh dấu, lưu file.
5. Chạy lệnh:
   ```bash
   git add .
   git commit -m "fix: resolve merge conflicts"
   git push
   ```
