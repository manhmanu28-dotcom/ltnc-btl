package com.auction.server.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * PasswordUtil: Tiện ích mã hóa mật khẩu an toàn sử dụng thuật toán SHA-256 kèm Salt.
 * Đáp ứng Yêu cầu 3.1.1 (Mã hóa mật khẩu người dùng trước khi lưu trữ vào Database).
 */
public final class PasswordUtil {

    private static final int SALT_LENGTH = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordUtil() {
        // Utility class
    }

    /**
     * Tạo chuỗi Salt ngẫu nhiên bảo mật.
     */
    public static String generateSalt() {
        byte[] salt = new byte[SALT_LENGTH];
        RANDOM.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    /**
     * Băm mật khẩu kèm Salt: SHA-256(password + salt).
     * Kết quả trả về định dạng "salt:hash" để thuận tiện lưu trữ và xác thực.
     */
    public static String hashPassword(String plainPassword) {
        if (plainPassword == null || plainPassword.isEmpty()) {
            throw new IllegalArgumentException("Mật khẩu không được để trống!");
        }
        String salt = generateSalt();
        String hash = computeSha256(plainPassword, salt);
        return salt + ":" + hash;
    }

    /**
     * Băm mật khẩu với Salt chỉ định trước.
     */
    public static String hashPasswordWithSalt(String plainPassword, String salt) {
        if (plainPassword == null || salt == null) {
            throw new IllegalArgumentException("Mật khẩu và salt không được để trống!");
        }
        return salt + ":" + computeSha256(plainPassword, salt);
    }

    /**
     * Kiểm tra mật khẩu người dùng nhập vào có khớp với hash đã lưu hay không.
     */
    public static boolean verifyPassword(String plainPassword, String storedHash) {
        if (plainPassword == null || storedHash == null) {
            return false;
        }

        // Trường hợp hash theo chuẩn "salt:hash"
        if (storedHash.contains(":")) {
            String[] parts = storedHash.split(":", 2);
            String salt = parts[0];
            String expectedHash = parts[1];
            String actualHash = computeSha256(plainPassword, salt);
            return MessageDigest.isEqual(
                    expectedHash.getBytes(StandardCharsets.UTF_8),
                    actualHash.getBytes(StandardCharsets.UTF_8)
            );
        }

        // Hỗ trợ tương thích ngược nếu mật khẩu lưu dạng thô hoặc hash đơn giản
        return plainPassword.equals(storedHash);
    }

    private static String computeSha256(String data, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Base64.getDecoder().decode(salt));
            byte[] encodedhash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encodedhash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Không tìm thấy thuật toán SHA-256", e);
        }
    }
}

