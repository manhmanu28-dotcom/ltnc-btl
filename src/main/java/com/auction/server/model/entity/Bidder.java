package com.auction.server.model.entity;

/**
 * Bidder: Người tham gia đấu giá (Kế thừa từ User).
 * Quản lý số dư và tiền đang tạm giữ (escrow hold) một cách an toàn đa luồng (Thread-safe),
 * đảm bảo một Bidder không thể dùng cùng một khoản tiền để thắng nhiều phiên đồng thời.
 */
public class Bidder extends User {
    private static final double EPSILON = 1e-6;

    private final transient Object balanceLock = new Object();
    private double balance; // Tổng số dư tài khoản
    private double heldBalance; // Số tiền đang tạm giữ cho các phiên đang dẫn đầu

    public Bidder(String id, String username, String passwordHash, String email, double initialBalance) {
        super(id, username, passwordHash, email);
        if (!Double.isFinite(initialBalance)) {
            throw new IllegalArgumentException("Số dư khởi tạo không hợp lệ!");
        }
        this.balance = Math.max(0.0, initialBalance);
        this.heldBalance = 0.0;
    }

    @Override
    public String getRoleName() {
        return "BIDDER";
    }

    @Override
    public boolean canBid() {
        return true; // Người đấu giá ĐƯỢC đặt giá
    }

    @Override
    public boolean canCreateAuction() {
        return false; // Người đấu giá KHÔNG ĐƯỢC tự tạo phiên bán
    }

    @Override
    public String printInfo() {
        synchronized (balanceLock) {
            return String.format("%s | Số dư: %,.0f VNĐ (Khả dụng: %,.0f VNĐ)",
                    super.printInfo(), balance, getAvailableBalanceInternal());
        }
    }

    public double getBalance() {
        synchronized (balanceLock) {
            return balance;
        }
    }

    public double getHeldBalance() {
        synchronized (balanceLock) {
            return heldBalance;
        }
    }

    public double getAvailableBalance() {
        synchronized (balanceLock) {
            return getAvailableBalanceInternal();
        }
    }

    public void setBalance(double balance) {
        if (!Double.isFinite(balance) || balance < 0.0) {
            throw new IllegalArgumentException("Số dư tài khoản phải là số hữu hạn không âm!");
        }
        synchronized (balanceLock) {
            if (balance + EPSILON < heldBalance) {
                throw new IllegalArgumentException(String.format(
                        "Số dư mới (%,.0f VNĐ) không thể nhỏ hơn số tiền đang tạm giữ (%,.0f VNĐ)!",
                        balance, heldBalance));
            }
            this.balance = balance;
        }
    }

    /**
     * Tạm giữ thêm một khoản tiền từ số dư khả dụng một cách nguyên tử (Thread-safe).
     *
     * @param amount Số tiền cần tạm giữ thêm
     * @return true nếu đủ số dư khả dụng và đã tạm giữ thành công
     */
    public boolean tryHold(double amount) {
        if (!Double.isFinite(amount) || amount < 0.0) {
            return false;
        }
        synchronized (balanceLock) {
            if (getAvailableBalanceInternal() + EPSILON < amount) {
                return false;
            }
            heldBalance += amount;
            return true;
        }
    }

    /**
     * Giải phóng khoản tiền đang tạm giữ khi bị người khác vượt giá hoặc phiên bị hủy.
     */
    public void releaseHold(double amount) {
        if (!Double.isFinite(amount) || amount <= 0.0) {
            return;
        }
        synchronized (balanceLock) {
            heldBalance = Math.max(0.0, heldBalance - amount);
        }
    }

    /**
     * Quyết toán khoản tiền đang tạm giữ khi người thắng thanh toán phiên (FINISHED -> PAID).
     */
    public void settleHold(double amount) {
        if (!Double.isFinite(amount) || amount <= 0.0) {
            return;
        }
        synchronized (balanceLock) {
            double released = Math.min(amount, heldBalance);
            heldBalance = Math.max(0.0, heldBalance - released);
            balance = Math.max(0.0, balance - amount);
        }
    }

    private double getAvailableBalanceInternal() {
        return Math.max(0.0, balance - heldBalance);
    }
}
