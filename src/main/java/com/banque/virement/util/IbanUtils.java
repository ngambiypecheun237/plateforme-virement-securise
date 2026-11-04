package com.banque.virement.util;

import java.math.BigInteger;
import java.util.Locale;

/**
 * Validation des IBAN selon la norme ISO 13616 (cle modulo 97) et controle du pays/longueur.
 */
public final class IbanUtils {

    private IbanUtils() {
    }

    /** Longueurs officielles d'IBAN pour quelques pays (liste extensible). */
    private static final java.util.Map<String, Integer> IBAN_LENGTHS = java.util.Map.ofEntries(
            java.util.Map.entry("CM", 27), java.util.Map.entry("FR", 27), java.util.Map.entry("GA", 27),
            java.util.Map.entry("CI", 28), java.util.Map.entry("SN", 28), java.util.Map.entry("CD", 25),
            java.util.Map.entry("CG", 27), java.util.Map.entry("BJ", 28), java.util.Map.entry("TG", 28),
            java.util.Map.entry("DE", 22), java.util.Map.entry("GB", 22), java.util.Map.entry("ES", 24),
            java.util.Map.entry("IT", 27), java.util.Map.entry("MA", 28), java.util.Map.entry("DZ", 24),
            java.util.Map.entry("TN", 24));

    public static boolean isValid(String iban) {
        if (iban == null) {
            return false;
        }
        String normalized = iban.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        if (normalized.length() < 15 || normalized.length() > 34) {
            return false;
        }
        String country = normalized.substring(0, 2);
        Integer expectedLength = IBAN_LENGTHS.get(country);
        if (expectedLength != null && normalized.length() != expectedLength) {
            return false;
        }
        if (!normalized.substring(0, 2).chars().allMatch(Character::isLetter)) {
            return false;
        }
        if (!normalized.substring(2, 4).chars().allMatch(Character::isDigit)) {
            return false;
        }
        // Deplacement des 4 premiers caracteres a la fin
        String rearranged = normalized.substring(4) + normalized.substring(0, 4);
        StringBuilder numeric = new StringBuilder();
        for (char c : rearranged.toCharArray()) {
            if (Character.isLetter(c)) {
                numeric.append(c - 'A' + 10);
            } else if (Character.isDigit(c)) {
                numeric.append(c);
            } else {
                return false;
            }
        }
        BigInteger remainder = new BigInteger(numeric.toString()).mod(BigInteger.valueOf(97));
        return remainder.intValue() == 1;
    }

    public static String normalize(String iban) {
        return iban == null ? null : iban.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }
}
