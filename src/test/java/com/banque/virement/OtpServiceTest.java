package com.banque.virement;

import com.banque.virement.domain.entity.OtpChallenge;
import com.banque.virement.domain.entity.User;
import com.banque.virement.domain.enums.NotificationType;
import com.banque.virement.domain.enums.OtpPurpose;
import com.banque.virement.domain.enums.Role;
import com.banque.virement.dto.OtpResponse;
import com.banque.virement.exception.BusinessException;
import com.banque.virement.repository.OtpChallengeRepository;
import com.banque.virement.service.MailService;
import com.banque.virement.service.NotificationService;
import com.banque.virement.service.OtpService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OtpServiceTest {

    @Mock
    private OtpChallengeRepository repository;

    @Mock
    private NotificationService notificationService;

    @Mock
    private MailService mailService;

    private OtpService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new OtpService(repository, new BCryptPasswordEncoder(4), notificationService, mailService);
        ReflectionTestUtils.setField(service, "ttlMinutes", 5);
        ReflectionTestUtils.setField(service, "maxAttempts", 5);
        ReflectionTestUtils.setField(service, "devMode", true);

        user = User.builder().id(11L).firstName("Jean").lastName("MARTIN")
                .email("jean@client.cm").passwordHash("x").role(Role.CLIENT).active(true).build();
    }

    @Test
    void requestOtpGenereEtStockeUnCodeHashe() {
        when(repository.findByUserIdAndPurposeAndConsumedFalse(eq(11L), eq(OtpPurpose.CONFIRMATION_VIREMENT)))
                .thenReturn(List.of());
        when(repository.save(any(OtpChallenge.class))).thenAnswer(inv -> inv.getArgument(0));

        OtpResponse response = service.requestOtp(user, OtpPurpose.CONFIRMATION_VIREMENT, "42");

        assertThat(response.devCode()).matches("\\d{6}");
        ArgumentCaptor<OtpChallenge> captor = ArgumentCaptor.forClass(OtpChallenge.class);
        verify(repository).save(captor.capture());
        OtpChallenge stored = captor.getValue();
        assertThat(stored.getCodeHash()).isNotEqualTo(response.devCode());
        assertThat(stored.getPurpose()).isEqualTo(OtpPurpose.CONFIRMATION_VIREMENT);
        assertThat(stored.getContext()).isEqualTo("42");
        assertThat(stored.getAttempts()).isZero();
        verify(notificationService).create(eq(user), eq(NotificationType.SECURITE), anyString(), anyString());
    }

    @Test
    void verifyAccepteUnCodeValideEtLeConsomme() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
        String code = "123456";
        OtpChallenge challenge = OtpChallenge.builder()
                .id(1L).user(user)
                .codeHash(encoder.encode(code))
                .purpose(OtpPurpose.CONFIRMATION_VIREMENT)
                .context("42")
                .expiresAt(Instant.now().plus(5, ChronoUnit.MINUTES))
                .attempts(0).maxAttempts(5).consumed(false).build();
        when(repository.findTopByUserIdAndPurposeAndContextAndConsumedFalseOrderByCreatedAtDesc(
                11L, OtpPurpose.CONFIRMATION_VIREMENT, "42")).thenReturn(Optional.of(challenge));

        service.verify(user, OtpPurpose.CONFIRMATION_VIREMENT, "42", code);

        assertThat(challenge.isConsumed()).isTrue();
    }

    @Test
    void verifyRejetteUnCodeIncorrectEtIncrementeLesTentatives() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
        OtpChallenge challenge = OtpChallenge.builder()
                .id(1L).user(user)
                .codeHash(encoder.encode("123456"))
                .purpose(OtpPurpose.CONFIRMATION_VIREMENT)
                .context("42")
                .expiresAt(Instant.now().plus(5, ChronoUnit.MINUTES))
                .attempts(0).maxAttempts(5).consumed(false).build();
        when(repository.findTopByUserIdAndPurposeAndContextAndConsumedFalseOrderByCreatedAtDesc(
                11L, OtpPurpose.CONFIRMATION_VIREMENT, "42")).thenReturn(Optional.of(challenge));

        assertThatThrownBy(() -> service.verify(user, OtpPurpose.CONFIRMATION_VIREMENT, "42", "000000"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("incorrect");
        assertThat(challenge.getAttempts()).isEqualTo(1);
    }

    @Test
    void verifyRejetteUnCodeExpire() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
        OtpChallenge challenge = OtpChallenge.builder()
                .id(1L).user(user)
                .codeHash(encoder.encode("123456"))
                .purpose(OtpPurpose.CONFIRMATION_VIREMENT)
                .context("42")
                .expiresAt(Instant.now().minus(1, ChronoUnit.MINUTES))
                .attempts(0).maxAttempts(5).consumed(false).build();
        when(repository.findTopByUserIdAndPurposeAndContextAndConsumedFalseOrderByCreatedAtDesc(
                11L, OtpPurpose.CONFIRMATION_VIREMENT, "42")).thenReturn(Optional.of(challenge));

        assertThatThrownBy(() -> service.verify(user, OtpPurpose.CONFIRMATION_VIREMENT, "42", "123456"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("expire");
    }
}
