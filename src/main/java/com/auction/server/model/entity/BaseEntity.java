package com.auction.server.model.entity;

import java.time.LocalDateTime;

/**
 * BaseEntity: Lớp trừu tượng cơ sở (Abstraction)
 * Mọi Entity trong hệ thống đều kế thừa lớp này để dùng chung id và createdAt.
 */
public abstract class BaseEntity {
    private final String id;
    private final LocalDateTime createdAt;

    public BaseEntity(String id) {
        this(id, LocalDateTime.now());
    }

    public BaseEntity(String id, LocalDateTime createdAt) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("ID không được để trống!");
        }
        this.id = id.trim();
        this.createdAt = (createdAt != null) ? createdAt : LocalDateTime.now();
    }

    /**
     * Phương thức đa hình hiển thị thông tin đối tượng (Yêu cầu 3.3.2).
     */
    public abstract String printInfo();

    public String getId() {
        return id;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
