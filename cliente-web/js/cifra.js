// Cifra_Manual (JavaScript).
//
// Cifra de fluxo com MAC manual (encrypt-then-MAC), sem crypto.subtle e sem
// biblioteca de cifragem. Apenas aritmetica de inteiros de 32 bits sem sinal
// (Math.imul, rotacoes, XOR, soma com >>>0). As unicas funcoes prontas usadas
// sao de codificacao (TextEncoder/TextDecoder, btoa/atob) e
// crypto.getRandomValues, exclusivamente para sortear o nonce.
//
// Formato (antes do Base64): versao(1) | nonce(8) | texto cifrado(n) | tag(8).

const VERSAO = 0x01;
const TAM_CHAVE = 32;
const TAM_NONCE = 8;
const TAM_TAG = 8;
const TAM_MINIMO = 1 + TAM_NONCE + TAM_TAG;

/** Erro lancado quando a decifragem e recusada (MAC, versao, Base64 ou UTF-8 invalidos). */
export class DecifragemFalhou extends Error {
  constructor(mensagem = 'Decifragem falhou') {
    super(mensagem);
    this.name = 'DecifragemFalhou';
  }
}

// ---------------------------------------------------------------------------
// Utilitarios de hexadecimal
// ---------------------------------------------------------------------------

/** Converte uma string hexadecimal (numero par de digitos) em Uint8Array. */
export function hexParaBytes(hex) {
  if (typeof hex !== 'string' || hex.length % 2 !== 0 || !/^[0-9a-fA-F]*$/.test(hex)) {
    throw new RangeError('Hexadecimal invalido');
  }
  const saida = new Uint8Array(hex.length / 2);
  for (let i = 0; i < saida.length; i++) {
    saida[i] = parseInt(hex.substr(i * 2, 2), 16);
  }
  return saida;
}

/** Converte bytes em string hexadecimal minuscula. */
export function bytesParaHex(bytes) {
  let saida = '';
  for (let i = 0; i < bytes.length; i++) {
    saida += (bytes[i] & 0xff).toString(16).padStart(2, '0');
  }
  return saida;
}

// ---------------------------------------------------------------------------
// Primitivas de 32 bits
// ---------------------------------------------------------------------------

function rotl(x, k) {
  return ((x << k) | (x >>> (32 - k))) >>> 0;
}

function imul(a, b) {
  return Math.imul(a, b) >>> 0;
}

// Passo A: mistura bytes em um estado h de quatro palavras.
function absorve(h, dados) {
  for (let i = 0; i < dados.length; i++) {
    const j = i & 3;
    let x = (h[j] ^ dados[i]) >>> 0;
    x = rotl(x, 5);
    x = imul(x, 0x9e3779b1);
    h[j] = x;
    const k = (j + 1) & 3;
    h[k] = (h[k] + (x >>> 13)) >>> 0;
  }
}

// Passo B: difusao final, 8 rodadas.
function mistura(h) {
  for (let r = 0; r < 8; r++) {
    for (let j = 0; j < 4; j++) {
      let x = (h[j] ^ (h[(j + 3) & 3] >>> 16)) >>> 0;
      x = imul(x, 0x85ebca6b);
      x = (rotl(x, 13) ^ h[(j + 1) & 3]) >>> 0;
      h[j] = x;
    }
  }
}

function concatenar(...partes) {
  let total = 0;
  for (const p of partes) total += p.length;
  const saida = new Uint8Array(total);
  let pos = 0;
  for (const p of partes) {
    saida.set(p, pos);
    pos += p.length;
  }
  return saida;
}

// Passo C: keystream de `tamanho` bytes, saidas de 4 bytes little-endian.
function keystream(chave, nonce, tamanho) {
  const h = new Uint32Array([0x243f6a88, 0x85a308d3, 0x13198a2e, 0x03707344]);
  absorve(h, concatenar(chave, nonce));
  mistura(h);
  const s = new Uint32Array(h);
  if (s[0] === 0 && s[1] === 0 && s[2] === 0 && s[3] === 0) s[0] = 1;

  const proximo = () => {
    const r = imul(rotl(imul(s[1], 5), 7), 9);
    const t = (s[1] << 9) >>> 0;
    s[2] = (s[2] ^ s[0]) >>> 0;
    s[3] = (s[3] ^ s[1]) >>> 0;
    s[1] = (s[1] ^ s[2]) >>> 0;
    s[0] = (s[0] ^ s[3]) >>> 0;
    s[2] = (s[2] ^ t) >>> 0;
    s[3] = rotl(s[3], 11);
    return r;
  };

  for (let i = 0; i < 8; i++) proximo(); // aquecimento

  const saida = new Uint8Array(tamanho);
  let pos = 0;
  while (pos < tamanho) {
    const w = proximo();
    for (let b = 0; b < 4 && pos < tamanho; b++) {
      saida[pos++] = (w >>> (8 * b)) & 0xff;
    }
  }
  return saida;
}

// Passo D: MAC de 8 bytes sobre versao || nonce || texto cifrado.
function calcularTag(chave, nonce, ct) {
  const h = new Uint32Array([0x452821e6, 0x38d01377, 0xbe5466cf, 0x34e90c6c]);
  absorve(h, concatenar(Uint8Array.of(0x4d), chave));
  absorve(h, concatenar(Uint8Array.of(VERSAO), nonce, ct));
  const n = ct.length >>> 0;
  absorve(h, Uint8Array.of((n >>> 24) & 0xff, (n >>> 16) & 0xff, (n >>> 8) & 0xff, n & 0xff));
  mistura(h);
  const a = (h[0] ^ h[2]) >>> 0;
  const b = (h[1] ^ h[3]) >>> 0;
  return Uint8Array.of(
    (a >>> 24) & 0xff, (a >>> 16) & 0xff, (a >>> 8) & 0xff, a & 0xff,
    (b >>> 24) & 0xff, (b >>> 16) & 0xff, (b >>> 8) & 0xff, b & 0xff,
  );
}

// ---------------------------------------------------------------------------
// Base64 (apenas transporte textual)
// ---------------------------------------------------------------------------

const REGEX_BASE64 = /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/;

function bytesParaBase64(bytes) {
  let bin = '';
  const lote = 0x2000;
  for (let i = 0; i < bytes.length; i += lote) {
    bin += String.fromCharCode.apply(null, bytes.subarray(i, i + lote));
  }
  return btoa(bin);
}

function base64ParaBytes(b64) {
  if (typeof b64 !== 'string' || !REGEX_BASE64.test(b64)) {
    throw new DecifragemFalhou('Base64 invalido');
  }
  let bin;
  try {
    bin = atob(b64);
  } catch {
    throw new DecifragemFalhou('Base64 invalido');
  }
  const saida = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) saida[i] = bin.charCodeAt(i);
  return saida;
}

// ---------------------------------------------------------------------------
// API publica
// ---------------------------------------------------------------------------

// Aceita Uint8Array/array de bytes ou string hexadecimal.
function normalizar(valor, tamanho, nome) {
  const bytes = typeof valor === 'string' ? hexParaBytes(valor) : Uint8Array.from(valor);
  if (bytes.length !== tamanho) {
    throw new RangeError(`${nome} deve ter ${tamanho} bytes`);
  }
  return bytes;
}

/** Cifra `texto` com `chave` e um nonce fornecido. Devolve Base64. */
export function cifrarComNonce(chave, nonce, texto) {
  const k = normalizar(chave, TAM_CHAVE, 'Chave');
  const n = normalizar(nonce, TAM_NONCE, 'Nonce');
  const pt = new TextEncoder().encode(texto);
  const ks = keystream(k, n, pt.length);
  const ct = new Uint8Array(pt.length);
  for (let i = 0; i < pt.length; i++) ct[i] = pt[i] ^ ks[i];
  const tag = calcularTag(k, n, ct);
  return bytesParaBase64(concatenar(Uint8Array.of(VERSAO), n, ct, tag));
}

/** Cifra `texto` com `chave` e um nonce aleatorio de 8 bytes. Devolve Base64. */
export function cifrar(chave, texto) {
  const nonce = new Uint8Array(TAM_NONCE);
  crypto.getRandomValues(nonce);
  return cifrarComNonce(chave, nonce, texto);
}

/** Decifra Base64 produzido por `cifrar`. Lanca DecifragemFalhou em qualquer irregularidade. */
export function decifrar(chave, b64) {
  const k = normalizar(chave, TAM_CHAVE, 'Chave');
  const bytes = base64ParaBytes(b64);
  if (bytes.length < TAM_MINIMO || bytes[0] !== VERSAO) {
    throw new DecifragemFalhou('Versao ou tamanho invalido');
  }
  const nonce = bytes.subarray(1, 1 + TAM_NONCE);
  const ct = bytes.subarray(1 + TAM_NONCE, bytes.length - TAM_TAG);
  const tag = bytes.subarray(bytes.length - TAM_TAG);

  const esperada = calcularTag(k, nonce, ct);
  let diferenca = 0;
  for (let i = 0; i < TAM_TAG; i++) diferenca |= esperada[i] ^ tag[i];
  if (diferenca !== 0) {
    throw new DecifragemFalhou('MAC invalido');
  }

  const ks = keystream(k, nonce, ct.length);
  const pt = new Uint8Array(ct.length);
  for (let i = 0; i < ct.length; i++) pt[i] = ct[i] ^ ks[i];
  try {
    return new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(pt);
  } catch {
    throw new DecifragemFalhou('UTF-8 invalido');
  }
}
