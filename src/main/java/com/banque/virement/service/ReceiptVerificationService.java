package com.banque.virement.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Code de verification imprime sur les recus de virement :
 * HMAC-SHA256(reference + "|" + montant, secret) tronque — permet a un tiers
 * (agent en agence, client) de verifier l'authenticite d'un recu imprime
 * via l'endpoint public /api/v1/public/verify, sans exposer de donnees.
 */
@Service
public class ReceiptVerificationService {

    private static final String ALGO = "HmacSHA256";
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // sans I, O, 0, 1

    private final SecretKeySpec key;

    public ReceiptVerificationService(@Value("${app.jwt.secret}") String secretBase64) {
        byte[] secret;
        try {
            secret = Base64.getDecoder().decode(secretBase64);
        } catch (IllegalArgumentException e) {
            secret = secretBase64.getBytes(StandardCharsets.UTF_8);
        }
        this.key = new SecretKeySpec(secret, ALGO);
    }

    public String codeFor(String reference, String amount) {
        String normalizedAmount = normalizeAmount(amount);
        try {
            Mac mac = Mac.getInstance(ALGO);
            mac.init(key);
            byte[] digest = mac.doFinal((reference + "|" + normalizedAmount).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(10);
            // Encodage base32-like sur 10 caracteres a partir des octets du digest
            for (int i = 0; i < 10; i++) {
                out.append(ALPHABET.charAt((digest[i] & 0xFF) % ALPHABET.length()));
            }
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Impossible de generer le code de verification", e);
        }
    }

    public boolean matches(String reference, String amount, String code) {
        if (code == null) {
            return false;
        }
        String expected = codeFor(reference, amount);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                code.trim().toUpperCase().getBytes(StandardCharsets.UTF_8));
    }

    private static String normalizeAmount(String amount) {
        try {
            return new BigDecimal(amount.replace(" ", "").replace(",", "."))
                    .stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            return amount;
        }
    }
}
