// Chave_Transito e identificador de dispositivo do Cliente_Web.
//
// A Chave_Transito (32 bytes, 64 digitos hexadecimais) chega ao navegador por
// um meio distinto da mensagem: o usuario a digita na tela de login ou abre um
// link com `#chave=<hex>`. O fragmento nunca e enviado ao servidor HTTP e e
// apagado da URL logo apos a leitura. A chave fica no `localStorage`.
//
// O identificador de dispositivo (UUID v4) e gerado na primeira execucao e
// guardado no `localStorage`; e enviado no `login` e usado nos logs.

/** Chave do `localStorage` onde fica a Chave_Transito (hexadecimal). */
export const CHAVE_STORAGE_TRANSITO = 'comunicador.chave_transito';

/** Chave do `localStorage` onde fica o identificador do dispositivo. */
export const CHAVE_STORAGE_DISPOSITIVO = 'comunicador.dispositivo';

/** Base dos recursos estaticos compartilhados (figurinhas, avatares, JSON). */
export const BASE_RECURSOS = '/recursos/';

const REGEX_CHAVE_HEX = /^[0-9a-fA-F]{64}$/;
const REGEX_UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

/** Monta a URL de um recurso, sempre sob `/recursos/` (ex.: `urlRecurso('emojis.json')`). */
export function urlRecurso(caminho) {
  return BASE_RECURSOS + String(caminho).replace(/^\/+/, '');
}

/** Indica se `texto` e uma Chave_Transito valida (64 digitos hexadecimais). */
export function chaveValida(texto) {
  return typeof texto === 'string' && REGEX_CHAVE_HEX.test(texto);
}

/** Normaliza a chave digitada: remove espacos nas pontas e usa minusculas. Devolve null se invalida. */
export function normalizarChave(texto) {
  if (typeof texto !== 'string') return null;
  const limpo = texto.trim().toLowerCase();
  return chaveValida(limpo) ? limpo : null;
}

/** Extrai a chave de um fragmento de URL (`#chave=<hex>`). Devolve null se ausente ou invalida. */
export function extrairChaveDoFragmento(hash) {
  if (typeof hash !== 'string' || hash.length === 0) return null;
  const sem = hash.startsWith('#') ? hash.slice(1) : hash;
  const valor = new URLSearchParams(sem).get('chave');
  return valor === null ? null : normalizarChave(valor);
}

/** Le a Chave_Transito guardada. Devolve null se nao houver ou se estiver corrompida. */
export function lerChaveTransito(storage = globalThis.localStorage) {
  try {
    return normalizarChave(storage.getItem(CHAVE_STORAGE_TRANSITO));
  } catch {
    return null;
  }
}

/** Guarda a Chave_Transito. Devolve a chave normalizada ou null se o texto for invalido (nada e gravado). */
export function guardarChaveTransito(texto, storage = globalThis.localStorage) {
  const chave = normalizarChave(texto);
  if (chave === null) return null;
  storage.setItem(CHAVE_STORAGE_TRANSITO, chave);
  return chave;
}

/** Remove a Chave_Transito guardada. */
export function esquecerChaveTransito(storage = globalThis.localStorage) {
  storage.removeItem(CHAVE_STORAGE_TRANSITO);
}

/**
 * Le `#chave=<hex>` da URL, guarda a chave (se valida) e apaga o fragmento da
 * URL e do historico do navegador. O fragmento e apagado mesmo quando invalido.
 * Devolve a chave guardada ou null.
 */
export function consumirFragmentoChave(
  localizacao = globalThis.location,
  historico = globalThis.history,
  storage = globalThis.localStorage,
) {
  const hash = localizacao?.hash ?? '';
  if (!hash) return null;
  const chave = extrairChaveDoFragmento(hash);
  if (chave !== null) guardarChaveTransito(chave, storage);
  if (new URLSearchParams(hash.replace(/^#/, '')).has('chave')) {
    historico.replaceState(null, '', localizacao.pathname + localizacao.search);
  }
  return chave;
}

/** Gera um UUID v4. */
export function gerarUuidV4() {
  const c = globalThis.crypto;
  if (typeof c?.randomUUID === 'function') return c.randomUUID();
  const b = new Uint8Array(16);
  c.getRandomValues(b);
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  const h = Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

/** Devolve o identificador do dispositivo, criando-o na primeira chamada. */
export function obterDispositivo(storage = globalThis.localStorage) {
  const atual = storage.getItem(CHAVE_STORAGE_DISPOSITIVO);
  if (typeof atual === 'string' && REGEX_UUID_V4.test(atual)) return atual;
  const novo = gerarUuidV4();
  storage.setItem(CHAVE_STORAGE_DISPOSITIVO, novo);
  return novo;
}

// ---------------------------------------------------------------------------
// Ligacao com a tela de login (somente no navegador)
// ---------------------------------------------------------------------------

/**
 * Consome o fragmento da URL, preenche o campo `#campo-chave` com a chave
 * guardada e garante o identificador do dispositivo.
 */
export function iniciarChave(doc = globalThis.document) {
  const chaveFragmento = consumirFragmentoChave();
  const chave = chaveFragmento ?? lerChaveTransito();
  const dispositivo = obterDispositivo();
  const campo = doc?.getElementById('campo-chave');
  if (campo && chave) campo.value = chave;
  if (doc?.documentElement) doc.documentElement.dataset.dispositivo = dispositivo;
  return { chave, dispositivo };
}

if (typeof document !== 'undefined' && typeof location !== 'undefined') {
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', () => iniciarChave());
  } else {
    iniciarChave();
  }
}
