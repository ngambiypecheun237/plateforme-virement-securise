package com.banque.virement.domain.enums;

public enum TransferStatus {
    EN_ATTENTE_CONFIRMATION,  // Cree (B2C), en attente du code OTP
    EN_ATTENTE_APPROBATION,   // B2B, en attente des signatures des mandataires
    PROGRAMME,                // Valide mais planifie a une date future (virement differe)
    EXECUTE,                  // Virement execute avec succes
    REJETE,                   // Rejete (mandataires / administrateur)
    ECHOUE,                   // Echec d'execution (ex : solde insuffisant a l'execution)
    BLOQUE_FRAUDE,            // Bloque par le moteur anti-fraude, en attente de revue
    ANNULE                    // Annule par le client avant execution
}
