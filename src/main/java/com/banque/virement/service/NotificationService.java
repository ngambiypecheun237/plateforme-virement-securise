package com.banque.virement.service;

import com.banque.virement.domain.entity.Notification;
import com.banque.virement.domain.entity.User;
import com.banque.virement.domain.enums.NotificationType;
import com.banque.virement.exception.ResourceNotFoundException;
import com.banque.virement.repository.NotificationRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {

    private final NotificationRepository repository;
    private final MailService mailService;

    public NotificationService(NotificationRepository repository, MailService mailService) {
        this.repository = repository;
        this.mailService = mailService;
    }

    @Transactional
    public Notification create(User user, NotificationType type, String title, String message) {
        Notification notification = Notification.builder()
                .user(user)
                .type(type)
                .title(title)
                .message(message)
                .build();
        return repository.save(notification);
    }

    @Async
    public void createAndEmailAsync(User user, NotificationType type, String title, String message) {
        create(user, type, title, message);
        mailService.send(user.getEmail(), "[Banque] " + title, message);
    }

    public Page<Notification> listFor(User user, Pageable pageable) {
        return repository.findByUserIdOrderByCreatedAtDesc(user.getId(), pageable);
    }

    public long unreadCount(User user) {
        return repository.countByUserIdAndReadFlagFalse(user.getId());
    }

    @Transactional
    public Notification markRead(User user, Long id) {
        Notification notification = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Notification introuvable"));
        if (!notification.getUser().getId().equals(user.getId())) {
            throw new ResourceNotFoundException("Notification introuvable");
        }
        notification.setReadFlag(true);
        return repository.save(notification);
    }

    @Transactional
    public int markAllRead(User user) {
        Page<Notification> page = repository.findByUserIdOrderByCreatedAtDesc(user.getId(), Pageable.ofSize(500));
        int count = 0;
        for (Notification n : page.getContent()) {
            if (!n.isReadFlag()) {
                n.setReadFlag(true);
                count++;
            }
        }
        repository.saveAll(page.getContent());
        return count;
    }
}
