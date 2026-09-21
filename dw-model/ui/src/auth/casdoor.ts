/** Casdoor 授权码 + PKCE。未配置环境变量时不要调用，控制台走演示或开发登录。 */

const VERIFIER_KEY = 'dw-ai.pkce.verifier';
const STATE_KEY = 'dw-ai.pkce.state';

export function casdoorEnv() {
  const endpoint = (import.meta.env.VITE_CASDOOR_ENDPOINT as string | undefined)?.replace(/\/$/, '') ?? '';
  const clientId = (import.meta.env.VITE_CASDOOR_CLIENT_ID as string | undefined) ?? '';
  const org = (import.meta.env.VITE_CASDOOR_ORG as string | undefined) ?? '';
  const app = (import.meta.env.VITE_CASDOOR_APP as string | undefined) ?? '';
  const redirectUri =
    (import.meta.env.VITE_CASDOOR_REDIRECT_URI as string | undefined) ||
    `${window.location.origin}/auth/callback`;
  return { endpoint, clientId, org, app, redirectUri };
}

export function casdoorReady() {
  const { endpoint, clientId } = casdoorEnv();
  return Boolean(endpoint && clientId);
}

function randomString(len = 48) {
  const bytes = new Uint8Array(len);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (b) => ('0' + (b % 36).toString(36)).slice(-1)).join('');
}

async function sha256Base64Url(text: string) {
  const data = new TextEncoder().encode(text);
  const hash = await crypto.subtle.digest('SHA-256', data);
  const bytes = new Uint8Array(hash);
  let bin = '';
  bytes.forEach((b) => {
    bin += String.fromCharCode(b);
  });
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export async function startCasdoorLogin() {
  const { endpoint, clientId, org, app, redirectUri } = casdoorEnv();
  if (!endpoint || !clientId) {
    throw new Error('未配置 VITE_CASDOOR_ENDPOINT / VITE_CASDOOR_CLIENT_ID');
  }
  const verifier = randomString(64);
  const state = randomString(24);
  sessionStorage.setItem(VERIFIER_KEY, verifier);
  sessionStorage.setItem(STATE_KEY, state);
  const challenge = await sha256Base64Url(verifier);
  const url = new URL(`${endpoint}/login/oauth/authorize`);
  url.searchParams.set('client_id', clientId);
  url.searchParams.set('response_type', 'code');
  url.searchParams.set('redirect_uri', redirectUri);
  url.searchParams.set('scope', 'openid profile email');
  url.searchParams.set('state', state);
  url.searchParams.set('code_challenge', challenge);
  url.searchParams.set('code_challenge_method', 'S256');
  if (org) url.searchParams.set('organization', org);
  if (app) url.searchParams.set('application', app);
  window.location.assign(url.toString());
}

export async function exchangeCasdoorCode(code: string, state: string) {
  const { endpoint, clientId, redirectUri } = casdoorEnv();
  const expect = sessionStorage.getItem(STATE_KEY);
  const verifier = sessionStorage.getItem(VERIFIER_KEY);
  sessionStorage.removeItem(STATE_KEY);
  sessionStorage.removeItem(VERIFIER_KEY);
  if (!verifier) throw new Error('缺少 PKCE verifier，请重新登录');
  if (expect && state && expect !== state) throw new Error('state 不匹配，请重新登录');
  const body = new URLSearchParams({
    grant_type: 'authorization_code',
    client_id: clientId,
    code,
    redirect_uri: redirectUri,
    code_verifier: verifier,
  });
  const res = await fetch(`${endpoint}/api/login/oauth/access_token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body,
  });
  if (!res.ok) throw new Error(await res.text());
  const json = (await res.json()) as { access_token?: string; error?: string; error_description?: string };
  if (!json.access_token) {
    throw new Error(json.error_description || json.error || 'Casdoor 未返回 access_token');
  }
  return json.access_token;
}
