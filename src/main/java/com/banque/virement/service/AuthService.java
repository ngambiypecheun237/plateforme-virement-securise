package com.banque.virement.service;

import com.banque.virement.domain.entity.User;
import com.banque.virement.domain.enums.Role;
import com.banque.virement.dto.AuthResponse;
import com.banque.virement.dto.LoginRequest;
import com.banque.virement.dto.RegisterRequest;
import com.banque.virement.exception.BusinessException;
import com.banque.virement.exception.UnauthorizedException;
import com.banque.virement.repository.UserRepository;
import com.banque.virement.security.JwtService;
import com.banque.virement.domain.enums.NotificationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditService auditService;
    private final NotificationService notificationService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       JwtService jwtService, AuditService auditService,
                       NotificationService notificationService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.auditService = auditService;
        this.notificationService = notificationService;
    }

    @Transactional
    public User register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email().toLowerCase())) {
            throw new BusinessException("Un compte existe deja avec cet email");
        }
        User user = User.builder()
                .firstName(request.firstName())
                .lastName(request.lastName())
                .email(request.email().toLowerCase())
                .phone(request.phone())
                .passwordHash(passwordEncoder.encode(request.password()))
                .role(Role.CLIENT)
                .active(true)
                .build();
        user = userRepository.save(user);
        auditService.log(user.getEmail(), "REGISTER", "User", String.valueOf(user.getId()),
                "Inscription d'un nouveau client particulier", null);
        return user;
    }

    @Transactional
    public AuthResponse login(LoginRequest request, String ipAddress) {
        String email = request.email().toLowerCase();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> {
                    auditService.log(email, "LOGIN_ECHEC", "User", null, "Utilisateur inconnu", ipAddress);
                    return new UnauthorizedException("Identifiants invalides");
                });

        if (!user.isActive()) {
            auditService.log(email, "LOGIN_ECHEC", "User", String.valueOf(user.getId()), "Compte desactive", ipAddress);
            throw new UnauthorizedException("Compte desactive. Contactez votre banque.");
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            auditService.log(email, "LOGIN_ECHEC", "User", String.valueOf(user.getId()), "Mot de passe incorrect", ipAddress);
            throw new UnauthorizedException("Identifiants invalides");
        }

        String token = jwtService.generateToken(user);
        auditService.log(email, "LOGIN_SUCCES", "User", String.valueOf(user.getId()), "Connexion reussie", ipAddress);
        notificationService.create(user, NotificationType.CONNEXION, "Nouvelle connexion",
                "Une connexion a votre compte a ete enregistree depuis " + (ipAddress == null ? "un appareil inconnu" : ipAddress) + ".");

        return new AuthResponse(token, "Bearer", jwtService.getExpirationMs() / 1000,
                user.getEmail(), user.getFullName(), user.getRole().name());
    }

    public User requireActiveUser(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UnauthorizedException("Utilisateur introuvable"));
        if (!user.isActive()) {
            throw new UnauthorizedException("Compte desactive");
        }
        return user;
    }
}
