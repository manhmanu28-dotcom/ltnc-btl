package com.auction.server.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;

/**
 * PasswordUtil: Băm mật khẩu mới bằng PBKDF2-HMAC-SHA256 kèm salt ngẫu nhiên.
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
     * Định dạng: pbkdf2$iterations$salt$hash. Không lưu mật khẩu thô.
     */
    public static String hashPassword(String plainPassword) {
        if (plainPassword == null || plainPassword.isEmpty()) {
            throw new IllegalArgumentException("Mật khẩu không được để trống!");
        }
        String salt = generateSalt();
        return "pbkdf2$600000$" + salt + "$" + deriveKey(plainPassword, salt, 600000);
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

        if (storedHash.startsWith("pbkdf2$")) {
            try {
                String[] parts = storedHash.split("\\$");
                if (parts.length != 4) {
                    return false;
                }
                int iterations = Integer.parseInt(parts[1]);
                if (iterations < 1 || iterations > 1_000_000) {
                    return false;
                }
                return MessageDigest.isEqual(Base64.getDecoder().decode(parts[3]),
                        Base64.getDecoder().decode(deriveKey(plainPassword, parts[2], iterations)));
            } catch (IllegalArgumentException error) {
                return false;
            }
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

        // Không chấp nhận mật khẩu lưu dạng thô.
        return false;
    }

    private static String deriveKey(String password, String salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), Base64.getDecoder().decode(salt),
                iterations, 256);
        try {
            byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(key);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Không thể băm mật khẩu.", error);
        } finally {
            spec.clearPassword();
        }
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

