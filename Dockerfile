# ---------- Etape 1 : build Maven ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
# Cache des dependances
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# ---------- Etape 2 : image d'execution ----------
FROM eclipse-temurin:21-jre
LABEL org.opencontainers.image.title="Plateforme de Virement Bancaire Securise" \
      org.opencontainers.image.description="Middleware de virement interne/externe securise (Groupe 9)"

RUN groupadd -r bancaire && useradd -r -g bancaire bancaire
WORKDIR /app
COPY --from=build /app/target/plateforme-virement-securise-1.0.0.jar app.jar
RUN chown bancaire:bancaire app.jar
USER bancaire

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
  CMD bash -c 'exec 3<>/dev/tcp/localhost/8080 && printf "GET /actuator/health HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3 && grep -q "200" <&3' || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
