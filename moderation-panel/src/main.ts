import {
  createClient,
  type RealtimeChannel,
  type Session,
} from "@supabase/supabase-js";
import {
  decisionLabel,
  formatSubmittedAt,
  type ReviewDecision,
  type ReviewItem,
} from "./review";
import "./style.css";
import "./console.css";
import "./trust-desk.css";
import "./workflow.css";

const supabaseUrl = import.meta.env.VITE_SUPABASE_URL as string | undefined;
const anonKey = import.meta.env.VITE_SUPABASE_ANON_KEY as string | undefined;
const configured = Boolean(supabaseUrl && anonKey);
const supabase = configured ? createClient(supabaseUrl!, anonKey!) : null;
const endpoint = `${supabaseUrl ?? ""}/functions/v1/moderation-profile-photos`;
const UUID = /^[0-9a-f-]{36}$/;

type View = "overview" | "photos" | "reports" | "appeals" | "metrics" | "users" | "history" | "team";
type Appeal = { appeal_id:string; user_id:string; sanction_id:string; statement:string; state:string; created_at:string; original_reviewer:string|null };
type Metrics = { window_days:number; open_cases:number; overdue_cases:number; resolved:number; median_resolution_hours:number|null; appeals_pending:number; appeals_reversed:number };
type Workspace = { operation:{priority:string;due_at:string;assigned_to:string|null;second_review_required:boolean;response_template_key:string|null}; notes:Array<{id:number;author_name:string|null;note:string;created_at:string}>; templates:Array<{key:string;title:string;body:string}> };
type Overview = {
  role: "reviewer" | "admin";
  photo_reviews: number;
  open_reports: number;
  reported_albums: number;
  suspended_accounts: number;
  active_staff: number;
  oldest_report_at: string | null;
  decisions_24h: number;
  total_active_accounts: number;
  online_now: number;
  active_24h: number;
  new_accounts_7d: number;
  messages_24h: number;
  conversations_24h: number;
  reports_24h: number;
  blocks_24h: number;
  avg_resolution_hours_30d: number | null;
};
type Case = {
  case_id: string;
  reported_user_id: string;
  display_name: string | null;
  account_status: string;
  reason: string;
  details: string;
  case_state: string;
  created_at: string;
  message_body: string | null;
  has_album_evidence: boolean;
  repeat_report_count: number;
  priority: "critical" | "high" | "normal";
  priority_score: number;
};
type UserRow = {
  user_id: string;
  display_name: string | null;
  account_status: string;
  verified: boolean;
  report_count: number;
  active_sanction: string | null;
  created_at: string;
};
type UserDetail = {
  user_id: string;
  account_status: string;
  created_at: string;
  adult_verified_at: string | null;
  profile: {
    display_name: string | null;
    age: number | null;
    bio: string | null;
    intent: string | null;
    region_code: string | null;
    verified: boolean;
    discovery_visible: boolean;
    has_photo: boolean;
    last_active_at: string | null;
    interests: string[];
  };
  identity: {
    gender_ids: string[];
    self_description: string | null;
    looking_for: string[];
  };
  risk: {
    reports_received: number;
    open_cases: number;
    blocks_involving_account: number;
  };
  access: {
    active_devices: number;
    last_device_seen_at: string | null;
    active_sessions: number;
    last_session_at: string | null;
  };
  devices: Array<{
    platform: string;
    active: boolean;
    created_at: string;
    last_seen_at: string;
  }>;
  sanctions: Array<{
    kind: string;
    reason: string;
    active: boolean;
    expires_at: string | null;
    created_at: string;
    ended_at: string | null;
  }>;
  reports: Array<{
    case_id: string;
    reason: string;
    state: string;
    has_message: boolean;
    created_at: string;
    resolved_at: string | null;
  }>;
};
type AuditRow = {
  event_id: number;
  event_type: string;
  actor_id: string | null;
  subject_user_id: string | null;
  moderation_case_id: string | null;
  decision: string | null;
  created_at: string;
};
type StaffRow = {
  user_id: string;
  display_name: string | null;
  staff_role: string;
  active: boolean;
  granted_at: string;
};
type Evidence = {
  album_id: string;
  album_item_id: string | null;
  hold_until: string;
  preview_url: string;
  preview_expires_in: number;
};
type PendingAction = {
  action: string;
  title: string;
  detail: string;
  payload: Record<string, unknown>;
};

const state = {
  session: null as Session | null,
  authEmail: "",
  otpSent: false,
  view: "overview" as View,
  overview: null as Overview | null,
  photos: [] as ReviewItem[],
  cases: [] as Case[],
  users: [] as UserRow[],
  audit: [] as AuditRow[],
  staff: [] as StaffRow[],
  appeals: [] as Appeal[],
  metrics: null as Metrics | null,
  workspace: null as Workspace | null,
  evidence: [] as Evidence[],
  selectedCase: null as Case | null,
  loading: false,
  message: "",
  pending: null as PendingAction | null,
  search: "",
  selectedUser: null as UserDetail | null,
  realtimeStatus: "connecting" as "connecting" | "live" | "offline",
  syncing: false,
  lastSyncedAt: null as Date | null,
  reportFilters: {
    reason: "all",
    evidence: "all",
    priority: "all",
    sort: "priority",
  },
};
const root = document.querySelector<HTMLDivElement>("#app")!;
let idleTimer = 0;
let autoRefreshTimer = 0;
let realtimeRefreshTimer = 0;
let realtimeChannel: RealtimeChannel | null = null;

function esc(value: unknown): string {
  return String(value ?? "").replace(
    /[&<>'"]/g,
    (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;" })[
        c
      ]!,
  );
}
function date(value: string): string {
  return new Intl.DateTimeFormat("pt-BR", {
    dateStyle: "short",
    timeStyle: "short",
  }).format(new Date(value));
}
function label(value: string): string {
  return (
    (
      {
        spam: "Spam",
    harassment: "Assédio",
    fake_profile: "Perfil falso",
    inappropriate_photo: "Foto inadequada",
        other: "Outro",
        suspension: "Suspensão",
        ban: "Banimento",
        warning: "Advertência",
      } as Record<string, string>
    )[value] ?? value.replaceAll("_", " ")
  );
}
function isAdmin(): boolean {
  return state.overview?.role === "admin";
}

function setSyncingIndicator(active: boolean): void {
  root.querySelector(".console-main")?.classList.toggle("syncing", active);
}

function shell(content: string): string {
  const nav: Array<[View, string, string]> = [
    ["overview", "Visão geral", "01"],
    ["photos", "Fotos", "02"],
    ["reports", "Denúncias", "03"],
    ["appeals", "Recursos", "04"],
    ["metrics", "Métricas", "05"],
    ["users", "Contas", "06"],
    ["history", "Histórico", "07"],
    ["team", "Equipe", "08"],
  ];
  const queue =
    (state.overview?.photo_reviews ?? 0) + (state.overview?.open_reports ?? 0);
  const liveLabel = state.syncing
    ? "Sincronizando"
    : state.realtimeStatus === "live"
      ? "Ao vivo"
      : state.realtimeStatus === "connecting"
        ? "Conectando"
        : "Reconectando";
  return `<div class="console"><aside class="console-nav"><div class="brand"><span aria-hidden="true"><b>V</b></span><div><strong>VIBEALI</strong><small>TRUST DESK</small></div></div><nav aria-label="Seções da central">${nav.map(([id, text, icon]) => `<button data-view="${id}" class="${state.view === id ? "active" : ""}" ${id === "team" && !isAdmin() ? "disabled" : ""}><i>${icon}</i><span>${text}</span></button>`).join("")}</nav><div class="pulse-card"><div class="pulse-orbit" aria-hidden="true"><i></i></div><div><strong>Operação ativa</strong><small>${queue ? `${queue} item(ns) na fila` : "Filas sob controle"}</small></div></div><div class="role-card"><span>${isAdmin() ? "Administrador" : "Revisor"}</span><small>${isAdmin() ? "Controle completo" : "Conteúdo e casos"}</small></div><button id="logout" class="quiet">Encerrar sessão</button></aside><section class="console-main ${state.syncing ? "syncing" : ""}"><header><div><p class="eyebrow">Central de confiança / ${isAdmin() ? "admin" : "revisão"}</p><h1>${viewTitle()}</h1></div><div class="header-tools"><span class="live ${state.realtimeStatus}"><i></i> ${liveLabel}</span><button id="refresh" class="quiet">Atualizar agora</button></div></header>${state.loading ? `<div class="center"><div class="loader"></div><p>Preparando a central…</p></div>` : content}</section>${state.pending ? confirmMarkup() : ""}<p class="toast" role="status">${esc(state.message)}</p></div>`;
}
function viewTitle(): string {
  return {
    overview: "Visão geral",
    photos: "Fotos públicas",
    reports: "Denúncias",
    appeals: "Recursos e segunda revisão",
    metrics: "Métricas operacionais",
    users: "Gestão de contas",
    history: "Histórico",
    team: "Equipe de moderação",
  }[state.view];
}

function render(preserveViewport = false): void {
  const scrollX = window.scrollX;
  const scrollY = window.scrollY;
  if (!configured) {
    root.innerHTML = `<main class="center"><section class="notice"><h1>Configure o Supabase</h1><p>Use as variáveis públicas descritas em <code>.env.example</code>.</p></section></main>`;
    return;
  }
  if (!state.session) {
    renderLogin();
    return;
  }
  const content =
    state.view === "overview"
      ? overviewMarkup()
      : state.view === "photos"
        ? photosMarkup()
        : state.view === "reports"
          ? reportsMarkup()
          : state.view === "appeals"
            ? appealsMarkup()
            : state.view === "metrics"
              ? metricsMarkup()
          : state.view === "users"
            ? usersMarkup()
            : state.view === "history"
              ? historyMarkup()
              : teamMarkup();
  root.innerHTML = shell(content);
  bindConsole();
  if (preserveViewport) {
    window.requestAnimationFrame(() => window.scrollTo(scrollX, scrollY));
  }
}

function renderLogin(): void {
  root.innerHTML = `<main class="login-shell"><section class="login-intro"><div class="login-mark"><span aria-hidden="true"><b>V</b></span><div><strong>VIBEALI</strong><small>TRUST DESK</small></div></div><div class="login-statement"><p class="eyebrow">Segurança da comunidade</p><h1>Decisões humanas.<br><em>Ambiente protegido.</em></h1><p>Uma central reservada para quem cuida das pessoas, do conteúdo e da confiança na VibeAli.</p></div><div class="trust-strip"><span><i></i> Ações auditadas</span><span>Sessão protegida</span><span>Dados mínimos</span></div></section><section class="login-card"><div class="access-code">ACESSO / 01</div><p class="eyebrow">Área restrita</p><h2>${state.otpSent ? "Digite o código." : "Identifique-se."}</h2><p class="muted">${state.otpSent ? `Enviamos seis dígitos para <strong>${esc(state.authEmail)}</strong>.` : "Use o e-mail autorizado da equipe de confiança."}</p><form id="email-form" class="${state.otpSent ? "hidden" : ""}"><label for="email">E-mail corporativo</label><input id="email" type="email" autocomplete="email" placeholder="voce@vibeali.shop" required value="${esc(state.authEmail)}"><button>Receber código de acesso</button></form><form id="otp-form" class="${state.otpSent ? "" : "hidden"}"><label for="otp">Código de 6 dígitos</label><input id="otp" class="otp-input" inputmode="numeric" autocomplete="one-time-code" pattern="[0-9]{6}" maxlength="6" placeholder="000000" required><button>Entrar na central</button><button id="change-email" type="button" class="quiet">Trocar e-mail</button></form><p class="form-message" role="status">${esc(state.message)}</p><small class="session-note">A sessão encerra após 15 minutos sem atividade.</small></section></main>`;
  bindLogin();
}

function overviewMarkup(): string {
  const o = state.overview;
  if (!o)
    return empty("Dados indisponíveis", "Atualize para carregar a operação.");
  const cards = [
    [o.photo_reviews, "Fotos aguardando", "photos"],
    [o.open_reports, "Denúncias abertas", "reports"],
    [o.reported_albums, "Álbuns denunciados", "reports"],
    [o.suspended_accounts, "Contas suspensas", "users"],
    [o.active_staff, "Equipe ativa", "team"],
    [o.decisions_24h, "Decisões em 24 h", "history"],
  ];
  const signals = [
    [o.online_now, "Online agora"],
    [o.active_24h, "Ativos em 24 h"],
    [o.new_accounts_7d, "Cadastros em 7 dias"],
    [o.messages_24h, "Mensagens em 24 h"],
    [o.reports_24h, "Denúncias em 24 h"],
    [o.avg_resolution_hours_30d ?? "—", "Horas para decidir"],
  ];
  return `<section class="command-brief"><div><p class="eyebrow">Prioridade da operação</p><h2>${o.open_reports ? `${o.open_reports} casos aguardam uma decisão humana` : "Nenhum caso crítico em espera"}</h2><p>${o.oldest_report_at ? `O relato mais antigo chegou em ${date(o.oldest_report_at)}.` : "As filas estão sob controle neste momento."}</p></div><button data-view="reports">Assumir próxima denúncia <span>→</span></button></section><section class="signal-board"><header><div><p class="eyebrow">Pulso da plataforma</p><strong>${o.total_active_accounts} contas ativas</strong></div><small>JANELAS MÓVEIS / TEMPO REAL</small></header><div>${signals.map(([value, title]) => `<article><b>${value}</b><span>${title}</span></article>`).join("")}</div></section><div class="overview-grid">${cards.map(([n, t, v], index) => `<button data-view="${v}" ${v === "team" && !isAdmin() ? "disabled" : ""}><small>0${index + 1}</small><b>${n}</b><span>${t}</span><i>Ver fila →</i></button>`).join("")}</div><section class="security-note"><span class="shield-mark">V</span><div><strong>Proteções da central ativas</strong><p>Prévias privadas expiram em 60 segundos. Toda decisão administrativa gera um registro de auditoria.</p></div><small>POLÍTICA / ONLINE</small></section>`;
}

function photosMarkup(): string {
  const item = state.photos[0];
  if (!item)
    return empty(
      "Nenhuma foto aguardando revisão",
      "A automação encaminhará para cá somente os casos que exigem decisão humana.",
    );
  return `<div class="embedded-review"><section class="photo-stage"><div class="stage-label"><span>FOTO EM REVISÃO</span><span>${esc(formatSubmittedAt(item.submitted_at))}</span></div><img src="${esc(item.preview_url)}" alt="Foto enviada por ${esc(item.display_name)}"></section><section class="decision-rail"><p class="eyebrow">${state.photos.length} na fila</p><h2>${esc(item.display_name)}</h2><p class="muted">${item.has_approved_photo ? "A foto anterior continuará visível se esta for bloqueada." : "Sem foto anterior aprovada."}</p><div class="actions"><button data-photo="approved" class="approve">Aprovar foto</button><button data-photo="blocked_adult" class="block">Conteúdo adulto</button><button data-photo="blocked_abusive" class="danger">Conteúdo abusivo</button></div></section></div>`;
}

function reportsMarkup(): string {
  if (state.selectedCase) return caseDetailMarkup(state.selectedCase);
  const f = state.reportFilters;
  const filters =
    `<form id="report-filters" class="filter-bar"><label>Prioridade<select id="priority-filter"><option value="all">Todas</option><option value="critical">Crítica</option><option value="high">Alta</option><option value="normal">Normal</option></select></label><label>Motivo<select id="reason-filter"><option value="all">Todos</option><option value="harassment">Assédio</option><option value="fake_profile">Perfil falso</option><option value="spam">Spam</option><option value="other">Outro</option></select></label><label>Evidência<select id="evidence-filter"><option value="all">Todas</option><option value="album">Álbum</option><option value="message">Mensagem</option><option value="any">Com evidência</option><option value="none">Sem evidência</option></select></label><label>Ordenar<select id="sort-filter"><option value="priority">Risco</option><option value="oldest">Mais antigas</option><option value="newest">Mais recentes</option></select></label><button>Aplicar filtros</button></form>`
      .replace(`value="${f.priority}"`, `value="${f.priority}" selected`)
      .replace(`value="${f.reason}"`, `value="${f.reason}" selected`)
      .replace(`value="${f.evidence}"`, `value="${f.evidence}" selected`)
      .replace(`value="${f.sort}"`, `value="${f.sort}" selected`);
  if (!state.cases.length)
    return (
      filters +
      empty(
        "Nenhuma denúncia neste recorte",
        "Altere os filtros ou aguarde novos relatos.",
      )
    );
  return `${filters}<div class="queue-list">${state.cases.map((c) => `<button data-case="${c.case_id}"><div><span class="risk-dot ${c.priority}"></span><strong>${esc(c.display_name ?? "Perfil sem nome")}</strong><small>${label(c.reason)} · ${c.repeat_report_count} relato(s) · risco ${c.priority_score}</small></div><time>${date(c.created_at)}</time><span class="priority ${c.priority}">${c.priority === "critical" ? "Crítica" : c.priority === "high" ? "Alta" : "Normal"}</span></button>`).join("")}</div>`;
}

function appealsMarkup(): string {
  if (!state.appeals.length) return empty("Nenhum recurso pendente", "Novos recursos aparecem aqui para uma segunda pessoa revisar.");
  return `<div class="appeals-list">${state.appeals.map(a => `<article><div><strong>Recurso ${a.appeal_id.slice(0, 8)}</strong><small>${date(a.created_at)} · segunda revisão obrigatória</small><p>${esc(a.statement)}</p></div><div><button data-appeal="accepted" data-id="${a.appeal_id}" class="approve">Aceitar recurso</button><button data-appeal="rejected" data-id="${a.appeal_id}" class="danger">Manter medida</button></div></article>`).join("")}</div>`;
}

function metricsMarkup(): string {
  const m = state.metrics;
  if (!m) return empty("Métricas indisponíveis", "Atualize a operação.");
  const values = [[m.open_cases,"Casos abertos"],[m.overdue_cases,"Fora do prazo"],[m.resolved,"Resolvidos"],[m.median_resolution_hours ?? "—","Mediana em horas"],[m.appeals_pending,"Recursos pendentes"],[m.appeals_reversed,"Decisões revertidas"]];
  return `<section class="signal-board"><header><div><p class="eyebrow">Janela de ${m.window_days} dias</p><strong>Qualidade da moderação</strong></div>${isAdmin() ? '<button id="export-audit">Exportar auditoria</button>' : ''}</header><div>${values.map(([v,t])=>`<article><b>${v}</b><span>${t}</span></article>`).join("")}</div></section>`;
}

function workflowMarkup(): string {
  const w = state.workspace;
  if (!w) return `<section class="workflow-card"><p class="muted">Carregando operação do caso…</p></section>`;
  const o = w.operation;
  const overdue = new Date(o.due_at).getTime() < Date.now();
  return `<section class="workflow-card"><header><div><p class="eyebrow">Operação e SLA</p><strong class="sla ${overdue ? "overdue" : ""}">${overdue ? "Prazo vencido" : `Prazo ${date(o.due_at)}`}</strong></div><span class="priority ${o.priority}">${label(o.priority)}</span></header><div class="workflow-actions"><button data-workflow="${o.assigned_to ? "release" : "assign_self"}" class="quiet">${o.assigned_to ? "Liberar caso" : "Assumir caso"}</button><select id="workflow-priority"><option value="critical">Crítica · 2h</option><option value="high">Alta · 8h</option><option value="normal">Normal · 24h</option><option value="low">Baixa · 72h</option></select><button data-workflow="set_priority">Atualizar prioridade</button></div><label>Nota interna</label><textarea id="workflow-note" maxlength="2000" placeholder="Contexto para a equipe, sem dados desnecessários"></textarea><button data-workflow="add_note">Registrar nota</button><label>Modelo de resposta</label><select id="workflow-template"><option value="">Selecione</option>${w.templates.map(t=>`<option value="${esc(t.key)}" ${o.response_template_key===t.key?"selected":""}>${esc(t.title)}</option>`).join("")}</select><button data-workflow="select_template">Vincular modelo</button><button data-workflow="require_second_review" class="quiet" ${o.second_review_required?"disabled":""}>${o.second_review_required?"Segunda revisão exigida":"Exigir segunda revisão"}</button><div class="internal-notes"><strong>Histórico interno</strong>${w.notes.length?w.notes.map(n=>`<article><p>${esc(n.note)}</p><small>${esc(n.author_name??"Equipe")} · ${date(n.created_at)}</small></article>`).join(""):`<p class="muted">Nenhuma nota interna.</p>`}</div></section>`;
}

function caseDetailMarkup(c: Case): string {
  return `<button id="back-cases" class="quiet">← Voltar à fila</button>${workflowMarkup()}<div class="case-layout"><section><p class="eyebrow">Caso ${esc(c.case_id.slice(0, 8))}</p><h2>${esc(c.display_name ?? "Perfil sem nome")}</h2><div class="case-facts"><span>Motivo <b>${label(c.reason)}</b></span><span>Reincidência <b>${c.repeat_report_count}</b></span><span>Conta <b>${label(c.account_status)}</b></span></div>${c.details ? `<blockquote>${esc(c.details)}</blockquote>` : ""}${c.message_body ? `<div class="evidence-text"><small>MENSAGEM DENUNCIADA</small><p>${esc(c.message_body)}</p></div>` : ""}${c.has_album_evidence ? `<div class="album-evidence"><div><strong>Evidência privada vinculada</strong><small>Somente objetos preservados neste caso.</small></div><button id="load-evidence">Carregar prévias por 60 s</button></div>${state.evidence.length ? `<div class="evidence-grid">${state.evidence.map((e, i) => `<article><img src="${esc(e.preview_url)}" alt="Evidência ${i + 1}"><button data-remove-item="${esc(e.album_item_id)}" data-album="${e.album_id}" ${!e.album_item_id ? "disabled" : ""}>Remover item</button></article>`).join("")}</div><button id="remove-album" data-album="${state.evidence[0].album_id}" class="danger">Remover álbum denunciado</button>` : ""}` : ""}</section><aside><strong>Decisão do caso</strong><p class="muted">A ação fica registrada no histórico.</p><button data-case-action="resolve_case" class="approve">Resolver caso</button><button data-case-action="dismiss_case" class="quiet">Descartar denúncia</button>${isAdmin() ? `<hr><strong>Sanção da conta</strong>${sanctionButtons(c.reported_user_id)}` : '<p class="muted">Sanções exigem papel de administrador.</p>'}</aside></div>`;
}

function usersMarkup(): string {
  if (state.selectedUser) return userDetailMarkup(state.selectedUser);
  return `<form id="user-search" class="search"><input id="search" placeholder="Nome ou identificador da conta" value="${esc(state.search)}"><button>Pesquisar</button></form>${!state.users.length ? empty("Nenhuma conta encontrada", "Pesquise pelo nome exibido ou identificador técnico.") : `<div class="user-list">${state.users.map((u) => `<article><div><strong>${esc(u.display_name ?? "Perfil sem nome")}</strong><small>${u.user_id.slice(0, 8)} · criado em ${date(u.created_at)}</small></div><span class="status ${u.account_status}">${label(u.account_status)}</span><span>${u.report_count} denúncia(s)</span><span>${u.active_sanction ? label(u.active_sanction) : "Sem sanção ativa"}</span><div class="user-row-actions"><button data-user-detail="${u.user_id}" class="quiet">Abrir ficha</button>${isAdmin() ? sanctionButtons(u.user_id, u.account_status) : ""}</div></article>`).join("")}</div>`}`;
}

function userDetailMarkup(u: UserDetail): string {
  const p = u.profile;
  return `<button id="back-users" class="quiet">← Voltar às contas</button><section class="dossier-head"><div><p class="eyebrow">Ficha operacional / ${u.user_id.slice(0, 8)}</p><h2>${esc(p.display_name ?? "Perfil sem nome")}</h2><p>${esc(p.intent ?? "Sem intenção informada")}</p></div><div class="dossier-state"><span class="status ${u.account_status}">${label(u.account_status)}</span><small>${p.verified ? "Identidade verificada" : "Identidade não verificada"}</small></div></section><div class="dossier-grid"><section><p class="eyebrow">Perfil e atividade</p><dl><div><dt>Idade</dt><dd>${p.age ?? "—"}</dd></div><div><dt>Região aproximada</dt><dd>${esc(p.region_code ?? "—")}</dd></div><div><dt>Última atividade</dt><dd>${p.last_active_at ? date(p.last_active_at) : "—"}</dd></div><div><dt>Foto pública</dt><dd>${p.has_photo ? "Disponível" : "Sem foto"}</dd></div><div><dt>Busca</dt><dd>${p.discovery_visible ? "Visível" : "Oculto"}</dd></div><div><dt>Maioridade</dt><dd>${u.adult_verified_at ? "Verificada" : "Pendente"}</dd></div></dl><p class="profile-bio">${esc(p.bio ?? "Sem apresentação.")}</p><div class="tag-list">${(p.interests ?? []).map((i) => `<span>${esc(i)}</span>`).join("") || "<small>Sem interesses informados</small>"}</div></section><section><p class="eyebrow">Risco e acesso</p><div class="risk-numbers"><article><b>${u.risk.reports_received}</b><span>Denúncias</span></article><article><b>${u.risk.open_cases}</b><span>Casos abertos</span></article><article><b>${u.risk.blocks_involving_account}</b><span>Bloqueios</span></article><article><b>${u.access.active_sessions}</b><span>Sessões</span></article><article><b>${u.access.active_devices}</b><span>Dispositivos</span></article></div><p class="muted">Última sessão: ${u.access.last_session_at ? date(u.access.last_session_at) : "sem sessão ativa"}. Último dispositivo: ${u.access.last_device_seen_at ? date(u.access.last_device_seen_at) : "não registrado"}.</p>${isAdmin() ? `<div class="dossier-actions">${sanctionButtons(u.user_id, u.account_status)}</div>` : ""}</section><section><p class="eyebrow">Sanções</p>${u.sanctions.length ? `<div class="compact-list">${u.sanctions.map((s) => `<article><div><strong>${label(s.kind)}</strong><small>${label(s.reason)} · ${date(s.created_at)}</small></div><span class="status">${s.active ? "Ativa" : "Encerrada"}</span></article>`).join("")}</div>` : `<p class="muted">Nenhuma sanção registrada.</p>`}</section><section><p class="eyebrow">Histórico de denúncias</p>${u.reports.length ? `<div class="compact-list">${u.reports.map((r) => `<article><div><strong>${label(r.reason)}</strong><small>${date(r.created_at)}${r.has_message ? " · mensagem vinculada" : ""}</small></div><span class="status">${label(r.state)}</span></article>`).join("")}</div>` : `<p class="muted">Nenhuma denúncia recebida.</p>`}</section></div>`;
}

function sanctionButtons(userId: string, status = "active"): string {
  return status === "suspended"
    ? `<button data-user-action="reactivate_user" data-user="${userId}" class="approve">Reativar</button>`
    : `<button data-user-action="warn_user" data-user="${userId}" class="quiet">Advertir</button><button data-user-action="suspend_user" data-user="${userId}" class="block">Suspender</button><button data-user-action="ban_user" data-user="${userId}" class="danger">Banir</button>`;
}

function historyMarkup(): string {
  return !state.audit.length
    ? empty(
        "Histórico vazio",
        "Decisões da central aparecerão aqui sem conteúdo sensível.",
      )
    : `<div class="timeline">${state.audit.map((a) => `<article><span></span><div><strong>${label(a.event_type.replace("moderation_console.", ""))}</strong><small>${a.decision ? label(a.decision) : "Decisão normalizada"} · ${a.subject_user_id?.slice(0, 8) ?? "sem conta"}</small></div><time>${date(a.created_at)}</time></article>`).join("")}</div>`;
}

function teamMarkup(): string {
  if (!isAdmin())
    return empty(
      "Acesso administrativo",
      "Somente administradores ativos gerenciam a equipe.",
    );
  return `<section class="team-add"><div><p class="eyebrow">Alterar acesso</p><h2>Adicionar ou atualizar integrante</h2><p class="muted">O último administrador ativo não pode ser removido.</p></div><form id="staff-form"><input id="staff-email" type="email" placeholder="E-mail da conta ativa" required><select id="staff-role"><option value="reviewer">Revisor</option><option value="admin">Administrador</option></select><label><input id="staff-active" type="checkbox" checked> Acesso ativo</label><button>Salvar acesso</button></form></section><div class="staff-list">${state.staff.map((s) => `<article><div><strong>${esc(s.display_name ?? "Conta da equipe")}</strong><small>${s.user_id.slice(0, 8)}</small></div><b>${s.staff_role}</b><span class="status">${s.active ? "Ativo" : "Inativo"}</span></article>`).join("")}</div><section class="security-note"><strong>Segundo fator recomendado</strong><p>Ative MFA no provedor de autenticação antes de liberar ações administrativas em produção aberta.</p></section>`;
}

function empty(title: string, detail: string): string {
  return `<section class="empty"><div class="empty-ring">✓</div><h2>${title}</h2><p class="muted">${detail}</p></section>`;
}
function confirmMarkup(): string {
  const p = state.pending!;
  return `<div class="modal-backdrop"><section class="modal" role="dialog" aria-modal="true"><p class="eyebrow">Confirmar ação</p><h2>${esc(p.title)}</h2><p>${esc(p.detail)}</p>${p.action.includes("user") && p.action !== "reactivate_user" ? `<label>Motivo</label><select id="action-reason"><option value="safety">Segurança</option><option value="spam">Spam</option><option value="harassment">Assédio</option><option value="fake_profile">Perfil falso</option><option value="adult_content">Conteúdo adulto</option><option value="abusive_content">Conteúdo abusivo</option><option value="other">Outro</option></select>${p.action === "suspend_user" ? `<label>Duração</label><select id="suspension-hours"><option value="24">24 horas</option><option value="72">3 dias</option><option value="168">7 dias</option><option value="336">14 dias</option><option value="720">30 dias</option></select>` : ""}` : ""}<div><button id="cancel-action" class="quiet">Cancelar</button><button id="confirm-action" class="${p.action.includes("ban") || p.action.includes("remove") ? "danger" : "approve"}">Confirmar</button></div></section></div>`;
}

async function api(path: string, init?: RequestInit): Promise<any> {
  const response = await fetch(`${endpoint}${path}`, {
    ...init,
    headers: {
      Authorization: `Bearer ${state.session?.access_token}`,
      "Content-Type": "application/json",
      ...(init?.headers ?? {}),
    },
  });
  const body = await response.json().catch(() => ({}));
  if (response.status === 403)
    throw new Error("Seu papel não permite esta ação.");
  if (response.status === 409)
    throw new Error("O item mudou. Atualize a tela.");
  if (response.status === 429)
    throw new Error("Muitas decisões em sequência. Aguarde um minuto.");
  if (!response.ok) throw new Error("A operação não pôde ser concluída.");
  return body;
}
async function rpc<T>(
  name: string,
  parameters: Record<string, unknown> = {},
): Promise<T> {
  const { data, error } = await supabase!.rpc(name, parameters);
  if (error)
    throw new Error(
      error.code === "42501"
        ? "Seu papel não permite esta ação."
        : "A operação não pôde ser concluída.",
    );
  return data as T;
}
async function loadOverview() {
  state.overview = await rpc<Overview>("get_moderation_console_overview");
}
async function loadView(showLoader = true) {
  if (state.loading || state.syncing) return;
  if (showLoader) {
    state.loading = true;
    state.message = "";
    render();
  } else {
    state.syncing = true;
    setSyncingIndicator(true);
  }
  try {
    await loadOverview();
    if (state.view === "photos") {
      const b = await api("?page_size=20");
      state.photos = b.items ?? [];
    }
    if (state.view === "reports") {
      const f = state.reportFilters;
      state.cases =
        (await rpc<Case[]>("list_moderation_cases_v2", {
          page_size: 30,
          state_filter: "open",
          reason_filter: f.reason,
          evidence_filter: f.evidence,
          priority_filter: f.priority,
          sort_order: f.sort,
        })) ?? [];
    }
    if (state.view === "appeals") state.appeals = (await rpc<Appeal[]>("list_moderation_appeals_for_review", { page_size: 50 })) ?? [];
    if (state.view === "metrics") state.metrics = await rpc<Metrics>("get_moderation_metrics", { days: 30 });
    if (state.view === "users" && !state.selectedUser) {
      state.users =
        (await rpc<UserRow[]>("search_moderation_users", {
          search_text: state.search,
          page_size: 30,
        })) ?? [];
    }
    if (state.view === "history") {
      state.audit =
        (await rpc<AuditRow[]>("list_moderation_audit", { page_size: 50 })) ??
        [];
    }
    if (state.view === "team" && isAdmin()) {
      state.staff = (await rpc<StaffRow[]>("list_moderation_staff")) ?? [];
    }
  } catch (e) {
    state.message = e instanceof Error ? e.message : "Falha ao atualizar.";
  } finally {
    state.loading = false;
    state.syncing = false;
    setSyncingIndicator(false);
    state.lastSyncedAt = new Date();
    const active = document.activeElement;
    const editing = active instanceof HTMLInputElement ||
      active instanceof HTMLTextAreaElement || active instanceof HTMLSelectElement;
    if (showLoader || !editing) render(!showLoader);
  }
}
async function runAction() {
  const p = state.pending!;
  const reason =
    document.querySelector<HTMLSelectElement>("#action-reason")?.value;
  const hours = Number(
    document.querySelector<HTMLSelectElement>("#suspension-hours")?.value,
  );
  state.pending = null;
  state.loading = true;
  render();
  try {
    await api("?route=actions", {
      method: "POST",
      body: JSON.stringify({
        ...p.payload,
        action: p.action,
        reason: reason ?? null,
        suspension_hours: Number.isInteger(hours) && hours > 0 ? hours : null,
      }),
    });
    state.message = "Ação registrada.";
    state.selectedCase = null;
    state.evidence = [];
    state.loading = false;
    await loadView();
  } catch (e) {
    state.loading = false;
    state.message = e instanceof Error ? e.message : "Ação não concluída.";
    render();
  }
}
function queueAction(
  action: string,
  title: string,
  detail: string,
  payload: Record<string, unknown>,
) {
  state.pending = { action, title, detail, payload };
  render();
}

function bindConsole() {
  document.querySelectorAll<HTMLElement>("[data-view]").forEach((el) =>
    el.addEventListener("click", () => {
      state.view = el.dataset.view as View;
      state.selectedCase = null;
      state.selectedUser = null;
      state.evidence = [];
      void loadView();
    }),
  );
  document
    .querySelector("#logout")
    ?.addEventListener("click", () => supabase!.auth.signOut());
  document
    .querySelector("#refresh")
    ?.addEventListener("click", () => loadView(false));
  document.querySelectorAll<HTMLButtonElement>("[data-photo]").forEach((b) =>
    b.addEventListener("click", () => {
      const item = state.photos[0];
      const d = b.dataset.photo as ReviewDecision;
      queueAction(
        "photo",
        decisionLabel(d),
        "A foto será removida da fila e a decisão ficará auditada.",
        {
          profile_id: item.profile_id,
          candidate_path: item.candidate_path,
          decision: d,
        },
      );
    }),
  );
  document.querySelectorAll<HTMLButtonElement>("[data-case]").forEach((b) =>
    b.addEventListener("click", async () => {
      state.selectedCase =
        state.cases.find((c) => c.case_id === b.dataset.case) ?? null;
      state.workspace = state.selectedCase ? await rpc<Workspace>("get_moderation_case_workspace", { target_case_id: state.selectedCase.case_id }) : null;
      render();
    }),
  );
  document.querySelector("#back-cases")?.addEventListener("click", () => {
    state.selectedCase = null;
    state.workspace = null;
    state.evidence = [];
    render();
  });
  document
    .querySelector<HTMLFormElement>("#report-filters")
    ?.addEventListener("submit", (e) => {
      e.preventDefault();
      state.reportFilters = {
        priority:
          document.querySelector<HTMLSelectElement>("#priority-filter")!.value,
        reason:
          document.querySelector<HTMLSelectElement>("#reason-filter")!.value,
        evidence:
          document.querySelector<HTMLSelectElement>("#evidence-filter")!.value,
        sort: document.querySelector<HTMLSelectElement>("#sort-filter")!.value,
      };
      void loadView();
    });
  document
    .querySelector("#load-evidence")
    ?.addEventListener("click", async () => {
      try {
      const b = await api(
        `?route=album-evidence&case_id=${state.selectedCase?.case_id}`,
      );
      state.evidence = b.items ?? [];
      state.message = state.evidence.length
        ? ""
        : "A evidência preservada expirou ou não está mais disponível.";
      render();
      } catch (e) {
        state.message = (e as Error).message;
        render();
      }
    });
  document
    .querySelectorAll<HTMLButtonElement>("[data-case-action]")
    .forEach((b) =>
      b.addEventListener("click", () =>
        queueAction(
          b.dataset.caseAction!,
          b.textContent ?? "Decidir caso",
          "Esta decisão não apaga o histórico.",
          { target_case_id: state.selectedCase?.case_id },
        ),
      ),
    );
  document
    .querySelectorAll<HTMLButtonElement>("[data-user-action]")
    .forEach((b) =>
      b.addEventListener("click", () =>
        queueAction(
          b.dataset.userAction!,
          b.textContent ?? "Alterar conta",
          "Descoberta, conversas, sessões e álbuns obedecerão ao novo estado.",
          { target_user_id: b.dataset.user },
        ),
      ),
    );
  document
    .querySelectorAll<HTMLButtonElement>("[data-user-detail]")
    .forEach((b) =>
      b.addEventListener("click", async () => {
        state.loading = true;
        render();
        try {
          state.selectedUser = await rpc<UserDetail>(
            "get_moderation_user_detail",
            { target_user_id: b.dataset.userDetail },
          );
          state.message = "";
        } catch (e) {
          state.message = (e as Error).message;
        } finally {
          state.loading = false;
          render();
        }
      }),
    );
  document.querySelector("#back-users")?.addEventListener("click", () => {
    state.selectedUser = null;
    render();
  });
  document
    .querySelectorAll<HTMLButtonElement>("[data-remove-item]")
    .forEach((b) =>
      b.addEventListener("click", () =>
        queueAction(
          "remove_album_item",
          "Remover item denunciado",
          "O objeto ficará indisponível e seguirá a rotina de retenção/limpeza.",
          {
            target_case_id: state.selectedCase?.case_id,
            target_album_id: b.dataset.album,
            target_album_item_id: b.dataset.removeItem,
          },
        ),
      ),
    );
  document.querySelector("#remove-album")?.addEventListener("click", (e) => {
    const b = e.currentTarget as HTMLButtonElement;
    queueAction(
      "remove_album",
      "Remover álbum denunciado",
      "Todas as concessões serão revogadas e os itens ficarão indisponíveis.",
      {
        target_case_id: state.selectedCase?.case_id,
        target_album_id: b.dataset.album,
      },
    );
  });
  document.querySelector("#cancel-action")?.addEventListener("click", () => {
    state.pending = null;
    render();
  });
  document.querySelector("#confirm-action")?.addEventListener("click", () => {
    if (state.pending?.action === "photo") void decidePhoto();
    else void runAction();
  });
  document.querySelectorAll<HTMLButtonElement>("[data-appeal]").forEach(b => b.addEventListener("click", async () => {
    const note = window.prompt("Registre a justificativa da segunda revisão (mínimo 3 caracteres):");
    if (!note) return;
    try { await rpc("review_moderation_appeal", { target_appeal_id: b.dataset.id, decision: b.dataset.appeal, note_value: note }); state.message="Recurso revisado e auditado."; await loadView(); } catch(e) { state.message=(e as Error).message; render(); }
  }));
  document.querySelector("#export-audit")?.addEventListener("click", async () => {
    try { const end=new Date(); const start=new Date(end.getTime()-30*86400000); const data=await rpc<unknown>("export_moderation_audit", { start_at:start.toISOString(), end_at:end.toISOString() }); const blob=new Blob([JSON.stringify(data,null,2)],{type:"application/json"}); const url=URL.createObjectURL(blob); const a=document.createElement("a"); a.href=url; a.download=`vibeali-auditoria-${end.toISOString().slice(0,10)}.json`; a.click(); URL.revokeObjectURL(url); state.message="Exportação auditável gerada."; render(); } catch(e) { state.message=(e as Error).message; render(); }
  });
  document.querySelectorAll<HTMLButtonElement>("[data-workflow]").forEach((button) =>
    button.addEventListener("click", async () => {
      if (!state.selectedCase) return;
      const action = button.dataset.workflow!;
      const note = document.querySelector<HTMLTextAreaElement>("#workflow-note")?.value.trim() ?? null;
      const priority = document.querySelector<HTMLSelectElement>("#workflow-priority")?.value ?? null;
      const template = document.querySelector<HTMLSelectElement>("#workflow-template")?.value || null;
      if (action === "add_note" && (!note || note.length < 3)) { state.message = "A nota interna precisa ter pelo menos 3 caracteres."; render(); return; }
      if (action === "select_template" && !template) { state.message = "Selecione um modelo de resposta."; render(); return; }
      try {
        await rpc("moderation_workflow_action", { action, target_case_id: state.selectedCase.case_id,
          priority_value: action === "set_priority" ? priority : null,
          note_value: action === "add_note" ? note : null,
          template_key_value: action === "select_template" ? template : null });
        state.workspace = await rpc<Workspace>("get_moderation_case_workspace", { target_case_id: state.selectedCase.case_id });
        state.message = "Fluxo operacional atualizado e auditado.";
      } catch (e) { state.message = (e as Error).message; }
      render();
    }),
  );
  document
    .querySelector<HTMLFormElement>("#user-search")
    ?.addEventListener("submit", (e) => {
      e.preventDefault();
      state.search = (
        document.querySelector<HTMLInputElement>("#search")?.value ?? ""
      ).trim();
      void loadView();
    });
  document
    .querySelector<HTMLFormElement>("#staff-form")
    ?.addEventListener("submit", async (e) => {
      e.preventDefault();
      try {
        await api("?route=staff", {
          method: "POST",
          body: JSON.stringify({
            email:
              document.querySelector<HTMLInputElement>("#staff-email")!.value,
            role: document.querySelector<HTMLSelectElement>("#staff-role")!
              .value,
            active:
              document.querySelector<HTMLInputElement>("#staff-active")!
                .checked,
          }),
        });
        state.message = "Acesso da equipe atualizado.";
        await loadView();
      } catch (err) {
        state.message = (err as Error).message;
        render();
      }
    });
}
async function decidePhoto() {
  const p = state.pending!;
  state.pending = null;
  state.loading = true;
  render();
  try {
    await api("", { method: "POST", body: JSON.stringify(p.payload) });
    state.message = "Decisão da foto registrada.";
    state.loading = false;
    await loadView();
  } catch (e) {
    state.loading = false;
    state.message = (e as Error).message;
    render();
  }
}

async function requestEmailOtp(email: string): Promise<Response> {
  return fetch(`${supabaseUrl}/auth/v1/otp`, {
    method: "POST",
    headers: {
      apikey: anonKey!,
      Authorization: `Bearer ${anonKey}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ email, create_user: false }),
  });
}
function bindLogin() {
  document
    .querySelector<HTMLFormElement>("#email-form")
    ?.addEventListener("submit", async (e) => {
      e.preventDefault();
      state.authEmail = document
        .querySelector<HTMLInputElement>("#email")!
        .value.trim();
      state.message = "Enviando código…";
      render();
      try {
        const response = await requestEmailOtp(state.authEmail);
        state.otpSent = response.ok;
        state.message = response.ok
          ? "Código enviado."
          : response.status === 429
            ? "Muitas tentativas. Aguarde alguns minutos."
            : "Não foi possível enviar o código.";
      } catch {
        state.otpSent = false;
        state.message = "Não foi possível conectar ao serviço de login.";
      }
      render();
    });
  document
    .querySelector<HTMLFormElement>("#otp-form")
    ?.addEventListener("submit", async (e) => {
      e.preventDefault();
      const token = document.querySelector<HTMLInputElement>("#otp")!.value;
      const { error } = await supabase!.auth.verifyOtp({
        email: state.authEmail,
        token,
        type: "email",
      });
      if (error) {
        state.message = "Código inválido ou expirado.";
        render();
      }
    });
  document.querySelector("#change-email")?.addEventListener("click", () => {
    state.otpSent = false;
    state.message = "";
    render();
  });
}
function resetIdle() {
  clearTimeout(idleTimer);
  if (state.session)
    idleTimer = window.setTimeout(
      () => {
        state.message = "Sessão encerrada por inatividade.";
        void supabase!.auth.signOut();
      },
      15 * 60 * 1000,
    );
}
function resetAutoRefresh() {
  clearInterval(autoRefreshTimer);
  if (state.session)
    autoRefreshTimer = window.setInterval(() => {
      if (document.visibilityState === "visible") void loadView(false);
    }, 60_000);
}

function scheduleRealtimeRefresh(): void {
  clearTimeout(realtimeRefreshTimer);
  realtimeRefreshTimer = window.setTimeout(() => void loadView(false), 250);
}

function resetRealtime(): void {
  clearTimeout(realtimeRefreshTimer);
  if (realtimeChannel) {
    void supabase?.removeChannel(realtimeChannel);
    realtimeChannel = null;
  }
  if (!state.session || !supabase) return;
  state.realtimeStatus = "connecting";
  realtimeChannel = supabase
    .channel("moderation-console-sync")
    .on(
      "postgres_changes",
      {
        event: "UPDATE",
        schema: "public",
        table: "moderation_console_sync",
      },
      scheduleRealtimeRefresh,
    )
    .subscribe((status) => {
      const next = status === "SUBSCRIBED" ? "live" :
        status === "CHANNEL_ERROR" || status === "TIMED_OUT" ? "offline" :
          "connecting";
      if (state.realtimeStatus !== next) {
        state.realtimeStatus = next;
        render(true);
      }
    });
}
["click", "keydown", "pointerdown"].forEach((event) =>
  window.addEventListener(event, resetIdle, { passive: true }),
);
document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "visible" && state.session) void loadView(false);
});
window.addEventListener("focus", () => {
  if (state.session) void loadView(false);
});
supabase?.auth.onAuthStateChange((_event, session) => {
  state.session = session;
  resetIdle();
  resetAutoRefresh();
  resetRealtime();
  render();
  if (session) window.setTimeout(() => void loadView(true), 0);
});
render();
