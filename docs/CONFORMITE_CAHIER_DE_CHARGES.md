# Conformité au Cahier des Charges

Référence : « Cahier de charge : mise en place d'une plateforme de virement sécurisé dans une banque via Spring Boot » — Groupe 9 — Année académique 2026/2027.

| # | Exigence (section CDC) | Statut | Où / Comment |
|---|---|---|---|
| **I. Contexte et objectifs** | Middleware entre clients et Core Banking System ; authentification forte ; chiffrement ; orchestration de bout en bout ; traçabilité totale | ✅ | Architecture API REST + `CoreBankingGateway` (interface) avec implémentation simulée ; journal d'audit immuable (`audit_logs`) |
| **II.1.1** | Backend API RESTful Spring Boot | ✅ | Spring Boot 3.4.1, Java 21, packages `controller/service/repository/entity` |
| **II.1.2** | Virements unitaires internes et externes | ✅ | `TransferService` — routage interne (IBAN présent dans la banque) / externe (SI central simulé) |
| **II.1.3** | Virements de masse B2B (batch, fichiers SEPA/CSV) | ✅ | `BatchService` + `BatchAsyncProcessor` — CSV `nom;iban;montant;motif[;bic]`, traitement asynchrone, signature multi-mandataires |
| **II.1.4** | Chiffrement en transit / au repos ; MFA via JWT & Spring Security | ✅ (partiel) | JWT + BCrypt + OTP ; TLS 1.3 via reverse proxy (prod) ; chiffrement au repos : AES-256 recommandé via `AttributeConverter` (documenté, non actif par défaut) |
| **II.1.5** | Tableau de bord d'administration (supervision, validation manuelle, logs) | ✅ | `/admin/` — SPA dédiée (login, KPIs, transactions, anti-fraude, clients, comptes, audit) |
| **II.2.1** | Interfaces clients finaux NON développées | ✅ conforme | Aucune app mobile/e-banking ; l'API est le seul point d'entrée |
| **II.2.2** | Core Banking System non modifié, intégration non intrusive | ✅ | Adaptateur `CoreBankingGateway` sans refonte du SI |
| **II.3** | Acteurs B2C / B2B / Admin & conformité | ✅ | Rôles `CLIENT`, `ENTREPRISE`, `AGENT_BANCAIRE`, `ADMIN` |
| **III.1** | Connexion sécurisée + sessions + déconnexion auto | ✅ | JWT 8h ; déconnexion côté client ; expiration stricte |
| **III.1** | MFA — OTP pour validation des virements et ajout de bénéficiaires | ✅ | `OtpService` (6 chiffres, BCrypt, 5 min, 5 tentatives) — virement (CONFIRMATION), approbation (APPROBATION), ajout/suppression bénéficiaire |
| **III.1** | RBAC clients / entreprises / administrateurs | ✅ | `SecurityConfig` (URL) + vérifications d'appartenance dans les services ; accès admin réservé ADMIN/AGENT_BANCAIRE |
| **III.2** | Bénéficiaires : ajout, liste, modification, suppression | ✅ | `BeneficiaryController` (CRUD complet) |
| **III.2** | Vérification des coordonnées avant enregistrement | ✅ | IBAN modulo 97 + pays/longueur contrôlés (`IbanUtils`) ; BIC stocké (non vérifié auprès de SWIFT — documenté) |
| **III.2** | Délai de carence 24–48h après ajout | ✅ | `availableAt = now + 48h` (configurable `CARENCE_HEURES`) ; virement refusé avant |
| **III.3** | Notifications (création, exécution, rejet, échec, connexion, activité sensible, opération inhabituelle) | ✅ | `NotificationService` (in-app + email simulé/SMTP) ; 7 types métier |
| **III.3** | Consultation des notifications depuis l'espace personnel | ✅ | `GET /api/v1/notifications`, badge non-lus, marquer lu |
| **III.3** | Historique complet + filtres + export PDF/Excel | ✅ | `GET /api/v1/transfers` (statut, période, texte) ; `…/export?format=pdf|xlsx` ; avis PDF par opération |
| **III.3** | Statut en temps réel du virement | ✅ | Statuts exposés dans toutes les réponses API + tableau de bord |
| **III.4** | Virements unitaires : saisie montant/motif/compte de débit/bénéficiaire ; immédiats ou différés | ✅ | `TransferCreateRequest` (+ `scheduledDate`) |
| **III.4** | Vérification des infos, du solde, confirmation, exécution, gestion statut, référence, notification | ✅ | Chaîne complète dans `TransferService` |
| **III.4** | Virements de masse via fichiers (XML SEPA ou CSV) | ✅ (CSV) | CSV pris en charge ; l'architecture accepte un parseur SEPA (ISO 20022 pain.001) en remplacement |
| **III.4** | Contrôle des plafonds journaliers/hebdomadaires en temps réel | ✅ | `sumExecutedSince` + `dailyLimit`/`weeklyLimit` du compte |
| **III.4** | Validation hiérarchique B2B (signatures de plusieurs mandataires) | ✅ | `ApprovalService` : quorum de signatures distinctes (≥2 par défaut, configurable par entreprise), maker-checker, rejet possible, signature par lot |
| **III.5** | Admin : liste clients, infos comptes, recherche, liste des virements, filtres, opérations en attente, rôles & permissions, rapports statistiques | ✅ | `AdminController` (`/api/v1/admin/**`) + UI `/admin/` |
| **III.6** | Tableau de bord client (solde, nb virements, dernières transactions, résumé) | ✅ | `GET /api/v1/client/dashboard` |
| **III.6** | Supervision des flux + état des serveurs | ✅ (partiel) | KPIs + actuator (`/actuator/health`, `/metrics`) ; « état des serveurs » via métriques (CPU/mémoire non graphés — Grafana conseillé en prod) |
| **III.6** | Anti-fraude : bloquer, analyser, annuler ou valider manuellement | ✅ | `FraudAlert` + actions Valider/Rejeter/Revue dans `/admin/` |
| **III.6** | Journaux d'audit immuables (connexions, ajouts, validations) | ✅ | `audit_logs` append-only ; écriture via transaction dédiée |
| **III.6** | Stats : nb clients, nb comptes, volume, montant total, répartition par statut, opérations en attente/signalées | ✅ | `GET /api/v1/admin/stats` (+ `/stats/daily`, `/reports/operations`) |
| **III.7** | Authentification sécurisée | ✅ | BCrypt + JWT + OTP |
| **III.7** | Confirmation supplémentaire (opérations sensibles) | ✅ | OTP sur virement, approbation, ajout/suppression bénéficiaire |
| **III.7** | Autorisation du client sur le compte sélectionné | ✅ | `validateOwnership` (user → compte ; entreprise → compte PRO) |
| **III.7** | Vérification de la disponibilité des fonds | ✅ | À la création **et** à l'exécution (verrou pessimiste) |
| **III.7** | Anti-doublons | ✅ | Fenêtre 10 min (compte+bénéficiaire+montant) + clé d'idempotence |
| **III.7** | Comptes suspendus/bloqués bloqués à la création et à l'exécution | ✅ | Contrôle `AccountStatus` aux deux étapes |
| **III.7** | Enregistrement de chaque opération (traçabilité) | ✅ | `audit_logs` + horodatage `createdAt/executedAt` |
| **III.7** | Admin : consultation journaux d'activité et événements de sécurité | ✅ | Menu *Journaux d'audit* (filtre utilisateur) |
| **III.7** | Détection et signalement d'opérations inhabituelles | ✅ | `FraudDetectionService` (5 règles) |
| **IV.1** | < 500 ms par transaction (charge normale) | ✅ (conception) | Requêtes indexées, pools HikariCP (20), pas d'open-in-view ; à mesurer via JMeter en recette |
| **IV.1** | Scalabilité horizontale | ✅ (conception) | Stateless (JWT, pas de session serveur) → N instances derrière un load balancer ; asynchrone pour les lots |
| **IV.1** | Traitements lourds asynchrones (files RabbitMQ/Kafka) | ✅ (partiel) | `@Async` + thread pool dédié ; RabbitMQ fourni en option compose |
| **IV.2** | Disponibilité 24/7, SLA 99,99 % | ✅ (concept) | Conception stateless + healthcheck actuator + `restart: unless-stopped` ; SLA = engagement d'infra (redondance, PRA) à contractualiser |
| **IV.2** | Tolérance aux pannes / failover sans perte de données | ✅ (design) | Transactions ACID ; débit/crédit atomiques ; verrou pessimiste ; rollback sur échec SI central (recrédit) |
| **IV.3** | OWASP (injections, XSS, CSRF…) | ✅ | JPA (paramétré, anti-injection), validation Bean Validation, CSRF désactivé car API stateless + JWT hors cookies, en-têtes sécurité, pas de stacktrace exposée |
| **IV.3** | TLS 1.3 + AES-256 au repos | ✅ (partiel) | TLS via frontal (prod) ; AES-256 au repos non activé par défaut (déploiement chiffré disque/volume possible) |
| **IV.3** | PCI-DSS / DSP2 | ✅ (aide) | Audit, RBAC, MFA, plafonds, consentement — base de conformité ; audit PCI complet hors périmètre |
| **IV.4** | Documentation OpenAPI/Swagger | ✅ | `/swagger-ui.html` + `/v3/api-docs` (schéma palier, ex. `bearerAuth`) |
| **IV.4** | Clean Architecture, normes Java, tests ≥ 80 %, JUnit 5 | ✅ (partiel) | Couches strictes ; 39 tests unitaires verts ; JaCoCo câblé ; couverture des branches critiques élevée, mais 80 % global non mesuré/certifié |
| **IV.4** | Format JSON interopérable | ✅ | 100 % JSON (sauf exports PDF/XLSX et multipart CSV) |
| **IV.5** | Docker + comportement identique dev/test/prod | ✅ | Dockerfile multi-étapes + compose (profil h2 pour dev, postgres pour prod) |
| **IV.5** | Observabilité (santé temps réel, fuites mémoire, logs centralisés) | ✅ (partiel) | Actuator + logs structurés console (12-factor) ; Stack ELK/Prometheus+Grafana recommandée en prod (documentée) |
| **V.1** | Architecture en couches + séparation des responsabilités + modularité | ✅ | `controller → service → repository → entity` ; modules métier distincts |
| **V.2** | Stack imposée : Java, Spring Boot, Spring Security, Data JPA, PostgreSQL/MySQL, REST, JSON, JWT, Maven, Swagger, JUnit 5, Git | ✅ | Conforme (PostgreSQL par défaut, H2 pour les tests/démo ; MySQL possible via driver) |
| **V.3** | Spring Security, RBAC, HTTPS, BCrypt/Argon2, protection API, JWT maîtrisé, validation stricte, pas de fuite de secrets dans réponses/logs | ✅ | Voir section sécurité du README |
| **V.4** | Compatibilité navigateurs, OS, ordinateurs/tablettes/smartphones | ✅ | Dashboard responsive (CSS grid, viewport) |
| **V.5** | RGPD / vie privée ; confidentialité ; traçabilité ; LBC/FT ; conservation | ✅ (partiel) | Données minimales, audit conservé, IBANs masqués dans l'UI ; registre de traitement & DPO à établir côté banque ; rétention configurable par archivage |
| **VI** | Livrables : CDC, Manuel installation, Guide utilisateur, Code source, Application fonctionnelle | ✅ | CDC (dossier parent), `docs/MANUEL_INSTALLATION.md`, `docs/GUIDE_UTILISATEUR.md`, code source complet, livrable exécutable (`target/*.jar`, image Docker) |
| **Console** | Interface d'administration professionnelle, sans identifiants affichés | ✅ | Split-screen institutionnel, aucune donnée de connexion visible, déconnexion auto 15 min, horloge live, recherche globale, centre de notifications, reçu PDF imprimable + vérification publique du code anti-falsification (HMAC) |
| **Console** | 100 comptes seedés par catégories | ✅ | 48 COURANT + 28 EPARGNE + 21 PRO (entreprises) + 3 historiques = 100 comptes ; IBANs modulo 97 ; ~250 virements historiques sur 90 jours ; 3 admins + 4 agents |
| **VII** | Planning 12 semaines / 3 mois | ℹ️ | Concernant la gestion de projet — hors périmètre technique du livrable logiciel |

## Synthèse

- **Couvert** : ~95 % des exigences fonctionnelles et non-fonctionnelles, soit l'intégralité du périmètre technique du CDC.
- **Partiel / assumé** : TLS 1.3 effectif (via frontal), AES-256 au repos, RabbitMQ actif (option compose), couverture de tests certifiée 80 %, observabilité complète (ELK/Grafana), PCI-DSS complet.
- **Hors périmètre (explicitement exclu par le CDC)** : applications mobiles, site e-banking client, refonte du Core Banking System.

## Décisions d'architecture notables

1. **Blocage avant débit** : le moteur anti-fraude s'exécute **avant** tout mouvement de fonds — aucun virement suspect n'est débité sans validation humaine.
2. **Idempotence** : double protection contre les doublons (fenêtre temporelle + clé client) — exigence « empêcher les virements en double ».
3. **Verrouillage pessimiste** : cohérence des soldes même en concurrence (dernier arrivé voit le solde réel).
4. **Quorum B2B** : signatures de mandataires **distincts** ; le créateur ne peut pas auto-valider ; le rejet est définitif.
5. **Audit en transaction séparée** (`REQUIRES_NEW`) : la trace survit à un rollback métier — exigence de traçabilité LBC/FT.
