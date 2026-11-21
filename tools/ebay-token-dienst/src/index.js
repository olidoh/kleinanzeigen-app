// Token-Dienst für die eBay-Anmeldung des Inserat-Helfers.
//
// eBay verlangt beim Eintausch des Anmeldecodes das Client Secret der Anwendung. Eine App kann das
// nicht geheim halten, deshalb liegt es nur hier (als Cloudflare-Secret). Der Dienst speichert nichts:
// Er reicht Code bzw. Refresh-Token an eBay weiter und gibt eBays Antwort unverändert an die App zurück.
//
//   POST /token          {"code": "...", "sandbox": "false"}            -> eBay-Token-Antwort
//   POST /refresh        {"refresh_token": "...", "scope": "...", ...}  -> eBay-Token-Antwort
//   GET  /eingeloggt     Seite nach erfolgreicher Anmeldung („Auth Accepted URL“)
//   GET  /abgelehnt      Seite nach abgelehnter Anmeldung („Auth Declined URL“)
//   GET  /datenschutz    Datenschutzhinweis (eBay verlangt eine Adresse dafür)
//   GET/POST /konto-loeschung  Pflicht-Endpunkt für eBays „Marketplace Account Deletion“

const ALLOWED_SCOPES = new Set([
  "https://api.ebay.com/oauth/api_scope",
  "https://api.ebay.com/oauth/api_scope/sell.inventory",
  "https://api.ebay.com/oauth/api_scope/sell.account",
  "https://api.ebay.com/oauth/api_scope/commerce.identity.readonly",
]);

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    try {
      switch (`${request.method} ${url.pathname}`) {
        case "POST /token":
          return await token(request, env, "authorization_code");
        case "POST /refresh":
          return await token(request, env, "refresh_token");
        case "GET /konto-loeschung":
          return await deletionChallenge(url, env);
        case "POST /konto-loeschung":
          // Der Dienst speichert keine eBay-Nutzerdaten; die Meldung wird nur bestätigt.
          return new Response(null, { status: 204 });
        case "GET /eingeloggt":
          return page("Bei eBay angemeldet", "Die Anmeldung hat geklappt. Du kannst zur App zurückkehren.");
        case "GET /abgelehnt":
          return page("Anmeldung abgebrochen", "Du hast der App keinen Zugriff gegeben. Du kannst zur App zurückkehren.");
        case "GET /datenschutz":
          return page(
            "Datenschutz",
            "Der Inserat-Helfer ist eine Android-App. Dieser Dienst tauscht nur den Anmeldecode von eBay gegen " +
              "einen Zugangsschlüssel und gibt ihn an die App auf deinem Handy zurück. Er speichert weder " +
              "Zugangsschlüssel noch Anzeigen oder andere Daten. Die Zugangsschlüssel liegen verschlüsselt nur " +
              "auf deinem Handy. Abmelden kannst du dich jederzeit in der App oder bei eBay unter " +
              "Konto > Einstellungen für Drittanbieter-Apps.",
          );
        default:
          return json({ error: "not_found" }, 404);
      }
    } catch (e) {
      return json({ error: "server_error", error_description: String(e && e.message ? e.message : e) }, 500);
    }
  },
};

async function token(request, env, grantType) {
  const body = await request.json().catch(() => ({}));
  const sandbox = String(body.sandbox) === "true";
  const clientId = sandbox ? env.EBAY_SANDBOX_CLIENT_ID : env.EBAY_CLIENT_ID;
  const secret = sandbox ? env.EBAY_SANDBOX_CLIENT_SECRET : env.EBAY_CLIENT_SECRET;
  const ruName = sandbox ? env.EBAY_SANDBOX_RUNAME : env.EBAY_RUNAME;
  if (!clientId || !secret || !ruName) {
    return json({ error: "not_configured", error_description: "Token-Dienst ist nicht vollständig eingerichtet." }, 500);
  }

  const form = new URLSearchParams({ grant_type: grantType });
  if (grantType === "authorization_code") {
    const code = typeof body.code === "string" ? body.code : "";
    if (!code || code.length > 4096) return json({ error: "invalid_request", error_description: "Code fehlt." }, 400);
    form.set("code", code);
    form.set("redirect_uri", ruName);
  } else {
    const refresh = typeof body.refresh_token === "string" ? body.refresh_token : "";
    if (!refresh || refresh.length > 4096) {
      return json({ error: "invalid_request", error_description: "Refresh-Token fehlt." }, 400);
    }
    const scopes = String(body.scope || "").split(" ").filter((s) => ALLOWED_SCOPES.has(s));
    form.set("refresh_token", refresh);
    form.set("scope", (scopes.length ? scopes : [...ALLOWED_SCOPES]).join(" "));
  }

  const host = sandbox ? "https://api.sandbox.ebay.com" : "https://api.ebay.com";
  const response = await fetch(`${host}/identity/v1/oauth2/token`, {
    method: "POST",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded",
      Authorization: "Basic " + btoa(`${clientId}:${secret}`),
    },
    body: form,
  });
  return new Response(await response.text(), {
    status: response.status,
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
  });
}

// eBay prüft den Endpunkt mit ?challenge_code=… und erwartet SHA-256(challengeCode + verificationToken + endpoint).
async function deletionChallenge(url, env) {
  const challenge = url.searchParams.get("challenge_code");
  if (!challenge || !env.EBAY_VERIFICATION_TOKEN) return json({ error: "invalid_request" }, 400);
  const endpoint = `${url.origin}${url.pathname}`;
  const data = new TextEncoder().encode(challenge + env.EBAY_VERIFICATION_TOKEN + endpoint);
  const digest = await crypto.subtle.digest("SHA-256", data);
  const hex = [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
  return json({ challengeResponse: hex });
}

function json(value, status = 200) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
  });
}

function page(title, text) {
  const html = `<!doctype html><html lang="de"><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>${title}</title>
<body style="font-family:system-ui,sans-serif;max-width:36rem;margin:3rem auto;padding:0 1rem;line-height:1.5">
<h1 style="font-size:1.4rem">${title}</h1><p>${text}</p></body></html>`;
  return new Response(html, { headers: { "Content-Type": "text/html; charset=utf-8" } });
}
