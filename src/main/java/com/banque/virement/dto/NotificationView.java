package com.banque.virement.dto;

import com.banque.virement.domain.entity.Notification;

import java.time.Instant;

public record NotificationView(Long id, String type, String title, String message,
                               boolean read, Instant createdAt) {

    public static NotificationView from(Notification n) {
        return new NotificationView(n.getId(), n.getType().name(), n.getTitle(), n.getMessage(),
                n.isReadFlag(), n.getCreatedAt());
    }
}
