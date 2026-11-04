package com.banque.virement.controller;

import com.banque.virement.domain.entity.Notification;
import com.banque.virement.domain.entity.User;
import com.banque.virement.dto.NotificationView;
import com.banque.virement.security.CurrentUser;
import com.banque.virement.service.AuthService;
import com.banque.virement.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications", description = "Alertes et notifications in-app de l'utilisateur")
public class NotificationController {

    private final NotificationService service;
    private final AuthService authService;
    private final CurrentUser currentUser;

    public NotificationController(NotificationService service, AuthService authService, CurrentUser currentUser) {
        this.service = service;
        this.authService = authService;
        this.currentUser = currentUser;
    }

    @GetMapping
    @Operation(summary = "Mes notifications (paginees, plus recentes d'abord)")
    public ResponseEntity<Page<NotificationView>> list(@RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        User user = authService.requireActiveUser(currentUser.email());
        Page<Notification> notifications = service.listFor(user,
                PageRequest.of(page, Math.min(size, 100), Sort.by(Sort.Direction.DESC, "createdAt")));
        return ResponseEntity.ok(notifications.map(NotificationView::from));
    }

    @GetMapping("/unread-count")
    @Operation(summary = "Nombre de notifications non lues")
    public ResponseEntity<Map<String, Long>> unreadCount() {
        User user = authService.requireActiveUser(currentUser.email());
        return ResponseEntity.ok(Map.of("unread", service.unreadCount(user)));
    }

    @PostMapping("/{id}/read")
    @Operation(summary = "Marquer une notification comme lue")
    public ResponseEntity<NotificationView> read(@PathVariable Long id) {
        User user = authService.requireActiveUser(currentUser.email());
        return ResponseEntity.ok(NotificationView.from(service.markRead(user, id)));
    }

    @PostMapping("/read-all")
    @Operation(summary = "Marquer toutes mes notifications comme lues")
    public ResponseEntity<Map<String, Object>> readAll() {
        User user = authService.requireActiveUser(currentUser.email());
        int count = service.markAllRead(user);
        return ResponseEntity.ok(Map.of("marqueesLues", count));
    }
}
