package com.banque.virement.service;

import com.banque.virement.domain.entity.Beneficiary;
import com.banque.virement.domain.entity.User;
import com.banque.virement.domain.enums.BeneficiaryStatus;
import com.banque.virement.domain.enums.NotificationType;
import com.banque.virement.domain.enums.OtpPurpose;
import com.banque.virement.dto.BeneficiaryRequest;
import com.banque.virement.exception.BusinessException;
import com.banque.virement.exception.ResourceNotFoundException;
import com.banque.virement.repository.BeneficiaryRepository;
import com.banque.virement.util.IbanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class BeneficiaryService {

    private final BeneficiaryRepository repository;
    private final OtpService otpService;
    private final NotificationService notificationService;
    private final AuditService auditService;

    @Value("${app.carence-heures:48}")
    private int carenceHeures;

    public BeneficiaryService(BeneficiaryRepository repository, OtpService otpService,
                              NotificationService notificationService, AuditService auditService) {
        this.repository = repository;
        this.otpService = otpService;
        this.notificationService = notificationService;
        this.auditService = auditService;
    }

    /**
     * Creation d'un beneficiaire : exige la verification OTP (finalite AJOUT_BENEFICIAIRE,
     * contexte = IBAN normalise) et applique le delai de carence (48h par defaut).
     */
    @Transactional
    public Beneficiary create(User user, BeneficiaryRequest request, String otp) {
        String iban = IbanUtils.normalize(request.iban());
        if (!IbanUtils.isValid(iban)) {
            throw new BusinessException("IBAN invalide : la cle de controle (modulo 97) ne correspond pas");
        }
        otpService.verify(user, OtpPurpose.AJOUT_BENEFICIAIRE, iban, otp);

        if (repository.findByOwnerIdAndIban(user.getId(), iban).isPresent()) {
            throw new BusinessException("Ce beneficiaire existe deja dans votre carnet");
        }

        Beneficiary beneficiary = Beneficiary.builder()
                .owner(user)
                .name(request.name().trim())
                .iban(iban)
                .bic(request.bic())
                .bankName(request.bankName())
                .country(request.country() == null ? "CM" : request.country().toUpperCase())
                .email(request.email())
                .status(BeneficiaryStatus.ACTIVE)
                .verified(true)
                .createdAt(Instant.now())
                .availableAt(Instant.now().plus(carenceHeures, ChronoUnit.HOURS))
                .build();
        beneficiary = repository.save(beneficiary);

        notificationService.create(user, NotificationType.BENEFICIAIRE_AJOUTE, "Beneficiaire ajoute",
                "Le beneficiaire " + beneficiary.getName() + " (" + maskIban(iban) + ") a ete ajoute a votre carnet. "
                        + "Il sera utilisable apres le delai de carence de " + carenceHeures + "h (a partir du "
                        + beneficiary.getAvailableAt() + ").");
        auditService.log(user.getEmail(), "AJOUT_BENEFICIAIRE", "Beneficiary",
                String.valueOf(beneficiary.getId()), "Beneficiaire ajoute : " + maskIban(iban), null);
        return beneficiary;
    }

    public List<Beneficiary> list(User user) {
        return repository.findByOwnerIdOrderByCreatedAtDesc(user.getId());
    }

    public Beneficiary get(User user, Long id) {
        return repository.findByIdAndOwnerId(id, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Beneficiaire introuvable"));
    }

    /** Mise a jour autorisee : nom, banque, BIC, pays, email. L'IBAN est immuable. */
    @Transactional
    public Beneficiary update(User user, Long id, BeneficiaryRequest request) {
        Beneficiary beneficiary = get(user, id);
        if (request.name() != null && !request.name().isBlank()) {
            beneficiary.setName(request.name().trim());
        }
        if (request.bic() != null) {
            beneficiary.setBic(request.bic());
        }
        if (request.bankName() != null) {
            beneficiary.setBankName(request.bankName());
        }
        if (request.country() != null) {
            beneficiary.setCountry(request.country().toUpperCase());
        }
        if (request.email() != null) {
            beneficiary.setEmail(request.email());
        }
        beneficiary = repository.save(beneficiary);
        auditService.log(user.getEmail(), "MODIFICATION_BENEFICIAIRE", "Beneficiary",
                String.valueOf(id), "Mise a jour des coordonnees", null);
        return beneficiary;
    }

    /** Suppression securisee : OTP SUPPRESSION_BENEFICIAIRE (contexte = identifiant). */
    @Transactional
    public void delete(User user, Long id, String otp) {
        Beneficiary beneficiary = get(user, id);
        otpService.verify(user, OtpPurpose.SUPPRESSION_BENEFICIAIRE, String.valueOf(id), otp);
        repository.delete(beneficiary);
        auditService.log(user.getEmail(), "SUPPRESSION_BENEFICIAIRE", "Beneficiary",
                String.valueOf(id), "Beneficiaire supprime : " + maskIban(beneficiary.getIban()), null);
    }

    private static String maskIban(String iban) {
        if (iban == null || iban.length() < 8) {
            return "****";
        }
        return iban.substring(0, 4) + "****" + iban.substring(iban.length() - 4);
    }
}
