package com.banque.virement.service;

import com.banque.virement.domain.entity.Transfer;
import com.banque.virement.domain.entity.FraudAlert;
import com.banque.virement.domain.enums.FraudSeverity;
import com.banque.virement.repository.TransferRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/**
 * Moteur de detection des operations inhabituelles (anti-fraude).
 * Toute alerte HAUTE ou CRITIQUE bloque le virement avant debit ; les alertes
 * MOYENNE/BASSE sont journalisees et remontent au tableau de bord de l'agent bancaire.
 */
@Service
public class FraudDetectionService {

    private final TransferRepository transferRepository;

    @Value("${app.fraude.montant-critique:25000000}")
    private BigDecimal montantCritique;

    @Value("${app.fraude.montant-eleve:5000000}")
    private BigDecimal montantEleve;

    @Value("${app.fraude.montant-inhabituel-nuit:1000000}")
    private BigDecimal montantNuit;

    @Value("${app.fraude.beneficiaire-recent-jours:7}")
    private int beneficiaireRecentJours;

    @Value("${app.fraude.beneficiaire-recent-montant:2000000}")
    private BigDecimal beneficiaireRecentMontant;

    @Value("${app.fraude.velocite-max:3}")
    private int velociteMax;

    @Value("${app.fraude.velocite-fenetre-minutes:10}")
    private int velociteFenetreMinutes;

    public FraudDetectionService(TransferRepository transferRepository) {
        this.transferRepository = transferRepository;
    }

    /** Evalue le virement et retourne la liste des alertes declenchees (non persistees). */
    public List<FraudAlert> screen(Transfer transfer) {
        List<FraudAlert> alerts = new ArrayList<>();

        // 1. Montant critique
        if (transfer.getAmount().compareTo(montantCritique) >= 0) {
            alerts.add(alert(transfer, "MONTANT_CRITIQUE", FraudSeverity.CRITIQUE,
                    "Montant de " + transfer.getAmount() + " XAF superieur au seuil critique de " + montantCritique + " XAF"));
        } else if (transfer.getAmount().compareTo(montantEleve) >= 0) {
            // 2. Montant eleve
            alerts.add(alert(transfer, "MONTANT_ELEVE", FraudSeverity.MOYENNE,
                    "Montant de " + transfer.getAmount() + " XAF superieur au seuil d'alerte de " + montantEleve + " XAF"));
        }

        // 3. Beneficiaire recemment cree + montant significatif
        if (transfer.getBeneficiary() != null) {
            Instant since = Instant.now().minus(beneficiaireRecentJours, ChronoUnit.DAYS);
            if (transfer.getBeneficiary().getCreatedAt() != null
                    && transfer.getBeneficiary().getCreatedAt().isAfter(since)
                    && transfer.getAmount().compareTo(beneficiaireRecentMontant) >= 0) {
                alerts.add(alert(transfer, "BENEFICIAIRE_RECENT", FraudSeverity.HAUTE,
                        "Virement de " + transfer.getAmount() + " XAF vers un beneficiaire ajoute il y a moins de "
                                + beneficiaireRecentJours + " jours"));
            }
        }

        // 4. Velocite : nombre de virements sur la fenetre glissante
        Instant window = Instant.now().minus(velociteFenetreMinutes, ChronoUnit.MINUTES);
        long recentCount = transferRepository.countBySourceAccountIdAndCreatedAtAfter(
                transfer.getSourceAccount().getId(), window);
        if (recentCount >= velociteMax) {
            alerts.add(alert(transfer, "VELOCITE_" + velociteFenetreMinutes + "MIN", FraudSeverity.HAUTE,
                    recentCount + " virements inities depuis ce compte en moins de " + velociteFenetreMinutes + " minutes"));
        }

        // 5. Heure inhabituelle (00h-05h) + montant significatif
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("Africa/Douala"));
        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        if (hour >= 0 && hour < 5 && transfer.getAmount().compareTo(montantNuit) >= 0) {
            alerts.add(alert(transfer, "HEURE_INHABITUELLE", FraudSeverity.MOYENNE,
                    "Virement de " + transfer.getAmount() + " XAF initie a " + hour + "h du matin"));
        }

        return alerts;
    }

    public boolean isBlocking(List<FraudAlert> alerts) {
        return alerts.stream().anyMatch(a ->
                a.getSeverity() == FraudSeverity.HAUTE || a.getSeverity() == FraudSeverity.CRITIQUE);
    }

    private FraudAlert alert(Transfer transfer, String rule, FraudSeverity severity, String details) {
        return FraudAlert.builder()
                .transfer(transfer)
                .rule(rule)
                .severity(severity)
                .details(details)
                .build();
    }
}
