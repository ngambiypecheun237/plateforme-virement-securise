/* =========================================================================
   Banque Démonstration — Console d'administration
   ========================================================================= */

const API = "/api/v1";
const TOKEN_KEY = "bde_admin_token";
const USER_KEY = "bde_admin_user";
const REMEMBER_KEY = "bde_admin_remembered_email";
const INACTIVITY_LIMIT_MS = 15 * 60 * 1000;   // déconnexion auto après 15 min

const $ = (id) => document.getElementById(id);

/* ------------------------------------------------------------------ session */
function token() { return sessionStorage.getItem(TOKEN_KEY) || localStorage.getItem(TOKEN_KEY); }

function saveSession(auth, rememberEmail) {
  sessionStorage.setItem(TOKEN_KEY, auth.token);   // par défaut : session onglet
  if (rememberEmail) localStorage.setItem(TOKEN_KEY, auth.token); else localStorage.removeItem(TOKEN_KEY);
  localStorage.setItem(USER_KEY, JSON.stringify({
    email: auth.email, fullName: auth.fullName, role: auth.role,
  }));
}

function clearSession() {
  [sessionStorage, localStorage].forEach(s => s.removeItem(TOKEN_KEY));
  localStorage.removeItem(USER_KEY);
}

function currentUser() {
  try { return JSON.parse(localStorage.getItem(USER_KEY) || "null"); } catch { return null; }
}

async function api(path, options = {}) {
  const headers = Object.assign({ "Content-Type": "application/json" }, options.headers || {});
  const t = token();
  if (t) headers["Authorization"] = "Bearer " + t;
  const response = await fetch(API + path, Object.assign({}, options, { headers }));
  if (response.status === 401) {
    clearSession();
    showLogin("Session expirée. Veuillez vous reconnecter.");
    throw new Error("Session expirée");
  }
  const ct = response.headers.get("content-type") || "";
  const body = ct.includes("application/json") ? await response.json() : null;
  if (!response.ok) {
    throw new Error(body && body.message ? body.message : "Erreur serveur (" + response.status + ")");
  }
  return body;
}

/* ------------------------------------------------------------------ helpers */
function formatAmount(value, currency) {
  return new Intl.NumberFormat("fr-FR", { maximumFractionDigits: 0 }).format(Number(value || 0))
    + " " + (currency || "XAF");
}
const DATETIME_FMT = new Intl.DateTimeFormat("fr-FR", {
  day: "2-digit", month: "2-digit", year: "numeric",
  hour: "2-digit", minute: "2-digit",
});
const LONG_FMT = new Intl.DateTimeFormat("fr-FR", {
  weekday: "long", day: "numeric", month: "long", year: "numeric", hour: "2-digit", minute: "2-digit",
});
const DATE_FMT = new Intl.DateTimeFormat("fr-FR", { day: "2-digit", month: "short", year: "numeric" });

function formatDateTime(iso) { return iso ? DATETIME_FMT.format(new Date(iso)) : "—"; }
function formatDateLong(iso) { return iso ? LONG_FMT.format(new Date(iso)) : "—"; }
function escapeHtml(value) {
  return String(value == null ? "" : value)
    .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;").replace(/'/g, "&#39;");
}

function toast(message, type) {
  const el = $("toast");
  el.textContent = message;
  el.className = "toast" + (type ? " " + type : "");
  el.hidden = false;
  clearTimeout(el._t);
  el._t = setTimeout(() => { el.hidden = true; }, 4200);
}

const STATUS_LABELS = {
  EXECUTE: "Exécuté",
  EN_ATTENTE_CONFIRMATION: "Attente confirmation",
  EN_ATTENTE_APPROBATION: "Attente approbation",
  PROGRAMME: "Programmé",
  BLOQUE_FRAUDE: "Bloqué (fraude)",
  REJETE: "Rejeté",
  ECHOUE: "Échoué",
  ANNULE: "Annulé",
};
const STATUS_BADGE = {
  EXECUTE: "badge-success",
  EN_ATTENTE_CONFIRMATION: "badge-warning",
  EN_ATTENTE_APPROBATION: "badge-warning",
  PROGRAMME: "badge-info",
  BLOQUE_FRAUDE: "badge-danger",
  REJETE: "badge-neutral",
  ECHOUE: "badge-neutral",
  ANNULE: "badge-neutral",
};
const SEVERITY_BADGE = { CRITIQUE: "badge-danger", HAUTE: "badge-danger", MOYENNE: "badge-warning", BASSE: "badge-info" };

const badge = (text, cls) => '<span class="badge ' + (cls || "badge-neutral") + '">' + escapeHtml(text) + "</span>";
const statusBadge = (s) => badge(STATUS_LABELS[s] || s, STATUS_BADGE[s] || "badge-neutral");

/* ------------------------------------------------------------- navigation */
const PAGE_TITLES = {
  dashboard: "Tableau de bord",
  transactions: "Transactions",
  fraud: "Anti-fraude",
  clients: "Clients & entreprises",
  accounts: "Comptes bancaires",
  users: "Utilisateurs internes",
  audit: "Journaux d'audit",
};

function showLogin(message) {
  $("app").classList.remove("visible");
  $("app").setAttribute("aria-hidden", "true");
  $("loginScreen").style.display = "grid";
  const err = $("loginError");
  if (message) { err.textContent = message; err.style.display = "block"; } else { err.style.display = "none"; }
}

function showApp() {
  const user = currentUser();
  $("loginScreen").style.display = "none";
  $("app").classList.add("visible");
  $("app").setAttribute("aria-hidden", "false");
  if (user) {
    $("userName").textContent = user.fullName || user.email;
    $("userRole").textContent = roleLabel(user.role);
    $("avatar").textContent = (user.fullName || user.email || "?").trim().charAt(0).toUpperCase();
  }
}

function roleLabel(role) {
  return ({ ADMIN: "Administrateur", AGENT_BANCAIRE: "Agent bancaire", CLIENT: "Client", ENTREPRISE: "Entreprise" })[role] || role || "—";
}

let currentPage = "dashboard";
function navigate(page) {
  currentPage = page;
  document.querySelectorAll(".page").forEach(p => p.classList.toggle("active", p.id === "page-" + page));
  document.querySelectorAll(".nav-item").forEach(n => n.classList.toggle("active", n.dataset.page === page));
  $("crumbCurrent").textContent = PAGE_TITLES[page] || page;
  if (page === "dashboard") loadDashboard();
  else if (page === "transactions") loadTransactions();
  else if (page === "fraud") loadFraudAlerts();
  else if (page === "clients") loadClients();
  else if (page === "accounts") loadAccounts();
  else if (page === "users") loadInternalUsers();
  else if (page === "audit") loadAudit();
}

document.querySelectorAll(".nav-item").forEach(item => {
  item.addEventListener("click", () => navigate(item.dataset.page));
});

/* ------------------------------------------------- horloge + inactivité */
function tickClock() {
  const now = new Date();
  $("clockTime").textContent = now.toLocaleTimeString("fr-FR", { hour: "2-digit", minute: "2-digit", second: "2-digit" });
  $("clockDate").textContent = now.toLocaleDateString("fr-FR", { weekday: "long", day: "numeric", month: "long", year: "numeric" });
}
setInterval(tickClock, 1000);

let inactivityTimer = null;
function resetInactivity() {
  clearTimeout(inactivityTimer);
  inactivityTimer = setTimeout(() => {
    clearSession();
    showLogin("Déconnexion automatique après 15 minutes d'inactivité.");
  }, INACTIVITY_LIMIT_MS);
}
["mousemove", "keydown", "click", "scroll"].forEach(evt => {
  window.addEventListener(evt, resetInactivity, { passive: true });
});

/* -------------------------------------------------------------- connexion */
const remembered = localStorage.getItem(REMEMBER_KEY);
if (remembered) { $("email").value = remembered; $("rememberEmail").checked = true; }

$("togglePassword").addEventListener("click", () => {
  const input = $("password");
  const show = input.type === "password";
  input.type = show ? "text" : "password";
  $("togglePassword").setAttribute("aria-pressed", String(show));
  $("eyeOpen").style.display = show ? "none" : "block";
  $("eyeClosed").style.display = show ? "block" : "none";
});

$("loginForm").addEventListener("submit", async (event) => {
  event.preventDefault();
  const btn = $("loginBtn");
  const err = $("loginError");
  err.style.display = "none";
  btn.disabled = true;
  btn.querySelector(".btn-label").textContent = "Vérification…";
  const email = $("email").value.trim();
  try {
    const auth = await api("/auth/login", {
      method: "POST",
      body: JSON.stringify({ email, password: $("password").value }),
    });
    const role = (auth.role || "").toUpperCase();
    if (role !== "ADMIN" && role !== "AGENT_BANCAIRE") {
      throw new Error("Accès réservé au personnel habilité (administrateurs et agents uniquement).");
    }
    const remember = $("rememberEmail").checked;
    if (remember) localStorage.setItem(REMEMBER_KEY, email); else localStorage.removeItem(REMEMBER_KEY);
    saveSession(auth, remember);
    showApp();
    resetInactivity();
    navigate("dashboard");
    toast("Bienvenue, " + (auth.fullName || auth.email), "success");
  } catch (e) {
    err.textContent = e.message;
    err.style.display = "block";
  } finally {
    btn.disabled = false;
    btn.querySelector(".btn-label").textContent = "Se connecter";
  }
});

$("logoutBtn").addEventListener("click", () => { clearSession(); showLogin(); });

/* ---------------------------------------------------------- notifications */
let notifPoll = null;

async function loadUnreadCount() {
  try {
    const data = await api("/notifications/unread-count");
    const badgeEl = $("bellBadge");
    const count = Number(data.unread || 0);
    badgeEl.hidden = count === 0;
    badgeEl.textContent = count > 99 ? "99+" : String(count);
  } catch { /* silencieux */ }
}

async function loadNotifications() {
  const list = $("notifList");
  try {
    const data = await api("/notifications?page=0&size=8");
    const items = data.content || [];
    if (!items.length) {
      list.innerHTML = '<p class="notif-empty">Aucune notification pour le moment.</p>';
      return;
    }
    list.innerHTML = items.map(n =>
      '<div class="notif-item' + (n.read ? "" : " unread") + '">' +
        '<span class="notif-dot"></span>' +
        '<div class="notif-body"><b>' + escapeHtml(n.title) + "</b>" +
          "<p>" + escapeHtml(n.message) + "</p>" +
          "<small>" + formatDateTime(n.createdAt) + "</small>" +
        "</div>" +
      "</div>"
    ).join("");
  } catch (e) {
    list.innerHTML = '<p class="notif-empty">Notifications indisponibles.</p>';
  }
}

$("bellBtn").addEventListener("click", async (event) => {
  event.stopPropagation();
  const panel = $("notifPanel");
  const willOpen = panel.hidden;
  panel.hidden = !willOpen;
  $("bellBtn").setAttribute("aria-expanded", String(willOpen));
  if (willOpen) { await loadNotifications(); }
});
document.addEventListener("click", (event) => {
  if (!event.target.closest(".bell-wrap")) $("notifPanel").hidden = true;
});
$("notifReadAll").addEventListener("click", async () => {
  try {
    await api("/notifications/read-all", { method: "POST" });
    if (!$("bellBadge")) return;
    $("bellBadge").hidden = true;
    await loadNotifications();
    toast("Toutes les notifications sont marquées comme lues.", "success");
  } catch (e) { toast(e.message, "error"); }
});
$("notifGoAudit").addEventListener("click", () => { $("notifPanel").hidden = true; navigate("audit"); });

/* ------------------------------------------------------ recherche globale */
const searchInput = $("globalSearch");
const searchResults = $("searchResults");
let searchTimer = null;

searchInput.addEventListener("input", () => {
  clearTimeout(searchTimer);
  const q = searchInput.value.trim();
  if (q.length < 2) { searchResults.hidden = true; return; }
  searchTimer = setTimeout(() => globalSearch(q), 220);
});
searchInput.addEventListener("focus", () => { if (searchResults.innerHTML) searchResults.hidden = false; });
document.addEventListener("click", (e) => { if (!e.target.closest(".topbar-search")) searchResults.hidden = true; });

async function globalSearch(q) {
  try {
    const [clients, accounts, tx] = await Promise.all([
      api("/admin/clients?q=" + encodeURIComponent(q)).catch(() => []),
      api("/admin/accounts?q=" + encodeURIComponent(q)).catch(() => []),
      api("/admin/transactions?q=" + encodeURIComponent(q) + "&page=0&size=4").catch(() => ({ content: [] })),
    ]);
    const sections = [];
    if (clients.length) {
      sections.push('<p class="search-group-label">Clients</p>' + clients.slice(0, 4).map(u =>
        '<button class="search-item" data-goto="clients" data-q="' + escapeHtml(u.email) + '"><span><b>' + escapeHtml(u.fullName) + '</b><small>' + escapeHtml(u.email) + '</small></span></button>').join(""));
    }
    if (accounts.length) {
      sections.push('<p class="search-group-label">Comptes</p>' + accounts.slice(0, 4).map(a =>
        '<button class="search-item" data-goto="accounts" data-q="' + escapeHtml(a.iban) + '"><span><b class="num">' + escapeHtml(a.iban) + '</b><small>' + escapeHtml(a.titulaire) + '</small></span></button>').join(""));
    }
    if ((tx.content || []).length) {
      sections.push('<p class="search-group-label">Virements</p>' + tx.content.slice(0, 4).map(t =>
        '<button class="search-item" data-goto="transactions" data-q="' + escapeHtml(t.reference) + '"><span><b>' + escapeHtml(t.reference) + '</b><small>' + escapeHtml(formatAmount(t.amount, t.currency)) + ' — ' + escapeHtml(STATUS_LABELS[t.status] || t.status) + '</small></span></button>').join(""));
    }
    searchResults.innerHTML = sections.length ? sections.join("") : '<p class="search-empty">Aucun résultat pour « ' + escapeHtml(q) + ' »</p>';
    searchResults.hidden = false;
  } catch (e) {
    searchResults.hidden = true;
  }
}

searchResults.addEventListener("click", (event) => {
  const btn = event.target.closest(".search-item");
  if (!btn) return;
  const page = btn.dataset.goto;
  const q = btn.dataset.q || "";
  searchResults.hidden = true;
  if (page === "clients") { $("clientSearch").value = q; navigate("clients"); loadClients(); }
  else if (page === "accounts") { $("accountSearch").value = q; navigate("accounts"); loadAccounts(); }
  else if (page === "transactions") { $("txSearch").value = q; txState.q = q; txState.page = 0; navigate("transactions"); }
});

/* ------------------------------------------------------- tableau de bord */
const STATUS_COLORS = {
  EXECUTE: "#177245",
  EN_ATTENTE_CONFIRMATION: "#B45309",
  EN_ATTENTE_APPROBATION: "#D97706",
  PROGRAMME: "#1D4ED8",
  BLOQUE_FRAUDE: "#B42318",
  REJETE: "#8A94A6",
  ECHOUE: "#5F6E80",
  ANNULE: "#C9CED7",
};

async function loadDashboard() {
  try {
    const [stats, series, fraud, recent] = await Promise.all([
      api("/admin/stats"),
      api("/admin/stats/daily?days=30"),
      api("/admin/fraud-alerts?status=OUVERTE&page=0&size=3"),
      api("/admin/transactions?page=0&size=6"),
    ]);

    renderKpis(stats);
    renderStatusChart(stats.repartitionParStatut || {});
    renderDailyChart(series || []);
    renderPendingFraud(fraud.content || []);
    renderRecentTransactions(recent.content || []);

    const openAlerts = Number(stats.alertesFraudeOuvertes || 0);
    const badgeEl = $("fraudBadge");
    badgeEl.hidden = openAlerts === 0;
    badgeEl.textContent = openAlerts;
  } catch (e) {
    toast(e.message, "error");
  }
}

function kpiCard(label, value, hint, kind) {
  return '<div class="kpi-card ' + (kind || "") + '">' +
    '<div class="kpi-label">' + escapeHtml(label) + "</div>" +
    '<div class="kpi-value">' + escapeHtml(value) + "</div>" +
    '<div class="kpi-hint">' + escapeHtml(hint) + "</div>" +
  "</div>";
}

function renderKpis(stats) {
  $("kpiGrid").innerHTML = [
    kpiCard("Clients", String(stats.totalClients ?? "—"), "particuliers actifs", ""),
    kpiCard("Comptes bancaires", String(stats.totalComptes ?? "—"), (stats.comptesSuspendus ?? 0) + " suspendu(s) / bloqué(s)", "gold"),
    kpiCard("Virements exécutés", String(stats.virementsExecutes ?? "—"), (stats.virementsEnAttente ?? 0) + " en attente de traitement", "green"),
    kpiCard("Montant total exécuté", formatAmount(stats.montantTotalExecute, stats.devise), "cumul toutes périodes", ""),
    kpiCard("Alertes anti-fraude", String(stats.alertesFraudeOuvertes ?? 0), "en attente de revue", "red"),
    kpiCard("Bloqués anti-fraude", String(stats.virementsBloquesFraude ?? 0), "jamais débités sans validation", "red"),
  ].join("");
}

function renderStatusChart(byStatus) {
  const container = $("statusChart");
  const entries = Object.entries(byStatus);
  if (!entries.length) {
    container.innerHTML = '<div class="empty">Aucune donnée disponible.</div>';
    $("statusTotal").textContent = "";
    return;
  }
  const total = entries.reduce((s, [, v]) => s + Number(v), 0);
  $("statusTotal").textContent = total + " virement(s)";
  const max = Math.max(...entries.map(([, v]) => Number(v)), 1);
  container.innerHTML = entries
    .sort((a, b) => Number(b[1]) - Number(a[1]))
    .map(([status, count]) =>
      '<div class="status-row">' +
        '<span class="lbl" title="' + escapeHtml(status) + '">' + escapeHtml(STATUS_LABELS[status] || status) + "</span>" +
        '<span class="status-bar"><span class="status-fill" style="width:' + Math.round((Number(count) / max) * 100) + '%;background:' + (STATUS_COLORS[status] || "#174A6E") + '"></span></span>' +
        '<span class="status-count">' + count + "</span>" +
      "</div>"
    ).join("");
}

function renderDailyChart(series) {
  const chart = $("dailyChart");
  if (!series.length) {
    chart.innerHTML = '<div class="chart-empty">Aucun virement exécuté sur la période.</div>';
    return;
  }
  const max = Math.max(...series.map(p => Number(p.volume || 0)), 1);
  chart.innerHTML = series.map(point => {
    const volume = Number(point.volume || 0);
    const height = Math.max(Math.round((volume / max) * 160), 4);
    const date = String(point.date || "").slice(5, 10);
    return '<div class="chart-col" title="' + escapeHtml(point.date) + ' — ' + formatAmount(volume, "XAF") + ' (' + point.count + ' virement(s))">' +
      '<div class="chart-bar" style="height:' + height + 'px"></div>' +
      '<div class="chart-lbl">' + escapeHtml(date) + "</div>" +
    "</div>";
  }).join("");
}

function renderPendingFraud(alerts) {
  const container = $("pendingFraudList");
  if (!alerts.length) {
    container.innerHTML = '<div class="empty">Aucune alerte en attente. Le système est sous contrôle.</div>';
    return;
  }
  container.innerHTML = alerts.map(a =>
    '<div class="pending-item' + (a.severity === "MOYENNE" || a.severity === "BASSE" ? " moyenne" : "") + '">' +
      '<div class="pi-body">' +
        "<b>" + escapeHtml(a.reference) + " — " + formatAmount(a.montant, a.devise) + "</b>" +
        "<span>" + escapeHtml(a.rule) + " · " + formatDateTime(a.createdAt) + "</span>" +
      "</div>" +
      '<div class="pi-actions">' +
        '<button class="btn btn-primary btn-sm" data-fraud-validate="' + a.id + '">Valider</button>' +
        '<button class="btn btn-ghost btn-sm" data-fraud-reject="' + a.id + '">Rejeter</button>' +
      "</div>" +
    "</div>"
  ).join("");
}

function renderRecentTransactions(list) {
  const tbody = $("recentTxBody");
  if (!list.length) {
    tbody.innerHTML = '<tr><td colspan="5" class="empty">Aucun virement pour le moment.</td></tr>';
    return;
  }
  tbody.innerHTML = list.map(t =>
    "<tr>" +
      '<td><span class="mono" style="font-family:Consolas,monospace;font-size:11px">' + escapeHtml(t.reference) + "</span></td>" +
      "<td>" + escapeHtml(formatDateTime(t.createdAt)) + "</td>" +
      "<td>" + escapeHtml(t.beneficiaryName || (t.targetIban ? "Compte propre" : "—")) + "</td>" +
      '<td class="num">' + escapeHtml(formatAmount(t.amount, t.currency)) + "</td>" +
      "<td>" + statusBadge(t.status) + "</td>" +
    "</tr>"
  ).join("");
}

$("goFraud").addEventListener("click", () => navigate("fraud"));
$("goTransactions").addEventListener("click", () => navigate("transactions"));
$("pendingFraudList").addEventListener("click", async (event) => {
  const validateBtn = event.target.closest("[data-fraud-validate]");
  const rejectBtn = event.target.closest("[data-fraud-reject]");
  if (validateBtn) {
    if (!confirm("Valider ce virement ? Les fonds seront débités immédiatement.")) return;
    try { await api("/admin/fraud-alerts/" + validateBtn.dataset.fraudValidate + "/validate", { method: "POST" }); toast("Virement validé et exécuté.", "success"); loadDashboard(); }
    catch (e) { toast(e.message, "error"); }
  } else if (rejectBtn) {
    const comment = prompt("Motif du rejet (optionnel) :", "");
    if (comment === null) return;
    try { await api("/admin/fraud-alerts/" + rejectBtn.dataset.fraudReject + "/reject", { method: "POST", body: JSON.stringify({ comment }) }); toast("Virement rejeté, fonds non débités.", "success"); loadDashboard(); }
    catch (e) { toast(e.message, "error"); }
  }
});

/* ------------------------------------------------------------ transactions */
const txState = { page: 0, size: 14, status: "", q: "", from: "", to: "" };
let currentTx = null;
let currentDrawerId = null;

async function loadTransactions() {
  const tbody = $("txTableBody");
  tbody.innerHTML = '<tr><td colspan="7" class="loading">Chargement…</td></tr>';
  const params = new URLSearchParams({ page: txState.page, size: txState.size });
  if (txState.status) params.set("status", txState.status);
  if (txState.q) params.set("q", txState.q);
  if (txState.from) params.set("from", txState.from + "T00:00:00Z");
  if (txState.to) params.set("to", txState.to + "T23:59:59Z");
  try {
    const data = await api("/admin/transactions?" + params.toString());
    const rows = (data.content || []).map(t =>
      "<tr>" +
        '<td><span style="font-family:Consolas,monospace;font-size:11px">' + escapeHtml(t.reference) + "</span></td>" +
        "<td>" + escapeHtml(formatDateTime(t.createdAt)) + "</td>" +
        "<td>" + escapeHtml(t.beneficiaryName || (t.targetIban ? "Compte propre " + t.targetIban : "—")) + "</td>" +
        '<td><span style="font-family:Consolas,monospace;font-size:11px">' + escapeHtml(t.sourceIban) + "</span></td>" +
        '<td class="num"><strong>' + escapeHtml(formatAmount(t.amount, t.currency)) + "</strong></td>" +
        "<td>" + statusBadge(t.status) + "</td>" +
        '<td><div class="row-actions">' +
          '<button class="icon-action" data-tx-view="' + t.id + '" title="Voir le détail" aria-label="Voir le détail"><svg viewBox="0 0 24 24" width="16" height="16" fill="none"><path d="M2.5 12S6 5.8 12 5.8 21.5 12 21.5 12 18 18.2 12 18.2 2.5 12 2.5 12Z" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round"/><circle cx="12" cy="12" r="3" stroke="currentColor" stroke-width="1.6"/></svg></button>' +
          '<button class="icon-action" data-tx-receipt="' + t.id + '" title="Imprimer le reçu (PDF)" aria-label="Imprimer le reçu"><svg viewBox="0 0 24 24" width="16" height="16" fill="none"><path d="M7 8V3h10v5M7 17H4a1 1 0 0 1-1-1v-6a3 3 0 0 1 3-3h12a3 3 0 0 1 3 3v6a1 1 0 0 1-1 1h-3m-14 0v4h14v-4H7Z" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round"/></svg></button>' +
        "</div></td>" +
      "</tr>"
    ).join("");
    tbody.innerHTML = rows || '<tr><td colspan="7" class="empty">Aucune transaction ne correspond aux filtres.</td></tr>';
    $("txPageInfo").textContent = "Page " + (data.number + 1) + " / " + Math.max(data.totalPages, 1);
    $("txTotal").textContent = data.totalElements + " résultat(s)";
    $("txPrev").disabled = data.first;
    $("txNext").disabled = data.last;
    currentTx = data.content || [];
  } catch (e) {
    tbody.innerHTML = '<tr><td colspan="7" class="empty">Erreur de chargement.</td></tr>';
    toast(e.message, "error");
  }
}

$("txApply").addEventListener("click", () => {
  txState.page = 0;
  txState.status = $("txStatus").value;
  txState.q = $("txSearch").value.trim();
  txState.from = $("txFrom").value;
  txState.to = $("txTo").value;
  loadTransactions();
});
$("txSearch").addEventListener("keydown", e => { if (e.key === "Enter") $("txApply").click(); });
$("txPrev").addEventListener("click", () => { if (txState.page > 0) { txState.page--; loadTransactions(); } });
$("txNext").addEventListener("click", () => { txState.page++; loadTransactions(); });

/* --- Tiroir de detail --- */
async function openTransferDrawer(id) {
  const t = (currentTx || []).find(x => String(x.id) === String(id));
  $("txDrawerOverlay").hidden = false;
  $("txDrawer").classList.add("open");
  $("txDrawer").setAttribute("aria-hidden", "false");
  $("drawerReference").textContent = t ? t.reference : "Virement #" + id;
  $("drawerAmount").textContent = t ? formatAmount(t.amount, t.currency) : "—";
  $("drawerStatus").outerHTML = t
    ? '<span id="drawerStatus" class="badge ' + (STATUS_BADGE[t.status] || "badge-neutral") + '">' + escapeHtml(STATUS_LABELS[t.status] || t.status) + "</span>"
    : '<span id="drawerStatus" class="badge badge-neutral">—</span>';
  currentDrawerId = t ? t.id : null;
  if (!t) { $("drawerMeta").innerHTML = ""; $("drawerTimeline").innerHTML = ""; return; }

  $("drawerMeta").innerHTML = [
    ["Référence", t.reference],
    ["Date et heure", formatDateLong(t.createdAt)],
    ["Compte débité", t.sourceIban],
    ["Bénéficiaire", t.beneficiaryName || (t.targetIban ? "Compte propre " + t.targetIban : "—")],
    ["Montant", formatAmount(t.amount, t.currency)],
    ["Motif", t.motif || "—"],
    ["Type", t.type === "VIREMENT_MASSE" ? "Virement de masse (entreprise)" : (t.type === "VIREMENT_UNITAIRE_INTERNE" ? "Virement interne" : "Virement externe")],
  ].map(([k, v]) => "<dt>" + escapeHtml(k) + "</dt><dd>" + escapeHtml(v) + "</dd>").join("");

  const steps = [];
  steps.push({ label: "Virement créé", when: t.createdAt, state: "done" });
  if (t.executedAt) steps.push({ label: "Virement exécuté", when: t.executedAt, state: "done" });
  else if (t.status === "BLOQUE_FRAUDE") steps.push({ label: "Bloqué par l'anti-fraude — revue requise", when: t.createdAt, state: "blocked" });
  else if (t.status === "EN_ATTENTE_APPROBATION") steps.push({ label: "En attente des signatures des mandataires", when: t.createdAt, state: "" });
  else if (t.status === "EN_ATTENTE_CONFIRMATION") steps.push({ label: "En attente du code OTP du client", when: t.createdAt, state: "" });
  else if (t.status === "PROGRAMME") steps.push({ label: "Programmé — exécution différée planifiée", when: t.scheduledDate || t.createdAt, state: "" });
  else if (t.status === "REJETE" || t.status === "ANNULE" || t.status === "ECHOUE") steps.push({ label: "Virement " + (STATUS_LABELS[t.status] || t.status).toLowerCase(), when: t.createdAt, state: "blocked" });
  $("drawerTimeline").innerHTML = steps.map(s =>
    '<li class="' + s.state + '"><b>' + escapeHtml(s.label) + "</b><small>" + escapeHtml(formatDateTime(s.when)) + "</small></li>"
  ).join("");
}

function closeTransferDrawer() {
  $("txDrawer").classList.remove("open");
  $("txDrawer").setAttribute("aria-hidden", "true");
  $("txDrawerOverlay").hidden = true;
}
$("drawerClose").addEventListener("click", closeTransferDrawer);
$("txDrawerOverlay").addEventListener("click", closeTransferDrawer);
document.addEventListener("keydown", e => { if (e.key === "Escape") closeTransferDrawer(); });

$("txTableBody").addEventListener("click", (event) => {
  const viewBtn = event.target.closest("[data-tx-view]");
  const receiptBtn = event.target.closest("[data-tx-receipt]");
  if (viewBtn) openTransferDrawer(viewBtn.dataset.txView);
  else if (receiptBtn) printReceipt(receiptBtn.dataset.txReceipt);
});
$("drawerPrintReceipt").addEventListener("click", () => {
  if (currentDrawerId != null) printReceipt(currentDrawerId);
});
$("drawerCopyRef").addEventListener("click", async () => {
  try { await navigator.clipboard.writeText($("drawerReference").textContent); toast("Référence copiée.", "success"); }
  catch { toast("Copie impossible.", "error"); }
});

async function printReceipt(transferId) {
  try {
    const response = await fetch(API + "/transfers/" + transferId + "/receipt.pdf", {
      headers: { Authorization: "Bearer " + token() },
    });
    if (!response.ok) throw new Error("Reçu indisponible (" + response.status + ")");
    const blob = await response.blob();
    const url = URL.createObjectURL(blob);
    const win = window.open(url, "_blank", "noopener");
    if (win) {
      win.addEventListener("load", () => { try { win.print(); } catch { /* impression manuelle */ } });
    } else {
      // Popup bloquee : telecharger le PDF
      const a = document.createElement("a");
      a.href = url;
      a.download = "recu-virement-" + transferId + ".pdf";
      document.body.appendChild(a);
      a.click();
      a.remove();
    }
    setTimeout(() => URL.revokeObjectURL(url), 60000);
    toast("Reçu ouvert dans un nouvel onglet (impression lancée).", "success");
  } catch (e) {
    toast(e.message, "error");
  }
}

/* --- Exports --- */
async function downloadExport(format) {
  const params = new URLSearchParams({ format });
  if (txState.status) params.set("status", txState.status);
  if (txState.q) params.set("q", txState.q);
  if (txState.from) params.set("from", txState.from + "T00:00:00Z");
  if (txState.to) params.set("to", txState.to + "T23:59:59Z");
  try {
    const response = await fetch(API + "/transfers/export?" + params.toString(), {
      headers: { Authorization: "Bearer " + token() },
    });
    if (!response.ok) throw new Error("Export impossible (" + response.status + ")");
    const blob = await response.blob();
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = format === "pdf" ? "historique-virements.pdf" : "historique-virements.xlsx";
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
    toast("Export généré.", "success");
  } catch (e) { toast(e.message, "error"); }
}
$("exportPdfBtn").addEventListener("click", () => downloadExport("pdf"));
$("exportXlsxBtn").addEventListener("click", () => downloadExport("xlsx"));

/* -------------------------------------------------------------- anti-fraude */
async function loadFraudAlerts() {
  const tbody = $("fraudTableBody");
  tbody.innerHTML = '<tr><td colspan="7" class="loading">Chargement…</td></tr>';
  const status = $("fraudStatus").value;
  const params = new URLSearchParams({ page: 0, size: 40 });
  if (status) params.set("status", status);
  try {
    const data = await api("/admin/fraud-alerts?" + params.toString());
    const rows = (data.content || []).map(a => {
      const actions = (a.status === "OUVERTE" || a.status === "EN_REVUE")
        ? '<button class="btn btn-primary btn-sm" data-action="validate" data-id="' + a.id + '">Valider</button> ' +
          '<button class="btn btn-ghost btn-sm" data-action="reject" data-id="' + a.id + '">Rejeter</button>'
        : badge(a.status, "badge-neutral");
      return "<tr>" +
        '<td style="font-family:Consolas,monospace;font-size:11px">' + escapeHtml(a.rule) + "</td>" +
        "<td>" + badge(a.severity, SEVERITY_BADGE[a.severity] || "badge-neutral") + "</td>" +
        '<td style="font-family:Consolas,monospace;font-size:11px">' + escapeHtml(a.reference) + "</td>" +
        "<td>" + escapeHtml(formatDateTime(a.createdAt)) + "</td>" +
        '<td class="num">' + escapeHtml(formatAmount(a.montant, a.devise)) + "</td>" +
        "<td>" + escapeHtml(a.initiator) + "</td>" +
        "<td>" + actions + "</td>" +
      "</tr>";
    }).join("");
    tbody.innerHTML = rows || '<tr><td colspan="7" class="empty">Aucune alerte dans ce filtre.</td></tr>';
  } catch (e) {
    tbody.innerHTML = '<tr><td colspan="7" class="empty">Erreur de chargement.</td></tr>';
    toast(e.message, "error");
  }
}
$("fraudStatus").addEventListener("change", loadFraudAlerts);
$("fraudTableBody").addEventListener("click", async (event) => {
  const btn = event.target.closest("button[data-action]");
  if (!btn) return;
  const id = btn.dataset.id;
  if (btn.dataset.action === "validate") {
    if (!confirm("Valider ce virement ? Les fonds seront débités immédiatement.")) return;
    try { await api("/admin/fraud-alerts/" + id + "/validate", { method: "POST" }); toast("Virement validé et exécuté.", "success"); loadFraudAlerts(); loadUnreadCount(); }
    catch (e) { toast(e.message, "error"); }
  } else if (btn.dataset.action === "reject") {
    const comment = prompt("Motif du rejet (optionnel) :", "");
    if (comment === null) return;
    try { await api("/admin/fraud-alerts/" + id + "/reject", { method: "POST", body: JSON.stringify({ comment }) }); toast("Virement rejeté.", "success"); loadFraudAlerts(); }
    catch (e) { toast(e.message, "error"); }
  }
});

/* ---------------------------------------------------------------- clients */
async function loadClients() {
  const tbody = $("clientTableBody");
  tbody.innerHTML = '<tr><td colspan="7" class="loading">Chargement…</td></tr>';
  const q = $("clientSearch").value.trim();
  const role = $("clientRole").value;
  const params = new URLSearchParams();
  if (q) params.set("q", q);
  if (role) params.set("role", role);
  try {
    const users = await api("/admin/clients?" + params.toString());
    tbody.innerHTML = users.map(u =>
      "<tr>" +
        "<td><strong>" + escapeHtml(u.fullName) + "</strong></td>" +
        '<td style="font-family:Consolas,monospace;font-size:11px">' + escapeHtml(u.email) + "</td>" +
        "<td>" + escapeHtml(u.phone || "—") + "</td>" +
        "<td>" + badge(u.role === "ENTREPRISE" ? "Entreprise" : "Particulier", u.role === "ENTREPRISE" ? "badge-gold" : "badge-info") + "</td>" +
        "<td>" + escapeHtml(u.company || "—") + "</td>" +
        "<td>" + (u.active ? badge("Actif", "badge-success") : badge("Inactif", "badge-danger")) + "</td>" +
        '<td><div class="row-actions"><button class="icon-action" data-view-accounts="' + escapeHtml(u.email) + '" title="Voir ses comptes" aria-label="Voir ses comptes"><svg viewBox="0 0 24 24" width="16" height="16" fill="none"><rect x="2.5" y="6" width="19" height="12" rx="2.5" stroke="currentColor" stroke-width="1.6"/><path d="M2.5 10h19" stroke="currentColor" stroke-width="1.6"/></svg></button></div></td>' +
      "</tr>"
    ).join("") || '<tr><td colspan="7" class="empty">Aucun résultat.</td></tr>';
  } catch (e) {
    tbody.innerHTML = '<tr><td colspan="7" class="empty">Erreur de chargement.</td></tr>';
    toast(e.message, "error");
  }
}
$("clientApply").addEventListener("click", loadClients);
$("clientSearch").addEventListener("keydown", e => { if (e.key === "Enter") loadClients(); });
$("clientTableBody").addEventListener("click", (event) => {
  const btn = event.target.closest("[data-view-accounts]");
  if (!btn) return;
  $("accountSearch").value = btn.dataset.viewAccounts;
  navigate("accounts");
  loadAccounts();
});

/* ---------------------------------------------------------------- comptes */
const accountState = { rows: [], page: 0, pageSize: 12 };

async function loadAccounts() {
  const tbody = $("accountTableBody");
  tbody.innerHTML = '<tr><td colspan="7" class="loading">Chargement…</td></tr>';
  const params = new URLSearchParams();
  const q = $("accountSearch").value.trim();
  const type = $("accountType").value;
  const status = $("accountStatus").value;
  if (q) params.set("q", q);
  if (type) params.set("type", type);
  if (status) params.set("status", status);
  try {
    accountState.rows = await api("/admin/accounts?" + params.toString());
    accountState.page = 0;
    $("accountsCount").textContent = String(accountState.rows.length);
    renderAccountPage();
  } catch (e) {
    tbody.innerHTML = '<tr><td colspan="7" class="empty">Erreur de chargement.</td></tr>';
    toast(e.message, "error");
  }
}

function renderAccountPage() {
  const tbody = $("accountTableBody");
  const start = accountState.page * accountState.pageSize;
  const slice = accountState.rows.slice(start, start + accountState.pageSize);
  tbody.innerHTML = slice.map(a => {
    const active = a.status === "ACTIVE";
    const statusCls = active ? "badge-success" : (a.status === "SUSPENDED" ? "badge-warning" : (a.status === "BLOCKED" ? "badge-danger" : "badge-neutral"));
    const action = active
      ? '<button class="btn btn-ghost btn-sm" data-suspend="' + a.id + '">Suspendre</button>'
      : '<button class="btn btn-primary btn-sm" data-activate="' + a.id + '">Réactiver</button>';
    return "<tr>" +
      '<td style="font-family:Consolas,monospace;font-size:11px">' + escapeHtml(a.iban) + "</td>" +
      "<td>" + escapeHtml(a.type) + "</td>" +
      "<td>" + escapeHtml(a.titulaire) + "</td>" +
      '<td class="num">' + escapeHtml(formatAmount(a.balance, a.currency)) + "</td>" +
      '<td class="num">' + escapeHtml(formatAmount(a.dailyLimit, a.currency)) + "</td>" +
      "<td>" + badge(a.status, statusCls) + "</td>" +
      "<td>" + action + "</td>" +
    "</tr>";
  }).join("") || '<tr><td colspan="7" class="empty">Aucun compte ne correspond aux filtres.</td></tr>';
  const totalPages = Math.max(1, Math.ceil(accountState.rows.length / accountState.pageSize));
  $("accountPageInfo").textContent = "Page " + (accountState.page + 1) + " / " + totalPages;
  $("accountPrev").disabled = accountState.page === 0;
  $("accountNext").disabled = accountState.page >= totalPages - 1;
}

$("accountApply").addEventListener("click", loadAccounts);
$("accountSearch").addEventListener("keydown", e => { if (e.key === "Enter") loadAccounts(); });
$("accountPrev").addEventListener("click", () => { if (accountState.page > 0) { accountState.page--; renderAccountPage(); } });
$("accountNext").addEventListener("click", () => { accountState.page++; renderAccountPage(); });
$("accountTableBody").addEventListener("click", async (event) => {
  const suspendBtn = event.target.closest("[data-suspend]");
  const activateBtn = event.target.closest("[data-activate]");
  if (suspendBtn) {
    const reason = prompt("Motif de la suspension :", "Activité inhabituelle détectée");
    if (reason === null) return;
    try { await api("/admin/accounts/" + suspendBtn.dataset.suspend + "/suspend", { method: "POST", body: JSON.stringify({ reason }) }); toast("Compte suspendu.", "success"); loadAccounts(); }
    catch (e) { toast(e.message, "error"); }
  } else if (activateBtn) {
    try { await api("/admin/accounts/" + activateBtn.dataset.activate + "/activate", { method: "POST" }); toast("Compte réactivé.", "success"); loadAccounts(); }
    catch (e) { toast(e.message, "error"); }
  }
});

$("exportAccountsBtn").addEventListener("click", () => {
  const rows = accountState.rows;
  if (!rows.length) { toast("Aucune donnée à exporter.", "error"); return; }
  const header = ["IBAN", "Type", "Titulaire", "Solde", "Devise", "Plafond jour", "Statut"];
  const lines = [header.join(";")].concat(rows.map(a =>
    [a.iban, a.type, '"' + String(a.titulaire).replace(/"/g, '""') + '"', String(a.balance).replace(".", ","), a.currency, String(a.dailyLimit).replace(".", ","), a.status].join(";")
  ));
  const blob = new Blob(["﻿" + lines.join("\r\n")], { type: "text/csv;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = "comptes-bancaires.csv";
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
  toast(rows.length + " ligne(s) exportée(s).", "success");
});

/* --------------------------------------------------------- utilisateurs */
async function loadInternalUsers() {
  const tbody = $("usersTableBody");
  tbody.innerHTML = '<tr><td colspan="5" class="loading">Chargement…</td></tr>';
  try {
    const [admins, agents] = await Promise.all([
      api("/admin/clients?role=ADMIN"),
      api("/admin/clients?role=AGENT_BANCAIRE"),
    ]);
    const me = currentUser();
    const users = [...admins, ...agents];
    tbody.innerHTML = users.map(u => {
      const isSelf = me && me.email === u.email;
      const roleSelect =
        '<select data-role-user="' + u.id + '" ' + (isSelf ? "disabled" : "") + ' aria-label="Rôle de ' + escapeHtml(u.fullName) + '">' +
          ["ADMIN", "AGENT_BANCAIRE"].map(r =>
            '<option value="' + r + '"' + (u.role === r ? " selected" : "") + ">" + (r === "ADMIN" ? "Administrateur" : "Agent bancaire") + "</option>"
          ).join("") +
        "</select>";
      const toggle = isSelf
        ? '<span class="muted">votre compte</span>'
        : (u.active
            ? '<button class="btn btn-ghost btn-sm" data-toggle-user="' + u.id + '" data-active="false">Désactiver</button>'
            : '<button class="btn btn-primary btn-sm" data-toggle-user="' + u.id + '" data-active="true">Réactiver</button>');
      return "<tr>" +
        "<td><strong>" + escapeHtml(u.fullName) + "</strong></td>" +
        '<td style="font-family:Consolas,monospace;font-size:11px">' + escapeHtml(u.email) + "</td>" +
        "<td>" + roleSelect + "</td>" +
        "<td>" + (u.active ? badge("Actif", "badge-success") : badge("Inactif", "badge-danger")) + "</td>" +
        "<td>" + toggle + "</td>" +
      "</tr>";
    }).join("") || '<tr><td colspan="5" class="empty">Aucun utilisateur interne.</td></tr>';
  } catch (e) {
    tbody.innerHTML = '<tr><td colspan="5" class="empty">Erreur de chargement.</td></tr>';
    toast(e.message, "error");
  }
}

$("usersTableBody").addEventListener("change", async (event) => {
  const select = event.target.closest("[data-role-user]");
  if (!select) return;
  try {
    await api("/admin/clients/" + select.dataset.roleUser + "/role", {
      method: "PUT",
      body: JSON.stringify({ role: select.value }),
    });
    toast("Rôle mis à jour.", "success");
  } catch (e) { toast(e.message, "error"); loadInternalUsers(); }
});
$("usersTableBody").addEventListener("click", async (event) => {
  const btn = event.target.closest("[data-toggle-user]");
  if (!btn) return;
  const activate = btn.dataset.active === "true";
  const reason = activate ? null : prompt("Motif de la désactivation :", "Fin de mission / départ");
  if (!activate && reason === null) return;
  try {
    await api("/admin/clients/" + btn.dataset.toggleUser + (activate ? "/activate" : "/deactivate"), {
      method: "POST",
      body: JSON.stringify({ reason }),
    });
    toast(activate ? "Utilisateur réactivé." : "Utilisateur désactivé.", "success");
    loadInternalUsers();
  } catch (e) { toast(e.message, "error"); }
});

/* ------------------------------------------------------------------- audit */
const auditState = { page: 0, user: "" };

async function loadAudit() {
  const tbody = $("auditTableBody");
  tbody.innerHTML = '<tr><td colspan="6" class="loading">Chargement…</td></tr>';
  const params = new URLSearchParams({ page: auditState.page, size: 20 });
  if (auditState.user) params.set("user", auditState.user);
  try {
    const data = await api("/admin/audit-logs?" + params.toString());
    const rows = (data.content || []).map(l =>
      "<tr>" +
        "<td>" + escapeHtml(formatDateTime(l.createdAt)) + "</td>" +
        '<td style="font-family:Consolas,monospace;font-size:11px">' + escapeHtml(l.userEmail) + "</td>" +
        "<td>" + badge(l.action, "badge-info") + "</td>" +
        "<td>" + escapeHtml((l.entityType || "") + (l.entityId ? " #" + l.entityId : "")) + "</td>" +
        '<td style="max-width:340px">' + escapeHtml(l.details || "—") + "</td>" +
        "<td>" + escapeHtml(l.ipAddress || "—") + "</td>" +
      "</tr>"
    ).join("");
    tbody.innerHTML = rows || '<tr><td colspan="6" class="empty">Aucune entrée.</td></tr>';
    $("auditPageInfo").textContent = "Page " + (data.number + 1) + " / " + Math.max(data.totalPages, 1);
    $("auditPrev").disabled = data.first;
    $("auditNext").disabled = data.last;
  } catch (e) {
    tbody.innerHTML = '<tr><td colspan="6" class="empty">Erreur de chargement.</td></tr>';
    toast(e.message, "error");
  }
}
$("auditApply").addEventListener("click", () => { auditState.page = 0; auditState.user = $("auditSearch").value.trim(); loadAudit(); });
$("auditSearch").addEventListener("keydown", e => { if (e.key === "Enter") $("auditApply").click(); });
$("auditPrev").addEventListener("click", () => { if (auditState.page > 0) { auditState.page--; loadAudit(); } });
$("auditNext").addEventListener("click", () => { auditState.page++; loadAudit(); });

/* ---------------------------------------------------------------- modales */
document.querySelectorAll("[data-modal]").forEach(btn => {
  btn.addEventListener("click", () => {
    $("modalBackdrop").hidden = false;
    $(btn.dataset.modal).hidden = false;
  });
});
function closeModals() {
  document.querySelectorAll(".modal").forEach(m => m.hidden = true);
  $("modalBackdrop").hidden = true;
}
$("modalBackdrop").addEventListener("click", closeModals);
document.querySelectorAll(".modal-close").forEach(btn => btn.addEventListener("click", closeModals));
document.addEventListener("keydown", e => { if (e.key === "Escape") closeModals(); });

/* ------------------------------------------------------------- démarrage */
if (token() && currentUser()) {
  if (sessionStorage.getItem(TOKEN_KEY) || localStorage.getItem(TOKEN_KEY)) {
    showApp();
    resetInactivity();
    navigate("dashboard");
    tickClock();
    loadUnreadCount();
    notifPoll = setInterval(loadUnreadCount, 30000);
  }
} else {
  showLogin();
}
