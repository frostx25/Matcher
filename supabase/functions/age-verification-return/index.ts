const completeDeepLink = "matcher://age-verification/complete";
const cancelledDeepLink = "matcher://age-verification/cancelled";

const responseHeaders = {
  "content-type": "text/html; charset=utf-8",
  "cache-control": "no-store",
  "content-security-policy":
    "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'; form-action 'none'; base-uri 'none'",
  "cross-origin-opener-policy": "same-origin",
  "permissions-policy": "camera=(), microphone=(), geolocation=()",
  "referrer-policy": "no-referrer",
  "x-content-type-options": "nosniff",
  "x-frame-options": "DENY",
};

Deno.serve((request) => {
  if (request.method !== "GET" && request.method !== "HEAD") {
    return new Response(null, {
      status: 405,
      headers: { "allow": "GET, HEAD" },
    });
  }

  const requestUrl = new URL(request.url);
  const deepLink = requestUrl.searchParams.get("cancelled") === "1"
    ? cancelledDeepLink
    : completeDeepLink;
  const completed = deepLink === completeDeepLink;
  const title = completed ? "Verificação concluída" : "Verificação cancelada";
  const message = completed
    ? "Volte ao Matcher para consultar o resultado confirmado pelo servidor."
    : "Você pode voltar ao Matcher e tentar novamente quando quiser.";
  const html = `<!doctype html>
<html lang="pt-BR"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="refresh" content="0;url=${deepLink}"><title>${title}</title>
<style>body{font-family:system-ui;background:#0b0b0f;color:#fff7fb;display:grid;place-items:center;min-height:100vh;margin:0}main{max-width:34rem;padding:2rem;text-align:center}a{display:inline-block;background:#ff2d7a;color:#0b0b0f;padding:1rem 1.4rem;border-radius:1rem;text-decoration:none;font-weight:700}</style>
</head><body><main><h1>${title}</h1><p>${message}</p><a href="${deepLink}">Voltar ao Matcher</a></main></body></html>`;

  return new Response(request.method === "HEAD" ? null : html, {
    headers: responseHeaders,
  });
});
