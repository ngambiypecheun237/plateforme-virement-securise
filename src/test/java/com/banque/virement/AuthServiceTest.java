package com.banque.virement;

import com.banque.virement.domain.entity.User;
import com.banque.virement.domain.enums.Role;
import com.banque.virement.dto.LoginRequest;
import com.banque.virement.dto.AuthResponse;
import com.banque.virement.exception.UnauthorizedException;
import com.banque.virement.repository.UserRepository;
import com.banque.virement.security.JwtService;
import com.banque.virement.service.AuditService;
import com.banque.virement.service.AuthService;
import com.banque.virement.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private JwtService jwtService;
    @Mock private AuditService auditService;
    @Mock private NotificationService notificationService;

    private AuthService authService;
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, encoder, jwtService, auditService, notificationService);
    }

    private User client() {
        return User.builder().id(1L).firstName("Jean").lastName("MARTIN")
                .email("jean@client.cm").passwordHash(encoder.encode("Client@123"))
                .role(Role.CLIENT).active(true).build();
    }

    @Test
    void loginReussiRetourneUnToken() {
        when(userRepository.findByEmail("jean@client.cm")).thenReturn(Optional.of(client()));
        when(jwtService.generateToken(org.mockito.ArgumentMatchers.any(User.class))).thenReturn("token-jwt");
        when(jwtService.getExpirationMs()).thenReturn(28800000L);

        AuthResponse response = authService.login(new LoginRequest("jean@client.cm", "Client@123"), "127.0.0.1");

        assertThat(response.token()).isEqualTo("token-jwt");
        assertThat(response.role()).isEqualTo("CLIENT");
    }

    @Test
    void loginAvecMauvaisMotDePasseEstRejete() {
        when(userRepository.findByEmail("jean@client.cm")).thenReturn(Optional.of(client()));

        assertThatThrownBy(() -> authService.login(new LoginRequest("jean@client.cm", "mauvais"), "127.0.0.1"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("Identifiants");
    }

    @Test
    void loginSurCompteDesactiveEstRejete() {
        User inactive = client();
        inactive.setActive(false);
        when(userRepository.findByEmail("jean@client.cm")).thenReturn(Optional.of(inactive));

        assertThatThrownBy(() -> authService.login(new LoginRequest("jean@client.cm", "Client@123"), "127.0.0.1"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("desactive");
    }

    @Test
    void loginUtilisateurInconnuEstRejete() {
        when(userRepository.findByEmail("fantome@client.cm")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("fantome@client.cm", "x"), "127.0.0.1"))
                .isInstanceOf(UnauthorizedException.class);
    }
}
