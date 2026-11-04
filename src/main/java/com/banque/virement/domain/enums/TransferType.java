package com.banque.virement.domain.enums;

public enum TransferType {
    VIREMENT_UNITAIRE_INTERNE,   // Entre deux comptes de la banque
    VIREMENT_UNITAIRE_EXTERNE,   // Vers une autre institution financiere
    VIREMENT_MASSE               // Remise entreprise (salaires, fournisseurs)
}
