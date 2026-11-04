package com.banque.virement.service;

import com.banque.virement.domain.entity.AuditLog;
import com.banque.virement.repository.AuditLogRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {

    private final AuditLogRepository repository;

    public AuditService(AuditLogRepository repository) {
        this.repository = repository;
    }

    /**
     * Ecrit une entree immuable dans le journal d'audit.
     * Executee dans une nouvelle transaction pour ne jamais etre perdue ni bloquer le metier.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(String userEmail, String action, String entityType, String entityId, String details, String ipAddress) {
        repository.save(AuditLog.builder()
                .userEmail(userEmail == null ? "SYSTEM" : userEmail)
                .action(action)
                .entityType(entityType)
                .entityId(entityId)
                .details(details)
                .ipAddress(ipAddress == null ? "-" : ipAddress)
                .build());
    }

    @Async
    public void logAsync(String userEmail, String action, String entityType, String entityId, String details, String ipAddress) {
        log(userEmail, action, entityType, entityId, details, ipAddress);
    }
}
