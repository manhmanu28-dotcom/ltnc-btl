package com.auction.server.service;

import com.auction.server.dao.UserDAO;
import com.auction.server.database.DBConnection;
import com.auction.server.model.entity.Bidder;
import com.auction.server.model.entity.User;
import com.auction.server.util.PasswordUtil;

import java.util.UUID;

/** Xác thực tài khoản được lưu trong SQLite, chỉ được gọi tại server. */
public class AuthenticationService {

    public User register(String username, String password, String email) {
        username = validateUsername(username);
        if (password == null || password.length() < 8 || password.length() > 128) {
            throw new IllegalArgumentException("Mật khẩu phải dài từ 8 đến 128 ký tự.");
        }
        if (email == null || email.length() > 254
                || !email.trim().matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
            throw new IllegalArgumentException("Email không hợp lệ.");
        }
        // Tính hash trước khi giữ khóa database để không chặn các truy vấn khác.
        String hash = PasswordUtil.hashPassword(password);
        DBConnection db = DBConnection.getInstance();
        synchronized (db) {
            UserDAO users = new UserDAO(db);
            if (users.findByUsername(username).isPresent()) {
                throw new IllegalArgumentException("Tên đăng nhập đã tồn tại.");
            }
            Bidder user = new Bidder("USER-" + UUID.randomUUID(), username, hash, email.trim(), 0);
            if (!users.create(user)) {
                throw new IllegalStateException("Không thể lưu tài khoản. Vui lòng thử lại.");
            }
            return user;
        }
    }

    public User login(String username, String password) {
        username = validateUsername(username);
        if (password == null || password.isEmpty() || password.length() > 128) {
            throw new IllegalArgumentException("Vui lòng nhập mật khẩu (tối đa 128 ký tự).");
        }
        DBConnection db = DBConnection.getInstance();
        synchronized (db) {
            return new UserDAO(db).authenticate(username, password)
                    .orElseThrow(() -> new IllegalArgumentException("Tên đăng nhập hoặc mật khẩu không đúng."));
        }
    }

    private String validateUsername(String username) {
        if (username == null || !username.trim().matches("[a-zA-Z0-9_]{3,32}")) {
            throw new IllegalArgumentException("Tên đăng nhập gồm 3–32 chữ cái không dấu, số hoặc dấu gạch dưới.");
        }
        return username.trim();
    }
}
