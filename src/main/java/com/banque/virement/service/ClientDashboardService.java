package com.banque.virement.service;

import com.banque.virement.domain.entity.BankAccount;
import com.banque.virement.domain.entity.Transfer;
import com.banque.virement.domain.entity.User;
import com.banque.virement.domain.enums.TransferStatus;
import com.banque.virement.dto.TransferCreateRequest;
import com.banque.virement.repository.BankAccountRepository;
import com.banque.virement.repository.TransferRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Tableau de bord client (B2C et B2B) : soldes, nombre de virements,
 * dernieres operations, resume du mois.
 */
@Service
public class ClientDashboardService {

    private final BankAccountRepository accountRepository;
    private final TransferRepository transferRepository;

    public ClientDashboardService(BankAccountRepository accountRepository, TransferRepository transferRepository) {
        this.accountRepository = accountRepository;
        this.transferRepository = transferRepository;
    }

    public Map<String, Object> dashboard(User user) {
        List<BankAccount> accounts = collectAccounts(user);

        BigDecimal totalBalance = accounts.stream()
                .map(BankAccount::getBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long transfersCount = transferRepository.countByCreatedById(user.getId());
        long executedCount = transferRepository.countByCreatedByIdAndStatus(user.getId(), TransferStatus.EXECUTE);
        long pendingCount = transferRepository.countByCreatedByIdAndStatus(user.getId(), TransferStatus.EN_ATTENTE_CONFIRMATION)
                + transferRepository.countByCreatedByIdAndStatus(user.getId(), TransferStatus.EN_ATTENTE_APPROBATION)
                + transferRepository.countByCreatedByIdAndStatus(user.getId(), TransferStatus.PROGRAMME);

        List<Transfer> recent = transferRepository.findByCreatedByIdOrderByCreatedAtDesc(user.getId()).stream()
                .limit(5)
                .collect(Collectors.toList());

        Map<String, Object> dashboard = new LinkedHashMap<>();
        dashboard.put("soldeTotal", totalBalance);
        dashboard.put("devise", accounts.isEmpty() ? "XAF" : accounts.get(0).getCurrency());
        dashboard.put("nombreComptes", accounts.size());
        dashboard.put("nombreVirements", transfersCount);
        dashboard.put("virementsExecutes", executedCount);
        dashboard.put("virementsEnAttente", pendingCount);
        dashboard.put("comptes", accounts.stream().map(TransferCreateRequest.AccountView::from).collect(Collectors.toList()));
        dashboard.put("dernieresOperations", recent.stream().map(TransferCreateRequest.View::from).collect(Collectors.toList()));
        return dashboard;
    }

    private List<BankAccount> collectAccounts(User user) {
        if (user.getRole() == com.banque.virement.domain.enums.Role.ENTREPRISE && user.getCompany() != null) {
            return accountRepository.findByOwnerCompanyId(user.getCompany().getId());
        }
        return accountRepository.findByOwnerUserId(user.getId());
    }
}
