package com.banque.virement.service;

import java.math.BigDecimal;

/**
 * Passerelle vers le Core Banking System / le systeme interbancaire de compensation.
 * En production, l'implementation reelle appellerait le SI de la banque ;
 * ici, une implementation simulee valide et journalise l'ordre.
 */
public interface CoreBankingGateway {

    /**
     * Route un ordre de virement externe (vers une autre institution).
     *
     * @return reference du SI central (ex : ACK-123456) si l'ordre est accepte
     * @throws IllegalStateException si le SI refuse l'ordre
     */
    String routeExternalTransfer(String transferReference, String debitIban, String creditIban,
                                 BigDecimal amount, String currency, String motif);
}
