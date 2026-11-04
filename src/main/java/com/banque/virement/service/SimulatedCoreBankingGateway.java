package com.banque.virement.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Implementation simulee du SI central : journalise l'ordre et retourne un accuse de reception.
 * Remplacable par une implementation reelle (web service SOAP/REST, ISO 20022 pacs.008)
 * sans modifier le reste de l'application.
 */
@Service
public class SimulatedCoreBankingGateway implements CoreBankingGateway {

    private static final Logger log = LoggerFactory.getLogger(SimulatedCoreBankingGateway.class);

    @Override
    public String routeExternalTransfer(String transferReference, String debitIban, String creditIban,
                                        BigDecimal amount, String currency, String motif) {
        String ack = "ACK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        log.info("[CORE BANKING SIMULE] Ordre {} debite {} credite {} montant {} {} motif='{}' -> accuse {}",
                transferReference, debitIban, creditIban, amount, currency, motif, ack);
        // Simulation d'un refus si le motif contient le mot-cle "REFUS_TEST" (facilite les tests de rejet)
        if (motif != null && motif.contains("REFUS_TEST")) {
            throw new IllegalStateException("Ordre refuse par le systeme central (simulation)");
        }
        return ack;
    }
}
