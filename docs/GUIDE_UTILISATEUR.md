# Guide Utilisateur — Plateforme de Virement Bancaire Sécurisé

Ce guide s'adresse aux trois profils d'utilisateurs :

1. **Client particulier (B2C)** — virements unitaires de la vie courante
2. **Mandataire d'entreprise (B2B)** — remises et validation hiérarchique
3. **Équipes bancaires (AGENT_BANCAIRE / ADMIN)** — supervision, anti-fraude, audit

---

## A. Client particulier

### A.1 Se connecter

`POST /api/v1/auth/login` avec email + mot de passe. La réponse contient un `token` JWT à joindre dans l'en-tête `Authorization: Bearer <token>` de chaque requête.

> Démonstration : `jean.martin@client.cm` / `Client@123`

Une notification « Nouvelle connexion » est créée à chaque connexion (visible dans *Notifications*).

### A.2 Consulter mes comptes

`GET /api/v1/accounts` → liste : IBAN, type (COURANT/ÉPARGNE), solde, plafonds journalier/hebdomadaire, statut.

### A.3 Ajouter un bénéficiaire (avec MFA)

1. `POST /api/v1/otp/request` `{ "purpose": "AJOUT_BENEFICIAIRE", "context": "<IBAN>" }`
2. `POST /api/v1/beneficiaries` `{ "name": "...", "iban": "...", "bankName": "...", "bic": "...", "otp": "<code>" }`

- L'**IBAN est vérifié** (clé modulo 97) avant enregistrement.
- Un **délai de carence de 48h** est appliqué : le bénéficiaire apparaît dans la liste mais tout virement vers lui est refusé jusqu'à `availableAt`.
- L'IBAN est **immuable** après création (modifier les autres champs via `PUT /api/v1/beneficiaries/{id}`).

### A.4 Faire un virement unitaire

1. `POST /api/v1/transfers`
   ```json
   { "sourceAccountId": 1, "beneficiaryId": 3, "amount": 75000, "motif": "Loyer novembre" }
   ```
   ou `"targetAccountId": <id>` pour un virement vers **votre propre compte** de la même banque.
   - Option : `"scheduledDate": "2026-12-01T08:00:00Z"` pour un **virement différé**.
   - Option anti-doublon : `"idempotencyKey": "mon-uuid-unique"` (rejouer la même clé ne crée pas de doublon).
2. Le système vérifie : compte actif, solde suffisant, plafonds jour/semaine, doublons (10 min), carence.
3. `POST /api/v1/otp/request` `{ "purpose": "CONFIRMATION_VIREMENT", "context": "<id du virement>" }`
4. `POST /api/v1/transfers/{id}/confirm` `{ "otp": "<code>" }`

Résultat possible : `EXECUTE` (exécution immédiate), `PROGRAMME` (différé), `BLOQUE_FRAUDE` (revue bancaire requise), `ECHOUE` (ex. solde devenu insuffisant).

### A.5 Suivre mes virements et télécharger un avis

- `GET /api/v1/transfers?status=EXECUTE&page=0&size=20&q=loyer` — historique filtrable (statut, période `from`/`to` ISO-8601, texte libre).
- `GET /api/v1/transfers/{id}/receipt.pdf` — **avis d'opération** PDF (référence, IBANs, montant, statut, horodatage).
- `GET /api/v1/transfers/export?format=xlsx` — export Excel (ou `format=pdf`).
- Statuts : `EN_ATTENTE_CONFIRMATION` → `EXECUTE` / `PROGRAMME` / `BLOQUE_FRAUDE` / `REJETE` / `ECHOUE` / `ANNULE`.
- Annulation possible avant exécution : `POST /api/v1/transfers/{id}/cancel`.

### A.6 Notifications

`GET /api/v1/notifications` (+ `/unread-count`, `POST /{id}/read`, `POST /read-all`). Vous êtes notifié à chaque événement : virement créé/exécuté/rejeté/échoué, connexion, ajout de bénéficiaire, alerte de sécurité.

### A.7 Tableau de bord client

`GET /api/v1/client/dashboard` → solde total, nombre de virements, 5 dernières opérations, comptes.

---

## B. Mandataire d'entreprise (B2B)

### B.1 Profil

Votre compte est lié à votre entreprise (ex : **SARL EKONDO & FILS**). Deux mandataires par défaut (`directeur@ekondo.cm`, `comptable@ekondo.cm`) doivent **tous les deux signer** un virement ≥ 1 000 000 XAF (une seule signature suffit sous ce seuil — paramètre `singleApprovalThreshold`).

### B.2 Initier un virement (comptable)

`POST /api/v1/transfers` — même format que le client. Le virement part en `EN_ATTENTE_APPROBATION` (jamais d'exécution directe). Les autres mandataires sont notifiés.

### B.3 Signer un virement (les deux mandataires, à tour de rôle)

1. `GET /api/v1/approvals/pending` — virements de votre entreprise en attente.
2. `POST /api/v1/otp/request` `{ "purpose": "APPROBATION_VIREMENT", "context": "<id du virement>" }`
3. `POST /api/v1/approvals/{id}/approve` `{ "otp": "<code>", "comment": "OK facture 118" }`
   - Chaque mandataire ne signe qu'une fois le virement ; il faut **2 signatures de mandataires distincts** (maker-checker : jamais une seule personne).
   - Le **quorum** atteint → exécution automatique (ou `PROGRAMME` si différé).
4. Rejet : `POST /api/v1/approvals/{id}/reject` `{ "otp": "<code>", "comment": "IBAN fournisseur incorrect" }`

### B.4 Remise de masse (salaires, fournisseurs)

1. Préparer un **CSV** : `nom;iban;montant;motif[;bic]` (une ligne par bénéficiaire ; le séparateur `,` est auto-détecté ; max 1000 lignes ; montant en XAF avec point ou virgule).

   ```csv
   Dupont Marie;CM6010000009876543210987654;450000;Salaire octobre 2026
   Fournisseur SCI;FR4812345678901234567890123;1200000;Facture 2026-118
   ```
2. `POST /api/v1/transfers/mass` (multipart) : champs `file` (le CSV) et `sourceAccountId` (compte PRO de l'entreprise).
3. Réponse **202 Accepted** + référence `BAT-…`. Le traitement est **asynchrone** : chaque ligne devient un virement `VIREMENT_MASSE` en `EN_ATTENTE_APPROBATION`.
4. Suivi : `GET /api/v1/transfers/mass/batches` et `…/batches/{id}` (nombre de réussites/échecs, statut `PARTIEL` possible).
5. Signature du lot : chaque mandataire fait `POST /api/v1/approvals/batch/{batchId}/approve-all` avec un OTP dont le contexte est `BATCH-<batchId>` (`POST /api/v1/otp/request` `{ "purpose": "APPROBATION_VIREMENT", "context": "BATCH-12" }`) — ou signe chaque virement individuellement.

> **Délai de carence** : non appliqué aux remises signées par l'entreprise (décision documentée) ; les IBANs restent contrôlés (modulo 97) et les échecs isolés ne bloquent pas le reste du lot.

---

## C. Équipes bancaires (AGENT_BANCAIRE / ADMIN)

### C.1 Tableau de bord d'administration

Ouvrir **http://localhost:8080/admin/** et se connecter (`agent@banque.cm` / `Agent@123` ou `admin@banque.cm` / `Admin@123`).

- **Tableau de bord** : clients, comptes, virements exécutés/en attente/bloqués, montant total, alertes ouvertes, répartition par statut, volume journalier (30 j).
- **Transactions** : recherche plein texte (référence, motif, IBAN, bénéficiaire) + filtre par statut + pagination ; export PDF/Excel.
- **Anti-fraude** : file des alertes (`OUVERTE` → `EN_REVUE` → `VALIDEE`/`ANNULEE`). Actions : **Valider** (le virement s'exécute) ou **Rejeter** (annulé, fonds non débités).
- **Clients & entreprises** : recherche, vision des comptes d'un client.
- **Comptes bancaires** : recherche, **suspension/réactivation** (avec motif).
- **Journaux d'audit** : toutes les actions (connexions, virements, validations, suspensions…), filtrables par utilisateur.

### C.2 Règles anti-fraude appliquées (bloquantes)

| Règle | Seuil (défaut) | Sévérité |
|---|---|---|
| `MONTANT_CRITIQUE` | ≥ 25 000 000 XAF | CRITIQUE (blocage) |
| `MONTANT_ELEVE` | ≥ 5 000 000 XAF | MOYENNE (alerte au tableau de bord) |
| `BENEFICIAIRE_RECENT` | ≥ 2 000 000 XAF vers bénéficiaire < 7 jours | HAUTE (blocage) |
| `VELOCITE_10MIN` | ≥ 3 virements en 10 minutes | HAUTE (blocage) |
| `HEURE_INHABITUELLE` | 00h–05h et ≥ 1 000 000 XAF | MOYENNE (alerte) |

Toute alerte **HAUTE/CRITIQUE** bloque avant tout débit : le compte n'est jamais débité tant que l'agent n'a pas validé.

### C.3 Remettre un reçu au client (impression en agence)

1. Menu *Transactions* → retrouver le virement (recherche par référence, IBAN, bénéficiaire ou date).
2. Icône **imprimante** en bout de ligne (ou ouvrir le détail avec l'icône œil → *Imprimer le reçu*).
3. Le **reçu PDF** s'ouvre dans un onglet : référence, date et heure complètes, compte débité, bénéficiaire, montant, statut, **code de vérification anti-falsification** (HMAC-SHA256 tronqué) et ligne de découpe.
4. Imprimer et remettre au client. Le client (ou un agent) peut ensuite vérifier l'authenticité du reçu via `GET /api/v1/public/verify?reference=…&amount=…&code=…` — la réponse confirme uniquement l'authenticité et le statut, sans exposer de données personnelles.

### C.4 Utilisateurs internes (3 admins + agents)

Menu *Utilisateurs internes* : liste des administrateurs et agents, changement de rôle (Administrateur/Agent), activation/désactivation avec motif (réservé aux ADMIN ; votre propre compte est protégé). Toute action est journalisée dans l'audit.

### C.5 Bonnes pratiques

- Ne jamais valider sans vérifier l'identité du client (rappel téléphonique au numéro enregistré).
- Consigner le motif du rejet (il est stocké dans l'audit).
- Les alertes `VALIDEE` sont conservées pour la conformité (LBC/FT) — ne pas les supprimer.
- Suspendre un compte uniquement pour motif documenté (fraude, opposition, décision de justice).
