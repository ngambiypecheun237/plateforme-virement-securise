package com.banque.virement;

import com.banque.virement.domain.entity.BankAccount;
import com.banque.virement.domain.entity.Transfer;
import com.banque.virement.domain.entity.User;
import com.banque.virement.domain.enums.*;
import com.banque.virement.service.ExportService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Genere reellement les documents binaires (PDF / XLSX) et verifie leur signature,
 * sans infrastructure externe.
 */
class ExportServiceTest {

    private final ExportService exportService =
            new ExportService(new com.banque.virement.service.ReceiptVerificationService(
                    java.util.Base64.getEncoder().encodeToString("cle-de-test-0123456789-0123456789-0123456789".getBytes(java.nio.charset.StandardCharsets.UTF_8))));

    private Transfer sampleTransfer() {
        User user = User.builder().id(1L).firstName("Jean").lastName("MARTIN").email("jean@client.cm").build();
        BankAccount account = BankAccount.builder().id(10L).iban("CM3810000001123456789012345")
                .type(AccountType.COURANT).balance(new BigDecimal("1000000"))
                .status(AccountStatus.ACTIVE).build();
        return Transfer.builder()
                .id(1L).reference("TRF-TEST-0001")
                .type(TransferType.VIREMENT_UNITAIRE_EXTERNE)
                .status(TransferStatus.EXECUTE)
                .sourceAccount(account)
                .amount(new BigDecimal("150000")).currency("XAF")
                .motif("Loyer novembre")
                .createdBy(user).createdAt(Instant.now())
                .executedAt(Instant.now())
                .build();
    }

    @Test
    void avisPdfEstUnFichierPdfValide() {
        byte[] pdf = exportService.receiptPdf(sampleTransfer());
        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
    }

    @Test
    void exportPdfListeEstUnFichierPdfValide() {
        byte[] pdf = exportService.historyPdf(List.of(sampleTransfer()));
        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
    }

    @Test
    void exportXlsxEstUnClasseurOffice() {
        byte[] xlsx = exportService.historyXlsx(List.of(sampleTransfer()));
        assertThat(xlsx).isNotEmpty();
        // XLSX = archive ZIP, signature PK\u0003\u0004
        assertThat(xlsx[0]).isEqualTo((byte) 'P');
        assertThat(xlsx[1]).isEqualTo((byte) 'K');
    }
}
