package com.banque.virement.service;

import com.banque.virement.domain.entity.OtpChallenge;
import com.banque.virement.domain.entity.User;
import com.banque.virement.domain.enums.NotificationType;
import com.banque.virement.domain.enums.OtpPurpose;
import com.banque.virement.dto.OtpResponse;
import com.banque.virement.exception.BusinessException;
import com.banque.virement.repository.OtpChallengeRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Gestion des codes OTP (One-Time Password) pour l'authentification forte :
 * validation de virements, approbation B2B, ajout/suppression de beneficiaires.
 *
 * En production, le code serait envoye par SMS via un fournisseur ; ici il est
 * delivre via une notification in-app et un email simule. En mode developpement
 * (app.dev-mode=true), le code est renvoye dans la reponse pour faciliter les tests.
 */
@Service
public class OtpService {

    private final OtpChallengeRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final NotificationService notificationService;
    private final MailService mailService;

    @Value("${app.otp.ttl-minutes:5}")
    private int ttlMinutes;

    @Value("${app.otp.max-attempts:5}")
    private int maxAttempts;

    @Value("${app.dev-mode:false}")
    private boolean devMode;

    private final SecureRandom random = new SecureRandom();

    public OtpService(OtpChallengeRepository repository, PasswordEncoder passwordEncoder,
                      NotificationService notificationService, MailService mailService) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.notificationService = notificationService;
        this.mailService = mailService;
    }

    @Transactional
    public OtpResponse requestOtp(User user, OtpPurpose purpose, String context) {
        // Invalide les anciens codes non consommes pour ce contexte
        List<OtpChallenge> existing = repository.findByUserIdAndPurposeAndConsumedFalse(user.getId(), purpose);
        for (OtpChallenge old : existing) {
            if (context == null ? old.getContext() == null : context.equals(old.getContext())) {
                old.setConsumed(true);
            }
        }
        repository.saveAll(existing);

        String code = String.format("%06d", random.nextInt(1_000_000));
        OtpChallenge challenge = OtpChallenge.builder()
                .user(user)
                .codeHash(passwordEncoder.encode(code))
                .purpose(purpose)
                .context(context)
                .expiresAt(Instant.now().plus(ttlMinutes, ChronoUnit.MINUTES))
                .attempts(0)
                .maxAttempts(maxAttempts)
                .consumed(false)
                .build();
        repository.save(challenge);

        String message = "Votre code de verification est " + code
                + " (valide " + ttlMinutes + " minutes). Ne le partagez avec personne.";
        notificationService.create(user, NotificationType.SECURITE, "Code de verification", message);
        mailService.send(user.getEmail(), "[Banque] Code de verification", message);

        return new OtpResponse(
                "Code envoye par notification et email",
                ttlMinutes * 60L,
                devMode ? code : null);
    }

    @Transactional
    public void verify(User user, OtpPurpose purpose, String context, String code) {
        OtpChallenge challenge = repository
                .findTopByUserIdAndPurposeAndContextAndConsumedFalseOrderByCreatedAtDesc(user.getId(), purpose, context)
                .orElseThrow(() -> new BusinessException("Aucun code de verification en cours. Veuillez en demander un nouveau."));

        if (Instant.now().isAfter(challenge.getExpiresAt())) {
            challenge.setConsumed(true);
            repository.save(challenge);
            throw new BusinessException("Code expire. Veuillez en demander un nouveau.");
        }
        if (challenge.getAttempts() >= challenge.getMaxAttempts()) {
            challenge.setConsumed(true);
            repository.save(challenge);
            throw new BusinessException("Nombre maximal de tentatives atteint. Demandez un nouveau code.");
        }
        if (!passwordEncoder.matches(code, challenge.getCodeHash())) {
            challenge.setAttempts(challenge.getAttempts() + 1);
            repository.save(challenge);
            throw new BusinessException("Code incorrect (" + (challenge.getMaxAttempts() - challenge.getAttempts()) + " tentative(s) restante(s)).");
        }
        challenge.setConsumed(true);
        repository.save(challenge);
    }
}
