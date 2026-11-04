# Manuel d'Installation et d'Exploitation

## 1. Prérequis

| Outil | Version | Remarque |
|---|---|---|
| JDK | **21 LTS** (Temurin recommandé) | Le projet cible Java 21 (Dockerfile inclus) |
| Maven | 3.9+ | Ou utiliser le wrapper si ajouté |
| PostgreSQL | 16 | Option A (profil h2) n'en a pas besoin |
| Docker + Docker Compose | 24+ | Option B |
| Navigateur moderne | Chrome / Firefox / Edge | Tableau de bord `/admin/` |

> ⚠️ Construire/tester avec JDK 21. (La logique métier est compatible JDK 17+, mais les outils de test (Mockito) sont certifiés sur LTS.)

## 2. Installation — Option A : démonstration locale (H2)

```powershell
cd plateforme-virement-securise
mvn clean package
java -jar target\plateforme-virement-securise-1.0.0.jar --spring.profiles.active=h2
```

- Application : http://localhost:8080
- Tableau de bord admin : http://localhost:8080/admin/
- Swagger : http://localhost:8080/swagger-ui.html
- Console H2 : http://localhost:8080/h2-console (JDBC `jdbc:h2:mem:virement`, user `sa`, sans mot de passe)

## 3. Installation — Option B : Docker Compose (recommandée pour la prod-like)

```bash
cd plateforme-virement-securise
docker compose up --build -d
docker compose logs -f app
```

Vérification :

```bash
curl http://localhost:8080/actuator/health
# {"status":"UP",...}
```

Arrêt / suppression :

```bash
docker compose down          # garde les données (volume pgdata)
docker compose down -v       # supprime aussi la base
```

Services optionnels :

```bash
docker compose --profile messaging up -d   # RabbitMQ (5672 / console 15672, guest/guest)
docker compose --profile tools up -d       # Adminer (http://localhost:8081)
```

## 4. Installation — Option C : PostgreSQL local (sans Docker)

```sql
CREATE USER virement WITH PASSWORD 'virement';
CREATE DATABASE virementdb OWNER virement;
```

Variables d'environnement (exemple `.env` — **ne jamais commiter**) :

```dotenv
DB_URL=jdbc:postgresql://localhost:5432/virementdb
DB_USERNAME=virement
DB_PASSWORD=virement
JWT_SECRET=<openssl rand -base64 48>
DEV_MODE=false
SERVER_PORT=8080
MAIL_HOST=smtp.banque.cm
MAIL_PORT=587
MAIL_USERNAME=...
MAIL_PASSWORD=...
```

Démarrage : `java -jar target/plateforme-virement-securise-1.0.0.jar`

## 5. Configuration (application.yml)

| Propriété | Défaut | Rôle |
|---|---|---|
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | postgres localhost | Connexion PostgreSQL |
| `JWT_SECRET` | valeur de démo | **À changer en production** (≥ 48 octets base64) |
| `JWT_EXPIRATION_MS` | 28800000 (8h) | Durée de vie du token |
| `CARENCE_HEURES` | 48 | Délai de carence après ajout de bénéficiaire |
| `FRAUDE_MONTANT_CRITIQUE` | 25000000 | Seuil d'alerte critique (XAF) |
| `FRAUDE_MONTANT_ELEVE` | 5000000 | Seuil d'alerte (XAF) |
| `FRAUDE_MONTANT_NUIT` | 1000000 | Seuil 00h–05h (XAF) |
| `FRAUDE_MONTANT_BENEFICIAIRE_RECENT` | 2000000 | Seuil bénéficiaire < 7 jours (XAF) |
| `DEV_MODE` | false | ⚠️ `true` renvoie le code OTP dans la réponse API (démo uniquement) |
| `spring.jpa.hibernate.ddl-auto` | update | `update` en démo ; `validate` + migrations Flyway en prod |

## 6. Exploitation au quotidien

### 6.1 Santé & métriques

- `GET /actuator/health` — statut (UP/DOWN), liveness/readiness
- `GET /actuator/metrics` — métriques applicatives
- `GET /actuator/prometheus` — export Prometheus (si `micrometer-registry-prometheus` ajouté)

### 6.2 Sauvegarde PostgreSQL

```bash
docker exec virement-db pg_dump -U virement virementdb > backup_$(date +%F).sql
# Restauration :
cat backup.sql | docker exec -i virement-db psql -U virement virementdb
```

Les journaux d'audit (`audit_logs`) sont **append-only** : ne jamais les purger sans procédure de conformité (conservation selon réglementation — voir section LBC/FT du CDC).

### 6.3 Suspension d'urgence d'un compte

1. Dashboard admin → *Comptes bancaires* → rechercher l'IBAN/titulaire → **Suspendre** (motif requis).
2. Le compte passe en `SUSPENDED` : toute création/exécution de virement depuis ce compte est rejetée ; le titulaire est notifié.
3. Réactivation via le même écran (**Réactiver**).

### 6.4 Incident « virement bloqué anti-fraude »

1. Menu *Anti-fraude* → alerte `OUVERTE` → **Prendre en revue**.
2. Vérifier : montant, bénéficiaire, historique, identité (appel téléphonique si nécessaire).
3. **Valider** (exécute le virement) ou **Rejeter** (le virement est annulé, fonds jamais débités) ; motif tracé dans l'audit.

### 6.5 Rotation du secret JWT

Changer `JWT_SECRET` invalide tous les tokens en cours (les utilisateurs se reconnectent). Planifier hors heures de pointe.

### 6.6 Déploiement HTTPS (production)

Placer Nginx/Traefik en frontal : terminaison TLS 1.3, redirection HTTP→HTTPS, HSTS, en-têtes `X-Frame-Options`, `nosniff`. L'application écoute en HTTP interne uniquement.

```nginx
server {
    listen 443 ssl;
    server_name virements.banque.cm;
    ssl_protocols TLSv1.3;
    location / { proxy_pass http://localhost:8080; proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for; }
}
```

## 7. Dépannage

| Symptôme | Cause probable | Solution |
|---|---|---|
| `BUILD FAILURE` sous JDK ≠ 21 | incompatibilité outils | Utiliser Temurin 21 (`java -version`) |
| 401 sur tous les appels | token absent/expiré | Se reconnecter (`POST /api/v1/auth/login`) |
| 403 sur `/api/v1/admin/**` | rôle insuffisant | Compte `ADMIN`/`AGENT_BANCAIRE` requis |
| OTP « Aucun code en cours » | contexte différent de l'OTP | Redemander un OTP (`POST /api/v1/otp/request` avec le bon `context` : id du virement, IBAN du bénéficiaire, ou `BATCH-<id>`) |
| « Délai de carence » | bénéficiaire ajouté < 48h | Attendre `availableAt` ou utiliser un bénéficiaire existant |
| « Plafond journalier dépassé » | plafond du compte atteint | Augmenter `dailyLimit` (admin SQL) ou attendre J+1 |
| H2 : tables absentes | profil h2 non activé | `--spring.profiles.active=h2` |
| Port 8080 occupé | autre instance | `SERVER_PORT=8081` |
| Virement B2B non exécuté | quorum non atteint | Vérifier `/api/v1/approvals/pending` et les signatures (2 requises par défaut) |

## 8. Tests & qualité

```bash
mvn clean test          # 39 tests unitaires (JUnit 5 / Mockito)
mvn jacoco:report       # rapport de couverture : target/site/jacoco/index.html
```

CI recommandé : GitHub Actions — `mvn -B verify` + scan OWASP Dependency-Check / SonarQube.

## 9. Journal des versions

- **1.0.0** (2026-11) — Livraison initiale : socle complet du CDC (voir `CONFORMITE_CAHIER_DE_CHARGES.md`).
