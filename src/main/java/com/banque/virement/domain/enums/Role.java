package com.banque.virement.domain.enums;

public enum Role {
    CLIENT,           // Particulier (B2C)
    ENTREPRISE,       // Mandataire d'une entreprise (B2B)
    AGENT_BANCAIRE,   // Agent de la banque (supervision, alertes fraude)
    ADMIN             // Administrateur de la plateforme
}
