package com.banque.virement.domain.enums;

public enum BatchStatus {
    EN_COURS,    // Traitement asynchrone en cours
    TERMINE,     // Toutes les lignes traitees avec succes
    PARTIEL,     // Certaines lignes en echec
    ECHOUE       // Echec global (fichier illisible)
}
