package com.banque.virement.dto;

import com.banque.virement.domain.entity.TransferBatch;

import java.time.Instant;

public record BatchView(Long id, String reference, String fileName, int totalCount,
                        int successCount, int failedCount, String status, Instant createdAt,
                        Instant completedAt) {

    public static BatchView from(TransferBatch b) {
        return new BatchView(b.getId(), b.getReference(), b.getFileName(), b.getTotalCount(),
                b.getSuccessCount(), b.getFailedCount(), b.getStatus().name(),
                b.getCreatedAt(), b.getCompletedAt());
    }
}
