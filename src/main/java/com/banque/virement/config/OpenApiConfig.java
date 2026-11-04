package com.banque.virement.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI bancaireOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("API de la Plateforme de Virement Bancaire Securise")
                        .description("Middleware securisant les virements internes et externes : "
                                + "authentification MFA (JWT + OTP), controle des plafonds, detection de fraude, "
                                + "workflow d'approbation B2B multi-mandataires, audit immuable.")
                        .version("1.0.0")
                        .contact(new Contact().name("Groupe 9 - Projet Bancaire").email("support@banque.cm"))
                        .license(new License().name("Usage interne banque")))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("Token JWT obtenu via /api/v1/auth/login")));
    }
}
