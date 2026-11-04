package com.banque.virement;

import com.banque.virement.util.IbanUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class IbanUtilsTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "CM3810000001123456789012345",
            "CM6010000009876543210987654",
            "CM3420000005554443332221111",
            "CM7630000007778889990001122",
            "FR4812345678901234567890123",
            "FR8330006000504812345678901",
            "GA7410000010000000000000123"
    })
    void ibanValidesSontAcceptes(String iban) {
        assertThat(IbanUtils.isValid(iban)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "CM9910000001123456789012345",   // cle modulo 97 invalide
            "CM3810000001123456789012346",   // dernier chiffre modifie
            "FR0000000000000000000000000",   // cle 00 invalide
            "XX0010000001123456789012345",   // pays inconnu
            "CM38",                          // trop court
            "",                              // vide
            "CM38ABC",                       // caracteres invalides
    })
    void ibanInvalidesSontRejetes(String iban) {
        assertThat(IbanUtils.isValid(iban)).isFalse();
    }

    @Test
    void normalizeSupprimeEspacesEtMetEnMajuscules() {
        assertThat(IbanUtils.normalize("cm38 1000 0001 1234 5678 9012 345"))
                .isEqualTo("CM3810000001123456789012345");
    }

    @Test
    void normalizeNullRetourneNull() {
        assertThat(IbanUtils.normalize(null)).isNull();
    }
}
