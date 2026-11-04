package com.banque.virement.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Envoi d'emails. En l'absence de serveur SMTP configure, le message est journalise
 * (simulation) au lieu de faire echouer l'operation metier.
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final Optional<JavaMailSender> mailSender;

    @Value("${spring.mail.host:localhost}")
    private String mailHost;

    public MailService(Optional<JavaMailSender> mailSender) {
        this.mailSender = mailSender;
    }

    public void send(String to, String subject, String body) {
        try {
            if (mailSender.isPresent() && mailHost != null && !mailHost.isBlank()) {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom("ne-pas-repondre@banque.cm");
                message.setTo(to);
                message.setSubject(subject);
                message.setText(body);
                mailSender.get().send(message);
                log.info("Email envoye a {} (objet: {})", to, subject);
            } else {
                log.info("[MAIL SIMULE] A: {} | Objet: {} | Corps: {}", to, subject, body);
            }
        } catch (Exception e) {
            log.warn("Echec de l'envoi de l'email a {} : {}", to, e.getMessage());
        }
    }
}
