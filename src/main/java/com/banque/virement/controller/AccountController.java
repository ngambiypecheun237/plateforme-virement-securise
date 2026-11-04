package com.banque.virement.controller;

import com.banque.virement.domain.enums.Role;
import com.banque.virement.dto.TransferCreateRequest;
import com.banque.virement.security.CurrentUser;
import com.banque.virement.service.AuthService;
import com.banque.virement.domain.entity.BankAccount;
import com.banque.virement.domain.entity.User;
import com.banque.virement.repository.BankAccountRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "Comptes", description = "Consultation des comptes du client")
public class AccountController {

    private final BankAccountRepository accountRepository;
    private final AuthService authService;
    private final CurrentUser currentUser;

    public AccountController(BankAccountRepository accountRepository, AuthService authService, CurrentUser currentUser) {
        this.accountRepository = accountRepository;
        this.authService = authService;
        this.currentUser = currentUser;
    }

    @GetMapping
    @Operation(summary = "Liste mes comptes bancaires (soldes, plafonds, statut)")
    public ResponseEntity<List<TransferCreateRequest.AccountView>> myAccounts() {
        User user = authService.requireActiveUser(currentUser.email());
        List<BankAccount> accounts;
        if (user.getRole() == Role.ENTREPRISE && user.getCompany() != null) {
            accounts = accountRepository.findByOwnerCompanyId(user.getCompany().getId());
        } else {
            accounts = accountRepository.findByOwnerUserId(user.getId());
        }
        return ResponseEntity.ok(accounts.stream()
                .map(TransferCreateRequest.AccountView::from)
                .collect(Collectors.toList()));
    }
}
