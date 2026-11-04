# Plateforme de Virement Bancaire Sécurisé

Middleware **Spring Boot** sécurisant les virements bancaires (internes et externes) entre le client (applications mobiles / e-banking, non développées ici — uniquement des API) et le système d'information central de la banque.

Projet réalisé dans le cadre du **Cahier des charges « Mise en place d'une plateforme de virement sécurisé dans une banque via Spring Boot »** (Groupe 9 — Année académique 2026/2027).

---

## 1. Fonctionnalités livrées

| Exigence du cahier des charges | État | Implémentation |
|---|---|---|
| Backend Spring Boot (API REST) | ✅ | Spring Boot 3.4 / Java 21, architecture en couches (Controller → Service → Repository → Entity) |
| Virements unitaires internes / externes | ✅ | `POST /api/v1/transfers` (+ OTP de confirmation) |
| Virements de masse B2B (remises salaires/fournisseurs) | ✅ | Upload CSV → traitement **asynchrone** → double signature des mandataires |
| Authentification forte (MFA) | ✅ | JWT + OTP à 6 chiffres (hash BCrypt, expiration 5 min, 5 tentatives max) |
| RBAC strict | ✅ | `CLIENT` (B2C), `ENTREPRISE` (B2B mandataires), `AGENT_BANCAIRE`, `ADMIN` (`/api/v1/admin/**`) |
| Gestion des bénéficiaires | ✅ | CRUD + validation IBAN (modulo 97) + **délai de carence 48h** |
| Plafonds journaliers / hebdomadaires | ✅ | Contrôle temps réel à la création (suivi des exécutions) |
| Virements en double | ✅ | Fenêtre de 10 minutes + clé d'idempotence (`idempotencyKey`) |
| Comptes suspendus/bloqués | ✅ | Blocage à la création et à l'exécution ; suspension/réactivation par l'admin |
| Anti-fraude | ✅ | Règles : montant critique/élevé, bénéficiaire récent, vélocité, heure inhabituelle → alerte + blocage avant débit |
| Validation manuelle des opérations suspectes | ✅ | Tableau de bord admin : valider (exécute) ou rejeter (jamais débité) |
| Validation hiérarchique B2B | ✅ | Quorum de signatures de mandataires distincts (configurable, ex : 2) + rejet possible |
| Tableau de bord client | ✅ | `GET /api/v1/client/dashboard` |
| Tableau de bord administration (UI web) | ✅ | `/admin/` : KPIs, répartition par statut, volume journalier, transactions, anti-fraude, clients, comptes, audit |
| Historique + export | ✅ | Filtres (statut, période, texte) + export **PDF** et **Excel (XLSX)** + avis d'opération PDF |
| Notifications | ✅ | In-app (consultables via API + compteur non-lus) + email (SMTP optionnel) ; SMS simulé via notification |
| Journaux d'audit immuables | ✅ | Écriture append-only (aucune API de modification/suppression), consultation admin |
| Traçabilité / LBC-FT | ✅ | Audit + alertes + revue manuelle + conservation horodatée |
| Documentation OpenAPI | ✅ | Swagger UI : `/swagger-ui.html` |
| Tests automatisés | ✅ | 39 tests (JUnit 5 / Mockito) — `mvn test` |
| Conteneurisation | ✅ | `Dockerfile` multi-étapes + `docker-compose.yml` (PostgreSQL 16) |
| Documentation utilisateur & installation | ✅ | `docs/` |

## 2. Comptes de démonstration (seed automatique)

| Rôle | Email | Mot de passe |
|---|---|---|
| Administrateur | `admin@banque.cm` | `Admin@123` |
| Agent bancaire (anti-fraude) | `agent@banque.cm` | `Agent@123` |
| Client B2C (2 comptes, carnet de bénéficiaires) | `jean.martin@client.cm` | `Client@123` |
| Client B2C | `marie.nga@client.cm` | `Client@123` |
| Entreprise B2B — DG / mandataire 1 | `directeur@ekondo.cm` | `Ent@123` |
| Entreprise B2B — comptable / mandataire 2 | `comptable@ekondo.cm` | `Ent@123` |

> Le seed crée aussi une **alerte anti-fraude ouverte** (8 500 000 XAF) et un **virement B2B en attente de double signature** pour la démonstration.

## 3. Démarrage rapide

**Prérequis** : JDK 21 (Temurin), Maven 3.9+.

### Option A — Développement express (H2 en mémoire, zéro installation PostgreSQL)

```bash
mvn clean package
java -jar target/plateforme-virement-securise-1.0.0.jar --spring.profiles.active=h2
```

Puis ouvrir :
- **Tableau de bord admin** : http://localhost:8080/admin/
- **Swagger UI** : http://localhost:8080/swagger-ui.html
- **Console H2** : http://localhost:8080/h2-console (JDBC URL : `jdbc:h2:mem:virement`)

### Option B — Docker Compose (PostgreSQL + application)

```bash
docker compose up --build
```

### Option C — PostgreSQL local

Créer la base : `CREATE DATABASE virementdb;` (utilisateur `virement` / `virement`), puis :

```bash
DB_URL=jdbc:postgresql://localhost:5432/virementdb \
DB_USERNAME=virement \
DB_PASSWORD=virement \
JWT_SECRET=$(openssl rand -base64 48) \
mvn spring-boot:run
```

## 4. Parcours de démonstration (5 minutes)

1. **Connexion admin** : http://localhost:8080/admin/ → `admin@banque.cm` / `Admin@123`
2. **Tableau de bord** : KPIs, répartition des virements par statut, volume journalier.
3. **Anti-fraude** : menu *Anti-fraude* → l'alerte `TRF-SEED-000900` (8,5 M XAF) → **Rejeter** (les fonds ne sont jamais débités) ou **Valider** (exécution immédiate).
4. **Client B2C** (nouvel onglet, Swagger ou curl) : login `jean.martin@client.cm` / `Client@123` → créer un virement → confirmer avec le code OTP **retourné dans la réponse en mode dev** (`devCode`).
5. **Double signature B2B** : login `comptable@ekondo.cm`, demander un OTP pour l'approbation, signer ; puis idem `directeur@ekondo.cm` → le quorum est atteint → le virement est exécuté.
6. **Audit** : menu *Journaux d'audit* → toutes les actions tracées.

### Exemple d'appel API (curl)

```bash
# 1. Connexion
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"jean.martin@client.cm","password":"Client@123"}' | python -c "import sys,json;print(json.load(sys.stdin)['token'])")

# 2. Créer un virement vers un bénéficiaire du carnet (id = 1)
curl -s -X POST http://localhost:8080/api/v1/transfers \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"sourceAccountId":1,"beneficiaryId":1,"amount":50000,"motif":"Loyer"}'

# 3. Confirmer avec l'OTP (en mode dev, le code est dans la réponse ci-dessus -> demander via /api/v1/otp/request)
curl -s -X POST http://localhost:8080/api/v1/transfers/1/confirm \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"otp":"123456"}'
```

## 5. Structure du projet

```
plateforme-virement-securise/
├── pom.xml                     # Maven (Spring Boot 3.4, Java 21)
├── Dockerfile                  # build multi-étapes (maven → jre 21)
├── docker-compose.yml          # PostgreSQL 16 + app (+ profils rabbitmq / adminer)
├── src/main/java/com/banque/virement/
│   ├── config/                 # SecurityConfig, OpenApiConfig, AsyncConfig, DataInitializer (seed)
│   ├── security/               # JwtService, JwtAuthenticationFilter, CurrentUser
│   ├── domain/entity|enums/    # Modèle JPA (User, Company, BankAccount, Beneficiary, Transfer, TransferBatch,
│   │                           #   TransferApproval, Notification, AuditLog, FraudAlert, OtpChallenge)
│   ├── repository/             # Spring Data JPA (+ requêtes de recherche)
│   ├── service/                # AuthService, OtpService, BeneficiaryService, TransferService, ApprovalService,
│   │                           #   BatchAsyncProcessor, FraudDetectionService, AdminService, ExportService…
│   ├── controller/             # API REST /api/v1/**
│   └── util/                   # IbanUtils (modulo 97), ReferenceGenerator
├── src/main/resources/
│   ├── application.yml         # PostgreSQL (prod) — variables d'environnement
│   ├── application-h2.yml      # profil démo locale (H2 + OTP affiché)
│   └── static/admin/           # Tableau de bord d'administration (SPA vanilla, offline-friendly)
├── src/test/java/              # 39 tests JUnit 5 / Mockito
├── docs/                       # Manuel d'installation, Guide utilisateur, Conformité CDC
└── cahier_de_charges_064739.pdf (dans le dossier parent)
```

## 6. Sécurité mise en œuvre

- **Mots de passe** : BCrypt (coût 12). **JWT** : HS256, expiration 8h. **OTP** : BCrypt + expiration 5 min + 5 tentatives max + invalidation à usage unique.
- **RBAC** : URL (`/api/v1/admin/**`) + annotations `@EnableMethodSecurity` ; chaque endpoint vérifie le propriétaire de la ressource (compte / bénéficiaire).
- **Anti double-débit** : verrou pessimiste (`SELECT … FOR UPDATE`) sur les comptes source et cible à l'exécution.
- **Traçabilité** : `audit_logs` append-only (connexions, créations, exécutions, suspensions, validations fraude, changements de rôle).
- **Protection données** : IBAN masqué dans l'UI, jamais de secret dans les réponses, IBAN complet uniquement dans les avis PDF destinés au client.
- **OWASP** : validation Bean Validation sur tous les DTOs, gestion centralisée des erreurs (pas de stacktrace exposée), en-têtes de sécurité Spring Security, CORS restreint à `/api/**`.
- **TLS/HTTPS** : à terminer en production via reverse proxy (nginx/Traefik) — voir `docs/MANUEL_INSTALLATION.md` (AES-256 au repos : chiffrement applicatif des données sensibles possible via JPA AttributeConverter si exigé).

## 7. Limites & hypothèses (honnêteté technique)

- **SMS/email réels** : non branchés (pas de fournisseur). L'envoi est simulé (notification in-app + log/email si SMTP configuré). L'OTP est affiché en réponse API uniquement en `dev-mode`.
- **Core Banking System** : intégré via l'interface `CoreBankingGateway` ; l'implémentation fournie est **simulée** (accuse réception, journalise, "refuse" les motifs contenant `REFUS_TEST`). Un vrai connecteur ISO 20022 (pacs.008) remplacerait cette classe sans toucher au reste.
- **RabbitMQ/Kafka** : le code utilise un `Executor` asynchrone (`@Async`) ; RabbitMQ est fourni en profil optionnel du compose pour brancher une vraie file si demandé.
- **Carence levée pour les remises B2B** : décision documentée (les remises signées par double signature ne subissent pas le délai de 48h appliqué aux bénéficiaires individuels).
- **Couverture de tests** : 39 tests sur la logique critique (virements, OTP, fraude, IBAN, export). Le seuil de 80% du CDC nécessiterait des tests d'intégration supplémentaires (MockMvc / Testcontainers) — JaCoCo est déjà câblé (`target/site/jacoco`).

## 8. Documentation

- `docs/MANUEL_INSTALLATION.md` — installation, exploitation, dépannage, sauvegarde
- `docs/GUIDE_UTILISATEUR.md` — guide pas-à-pas (client B2C, mandataire B2B, agent/admin)
- `docs/CONFORMITE_CAHIER_DE_CHARGES.md` — correspondance point par point avec le CDC

## 9. Auteurs

Groupe 9 — Projet académique « Plateforme de virement sécurisé via Spring Boot » — Année académique 2026/2027.
