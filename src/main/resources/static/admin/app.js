/* =========================================================================
   Banque Démonstration — Administration des virements (SPA légère)
   ========================================================================= */

const API = "/api/v1";
const TOKEN_KEY = "virement_admin_token";
const USER_KEY = "virement_admin_user";

/* ---------- helpers ---------- */
function token() { return localStorage.getItem(TOKEN_KEY); }

function saveSession(auth) {
  localStorage.setItem(TOKEN_KEY, auth.token);
  localStorage.setItem(USER_KEY, JSON.stringify({
    email: auth.email,
    fullName: auth.fullName,
    role: auth.role,
  }));
}

function clearSession() {
  localStorage.removeItem(TOKEN_KEY);
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
    showLogin("Session expirée ou non autorisée.");
    throw new Error("Non autorisé");
  }
  const contentType = response.headers.get("content-type") || "";
  const body = contentType.includes("application/json") ? await response.json() : null;
  if (!response.ok) {
    throw new Error(body && body.message ? body.message : "Erreur serveur (" + response.status + ")");
  }
  return body;
}

function formatAmount(value, currency) {
  const n = Number(value || 0);
  return new Intl.NumberFormat("fr-FR", { maximumFractionDigits: 0 }).format(n) + " " + (currency || "XAF");
}

function formatDate(iso) {
  if (!iso) return "—";
  return new Date(iso).toLocaleString("fr-FR", { day: "2-digit", month: "2-digit", year: "numeric", hour: "2-digit", minute: "2-digit" });
}

function escapeHtml(value) {
  return String(value == null ? "" : value)
    .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;").replace(/'/g, "&#39;");
}

function toast(message, type) {
  const el = document.getElementById("toast");
  el.textContent = message;
  el.className = "toast" + (type ? " " + type : "");
  el.style.display = "block";
  clearTimeout(el._timer);
  el._timer = setTimeout(() => { el.style.display = "none"; }, 4200);
}

/* ---------- statuts ---------- */
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

const SEVERITY_BADGE = {
  CRITIQUE: "badge-danger",
  HAUTE: "badge-danger",
  MOYENNE: "badge-warning",
  BASSE: "badge-info",
};

function badge(text, cls) {
  return '<span class="badge ' + (cls || "badge-neutral") + '">' + escapeHtml(text) + "</span>";
}

function statusBadge(status) {
  return badge(STATUS_LABELS[status] || status, STATUS_BADGE[status] || "badge-neutral");
}

/* ---------- navigation ---------- */
function showLogin(errorMessage) {
  document.getElementById("app").classList.remove("visible");
  document.getElementById("loginScreen").style.display = "flex";
  const err = document.getElementById("loginError");
  if (errorMessage) {
    err.textContent = errorMessage;
    err.style.display = "block";
  } else {
    err.style.display = "none";
  }
}

function showApp() {
  const user = currentUser();
  document.getElementById("loginScreen").style.display = "none";
  document.getElementById("app").classList.add("visible");
  if (user) {
    document.getElementById("userName").textContent = user.fullName || user.email;
    document.getElementById("userRole").textContent = user.role || "";
    document.getElementById("avatar").textContent = (user.fullName || user.email || "?").trim().charAt(0).toUpperCase();
  }
}

function navigate(page) {
  document.querySelectorAll(".page").forEach(p => p.classList.toggle("active", p.id === "page-" + page));
  document.querySelectorAll(".nav-item").forEach(n => n.classList.toggle("active", n.dataset.page === page));
  if (page === "dashboard") loadDashboard();
  else if (page === "transactions") loadTransactions();
  else if (page === "fraud") loadFraudAlerts();
  else if (page === "clients") loadClients();
  else if (page === "accounts") loadAccounts();
  else if (page === "audit") loadAudit();
}

document.querySelectorAll(".nav-item").forEach(item => {
  item.addEventListener("click", () => navigate(item.dataset.page));
});

/* ---------- connexion ---------- */
document.getElementById("fillDemo").addEventListener("click", () => {
  document.getElementById("email").value = "admin@banque.cm";
  document.getElementById("password").value = "Admin@123";
});

document.getElementById("loginForm").addEventListener("submit", async (event) => {
  event.preventDefault();
  const btn = document.getElementById("loginBtn");
  const err = document.getElementById("loginError");
  err.style.display = "none";
  btn.disabled = true;
  btn.textContent = "Connexion…";
  try {
    const auth = await api("/auth/login", {
      method: "POST",
      body: JSON.stringify({
        email: document.getElementById("email").value.trim(),
        password: document.getElementById("password").value,
      }),
    });
    const role = (auth.role || "").toUpperCase();
    if (role !== "ADMIN" && role !== "AGENT_BANCAIRE") {
      throw new Error("Accès réservé aux équipes internes (ADMIN / AGENT_BANCAIRE).");
    }
    saveSession(auth);
    showApp();
    navigate("dashboard");
    toast("Bienvenue, " + (auth.fullName || auth.email), "success");
  } catch (e) {
    err.textContent = e.message;
    err.style.display = "block";
  } finally {
    btn.disabled = false;
    btn.textContent = "Se connecter";
  }
});

document.getElementById("logoutBtn").addEventListener("click", () => {
  clearSession();
  showLogin();
});

/* ---------- tableau de bord ---------- */
async function loadDashboard() {
  try {
    const stats = await api("/admin/stats");
    document.getElementById("kpiClients").textContent = stats.totalClients ?? "—";
    document.getElementById("kpiAccounts").textContent = stats.totalComptes ?? "—";
    document.getElementById("kpiSuspended").textContent = (stats.comptesSuspendus ?? 0) + " suspendu(s)/bloqué(s)";
    document.getElementById("kpiExecuted").textContent = stats.virementsExecutes ?? "—";
    document.getElementById("kpiPending").textContent = (stats.virementsEnAttente ?? 0) + " en attente";
    document.getElementById("kpiVolume").textContent = formatAmount(stats.montantTotalExecute, stats.devise);
    const fraud = stats.alertesFraudeOuvertes ?? 0;
    document.getElementById("kpiFraud").textContent = fraud;
    const badgeEl = document.getElementById("fraudBadge");
    badgeEl.style.display = fraud > 0 ? "inline-block" : "none";
    badgeEl.textContent = fraud;
    renderStatusChart(stats.repartitionParStatut || {});

    const series = await api("/admin/stats/daily?days=30");
    renderDailyChart(series || []);
  } catch (e) {
    toast(e.message, "error");
  }
}

function renderStatusChart(byStatus) {
  const container = document.getElementById("statusChart");
  const entries = Object.entries(byStatus);
  if (entries.length === 0) {
    container.innerHTML = '<div class="empty">Aucune donnée pour le moment.</div>';
    document.getElementById("statusTotal").textContent = "";
    return;
  }
  const total = entries.reduce((sum, [, v]) => sum + Number(v), 0);
  document.getElementById("statusTotal").textContent = total + " virement(s)";
  const colors = {
    EXECUTE: "#1e9e6a",
    EN_ATTENTE_CONFIRMATION: "#d97706",
    EN_ATTENTE_APPROBATION: "#eab308",
    PROGRAMME: "#2563eb",
    BLOQUE_FRAUDE: "#d64545",
    REJETE: "#94a3b8",
    ECHOUE: "#64748b",
    ANNULE: "#cbd5e1",
  };
  const max = Math.max(...entries.map(([, v]) => Number(v)), 1);
  container.innerHTML = entries
    .sort((a, b) => Number(b[1]) - Number(a[1]))
    .map(([status, count]) => {
      const pct = Math.round((Number(count) / max) * 100);
      return (
        '<div class="status-row">' +
          '<div class="lbl" title="' + escapeHtml(status) + '">' + escapeHtml(STATUS_LABELS[status] || status) + "</div>" +
          '<div class="status-bar"><div class="status-fill" style="width:' + pct + '%;background:' + (colors[status] || "#0f4c81") + '"></div></div>' +
          '<div class="status-count">' + count + "</div>" +
        "</div>"
      );
    }).join("");
}

function renderDailyChart(series) {
  const chart = document.getElementById("dailyChart");
  if (!series.length) {
    chart.innerHTML = '<div class="chart-empty">Aucun virement exécuté sur la période.</div>';
    return;
  }
  const max = Math.max(...series.map(p => Number(p.volume || 0)), 1);
  chart.innerHTML = series.map(point => {
    const volume = Number(point.volume || 0);
    const height = Math.max(Math.round((volume / max) * 150), 4);
    const date = String(point.date || "").slice(5, 10);
    const label = volume >= 1000000 ? (volume / 1000000).toFixed(1) + " M" : formatAmount(volume, "").trim();
    return (
      '<div class="chart-col" title="' + escapeHtml(point.date) + ' : ' + formatAmount(volume, "XAF") + " (" + point.count + " virement(s))" + '">' +
        '<div class="chart-bar" style="height:' + height + 'px"></div>' +
        '<div class="chart-lbl">' + escapeHtml(date) + "</div>" +
      "</div>"
    );
  }).join("");
}

/* ---------- transactions ---------- */
const txState = { page: 0, size: 15, status: "", q: "" };

async function loadTransactions() {
  const tbody = document.getElementById("txTableBody");
  tbody.innerHTML = '<tr><td colspan="6" class="loading">Chargement…</td></tr>';
  const params = new URLSearchParams({ page: txState.page, size: txState.size });
  if (txState.status) params.set("status", txState.status);
  if (txState.q) params.set("q", txState.q);
  try {
    const data = await api("/admin/transactions?" + params.toString());
    const rows = (data.content || []).map(t =>
      "<tr>" +
        '<td class="mono">' + escapeHtml(t.reference) + "</td>" +
        "<td>" + escapeHtml(formatDate(t.createdAt)) + "</td>" +
        "<td>" + escapeHtml(t.beneficiaryName || (t.targetIban ? "Compte propre " + t.targetIban : "—")) + "</td>" +
        '<td class="mono">' + escapeHtml(t.sourceIban) + "</td>" +
        "<td><strong>" + escapeHtml(formatAmount(t.amount, t.currency)) + "</strong></td>" +
        "<td>" + statusBadge(t.status) + "</td>" +
      "</tr>"
    ).join("");
    tbody.innerHTML = rows || '<tr><td colspan="6" class="empty">Aucune transaction ne correspond aux filtres.</td></tr>';
    document.getElementById("txPageInfo").textContent = "Page " + (data.number + 1) + " / " + Math.max(data.totalPages, 1);
    document.getElementById("txTotal").textContent = data.totalElements + " résultat(s)";
    document.getElementById("txPrev").disabled = data.first;
    document.getElementById("txNext").disabled = data.last;
  } catch (e) {
    tbody.innerHTML = '<tr><td colspan="6" class="empty">Erreur de chargement.</td></tr>';
    toast(e.message, "error");
  }
}

document.getElementById("txApply").addEventListener("click", () => {
  txState.page = 0;
  txState.status = document.getElementById("txStatus").value;
  txState.q = document.getElementById("txSearch").value.trim();
  loadTransactions();
});
document.getElementById("txSearch").addEventListener("keydown", e => { if (e.key === "Enter") document.getElementById("txApply").click(); });
document.getElementById("txPrev").addEventListener("click", () => { if (txState.page > 0) { txState.page--; loadTransactions(); } });
document.getElementById("txNext").addEventListener("click", () => { txState.page++; loadTransactions(); });

/* ---------- export PDF / Excel (authentifié) ---------- */
async function downloadExport(format) {
  const params = new URLSearchParams({ format });
  if (txState.status) params.set("status", txState.status);
  if (txState.q) params.set("q", txState.q);
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
  } catch (e) {
    toast(e.message, "error");
  }
}
document.getElementById("exportPdfBtn").addEventListener("click", () => downloadExport("pdf"));
document.getElementById("exportXlsxBtn").addEventListener("click", () => downloadExport("xlsx"));

/* ---------- anti-fraude ---------- */
async function loadFraudAlerts() {
  const tbody = document.getElementById("fraudTableBody");
  tbody.innerHTML = '<tr><td colspan="7" class="loading">Chargement…</td></tr>';
  const status = document.getElementById("fraudStatus").value;
  try {
    const params = new URLSearchParams({ page: 0, size: 30 });
    if (status) params.set("status", status);
    const data = await api("/admin/fraud-alerts?" + params.toString());
    const rows = (data.content || []).map(a => {
      const actions = (a.status === "OUVERTE" || a.status === "EN_REVUE")
        ? '<button class="btn btn-success btn-sm" data-action="validate" data-id="' + a.id + '">Valider</button> ' +
          '<button class="btn btn-danger btn-sm" data-action="reject" data-id="' + a.id + '">Rejeter</button>'
        : '<span class="badge badge-neutral">' + escapeHtml(a.status) + "</span>";
      return (
        "<tr>" +
          '<td class="mono">' + escapeHtml(a.rule) + "</td>" +
          "<td>" + badge(a.severity, SEVERITY_BADGE[a.severity] || "badge-neutral") + "</td>" +
          '<td class="mono">' + escapeHtml(a.reference) + "</td>" +
          "<td>" + escapeHtml(formatAmount(a.montant, a.devise)) + "</td>" +
          "<td>" + escapeHtml(a.initiator) + "</td>" +
          '<td style="max-width:320px">' + escapeHtml(a.details) + "</td>" +
          "<td>" + actions + "</td>" +
        "</tr>"
      );
    }).join("");
    tbody.innerHTML = rows || '<tr><td colspan="7" class="empty">Aucune alerte. Le système est calme ✅</td></tr>';
  } catch (e) {
    tbody.innerHTML = '<tr><td colspan="7" class="empty">Erreur de chargement.</td></tr>';
    toast(e.message, "error");
  }
}

document.getElementById("fraudApply").addEventListener("click", loadFraudAlerts);
document.getElementById("fraudTableBody").addEventListener("click", async (event) => {
  const btn = event.target.closest("button[data-action]");
  if (!btn) return;
  const id = btn.dataset.id;
  const action = btn.dataset.action;
  if (action === "validate") {
    if (!confirm("Valider manuellement ce virement ? Les fonds seront débités immédiatement.")) return;
    try {
      await api("/admin/fraud-alerts/" + id + "/validate", { method: "POST" });
      toast("Virement validé et exécuté.", "success");
      loadFraudAlerts();
      loadDashboard();
    } catch (e) { toast(e.message, "error"); }
  } else if (action === "reject") {
    const comment = prompt("Motif du rejet (optionnel) :", "");
    if (comment === null) return;
    try {
      await api("/admin/fraud-alerts/" + id + "/reject", {
        method: "POST",
        body: JSON.stringify({ comment }),
      });
      toast("Virement rejeté. Les fonds n'ont pas été débités.", "success");
      loadFraudAlerts();
      loadDashboard();
    } catch (e) { toast(e.message, "error"); }
  }
});

/* ---------- clients ---------- */
async function loadClients() {
  const tbody = document.getElementById("clientTableBody");
  tbody.innerHTML = '<tr><td colspan="6" class="loading">Chargement…</td></tr>';
  const q = document.getElementById("clientSearch").value.trim();
  const role = document.getElementById("clientRole").value;
  const params = new URLSearchParams();
  if (q) params.set("q", q);
  if (role) params.set("role", role);
  try {
    const users = await api("/admin/clients?" + params.toString());
    const rows = users.map(u =>
      "<tr>" +
        "<td><strong>" + escapeHtml(u.fullName) + "</strong></td>" +
        '<td class="mono">' + escapeHtml(u.email) + "</td>" +
        "<td>" + escapeHtml(u.phone || "—") + "</td>" +
        "<td>" + badge(u.role, u.role === "ADMIN" ? "badge-gold" : "badge-info") + "</td>" +
        "<td>" + (u.active ? badge("Actif", "badge-success") : badge("Inactif", "badge-danger")) + "</td>" +
        '<td><span style="color:var(--muted);font-size:12px">' + escapeHtml(u.company || "") + "</span></td>" +
      "</tr>"
    ).join("");
    tbody.innerHTML = rows || '<tr><td colspan="6" class="empty">Aucun client trouvé.</td></tr>';
  } catch (e) {
    tbody.innerHTML = '<tr><td colspan="6" class="empty">Erreur de chargement.</td></tr>';
    toast(e.message, "error");
  }
}

document.getElementById("clientApply").addEventListener("click", loadClients);
document.getElementById("clientSearch").addEventListener("keydown", e => { if (e.key === "Enter") loadClients(); });

/* ---------- comptes ---------- */
async function loadAccounts() {
  const tbody = document.getElementById("accountTableBody");
  tbody.innerHTML = '<tr><td colspan="7" class="loading">Chargement…</td></tr>';
  const q = document.getElementById("accountSearch").value.trim();
  const params = new URLSearchParams();
  if (q) params.set("q", q);
  try {
    const accounts = await api("/admin/accounts?" + params.toString());
    const rows = accounts.map(a => {
      const active = a.status === "ACTIVE";
      const action = active
        ? '<button class="btn btn-danger btn-sm" data-action="suspend" data-id="' + a.id + '">Suspendre</button>'
        : '<button class="btn btn-success btn-sm" data-action="activate" data-id="' + a.id + '">Réactiver</button>';
      const statusCls = active ? "badge-success" : (a.status === "SUSPENDED" ? "badge-warning" : "badge-danger");
      return (
        "<tr>" +
          '<td class="mono">' + escapeHtml(a.iban) + "</td>" +
          "<td>" + escapeHtml(a.type) + "</td>" +
          "<td>" + escapeHtml(a.titulaire) + "</td>" +
          "<td>" + escapeHtml(formatAmount(a.balance, a.currency)) + "</td>" +
          "<td>" + escapeHtml(formatAmount(a.dailyLimit, a.currency)) + "</td>" +
          "<td>" + badge(a.status, statusCls) + "</td>" +
          "<td>" + action + "</td>" +
        "</tr>"
      );
    }).join("");
    tbody.innerHTML = rows || '<tr><td colspan="7" class="empty">Aucun compte trouvé.</td></tr>';
  } catch (e) {
    tbody.innerHTML = '<tr><td colspan="7" class="empty">Erreur de chargement.</td></tr>';
    toast(e.message, "error");
  }
}

document.getElementById("accountApply").addEventListener("click", loadAccounts);
document.getElementById("accountSearch").addEventListener("keydown", e => { if (e.key === "Enter") loadAccounts(); });
document.getElementById("accountTableBody").addEventListener("click", async (event) => {
  const btn = event.target.closest("button[data-action]");
  if (!btn) return;
  const id = btn.dataset.id;
  if (btn.dataset.action === "suspend") {
    const reason = prompt("Motif de la suspension :", "Activité inhabituelle détectée");
    if (reason === null) return;
    try {
      await api("/admin/accounts/" + id + "/suspend", { method: "POST", body: JSON.stringify({ reason }) });
      toast("Compte suspendu.", "success");
      loadAccounts();
    } catch (e) { toast(e.message, "error"); }
  } else if (btn.dataset.action === "activate") {
    try {
      await api("/admin/accounts/" + id + "/activate", { method: "POST" });
      toast("Compte réactivé.", "success");
      loadAccounts();
    } catch (e) { toast(e.message, "error"); }
  }
});

/* ---------- audit ---------- */
const auditState = { page: 0, user: "" };

async function loadAudit() {
  const tbody = document.getElementById("auditTableBody");
  tbody.innerHTML = '<tr><td colspan="6" class="loading">Chargement…</td></tr>';
  const params = new URLSearchParams({ page: auditState.page, size: 20 });
  if (auditState.user) params.set("user", auditState.user);
  try {
    const data = await api("/admin/audit-logs?" + params.toString());
    const rows = (data.content || []).map(l =>
      "<tr>" +
        "<td>" + escapeHtml(formatDate(l.createdAt)) + "</td>" +
        '<td class="mono">' + escapeHtml(l.userEmail) + "</td>" +
        "<td>" + badge(l.action, "badge-info") + "</td>" +
        "<td>" + escapeHtml((l.entityType || "") + (l.entityId ? " #" + l.entityId : "")) + "</td>" +
        '<td style="max-width:360px">' + escapeHtml(l.details || "—") + "</td>" +
        "<td>" + escapeHtml(l.ipAddress || "—") + "</td>" +
      "</tr>"
    ).join("");
    tbody.innerHTML = rows || '<tr><td colspan="6" class="empty">Aucune entrée.</td></tr>';
    document.getElementById("auditPageInfo").textContent = "Page " + (data.number + 1) + " / " + Math.max(data.totalPages, 1);
    document.getElementById("auditPrev").disabled = data.first;
    document.getElementById("auditNext").disabled = data.last;
  } catch (e) {
    tbody.innerHTML = '<tr><td colspan="6" class="empty">Erreur de chargement.</td></tr>';
    toast(e.message, "error");
  }
}

document.getElementById("auditApply").addEventListener("click", () => {
  auditState.page = 0;
  auditState.user = document.getElementById("auditSearch").value.trim();
  loadAudit();
});
document.getElementById("auditSearch").addEventListener("keydown", e => { if (e.key === "Enter") document.getElementById("auditApply").click(); });
document.getElementById("auditPrev").addEventListener("click", () => { if (auditState.page > 0) { auditState.page--; loadAudit(); } });
document.getElementById("auditNext").addEventListener("click", () => { auditState.page++; loadAudit(); });

/* ---------- démarrage ---------- */
if (token() && currentUser()) {
  showApp();
  navigate("dashboard");
} else {
  showLogin();
}
