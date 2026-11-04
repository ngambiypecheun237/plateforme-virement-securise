package com.banque.virement.domain.enums;

public enum FraudAlertStatus {
    OUVERTE,       // Detectee, en attente de revue
    EN_REVUE,      // En cours d'analyse par un agent
    VALIDEE,       // Validee manuellement par un agent (virement autorise)
    ANNULEE        // Annulee par un agent (virement rejete)
}
