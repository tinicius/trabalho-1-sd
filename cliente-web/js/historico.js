// Historico_Local do Cliente_Web no IndexedDB.
//
// - Um banco por usuario: `comunicador_<login>`. O IndexedDB e por
//   origem e por perfil do navegador, o que da o escopo de dispositivo.
// - Object store `mensagens`: chave `mid`, indice `ts`. Cada registro tem o
//   formato do Repositorio_Conversas, com `conteudo` cifrado com a
//   Chave_Historico.
// - Object store `meta`: `historico_key` (32 bytes aleatorios em hexadecimal,
//   gerados neste dispositivo e nunca transmitidos) e `dispositivo` (UUID v4).
// - Incorporacao idempotente por `mid`: `add` com
//   `ConstraintError` significa "ja existe" e e ignorado. Duplicatas no mesmo
//   lote: vale a primeira.
// - Nada e removido automaticamente. Cada dispositivo recifra com a
//   propria chave e um nonce novo.
// - Exibicao ordenada por (ts, mid).

import { bytesParaHex, cifrar, decifrar } from './cifra.js';
import { gerarUuidV4 } from './chave.js';

/** Prefixo do nome do banco; o nome completo e `comunicador_<login>`. */
export const PREFIXO_BANCO = 'comunicador_';

export const STORE_MENSAGENS = 'mensagens';
export const STORE_META = 'meta';
export const META_CHAVE_HISTORICO = 'historico_key';
export const META_DISPOSITIVO = 'dispositivo';

const VERSAO_BANCO = 1;
const REGEX_LOGIN = /^[a-z0-9_]{3,20}$/;
const REGEX_CHAVE_HEX = /^[0-9a-f]{64}$/;
const TIPOS = new Set(['texto', 'figurinha']);

/** Erro de uso ou de armazenamento do Historico_Local. */
export class ErroHistorico extends Error {
  constructor(mensagem, causa) {
    super(mensagem);
    this.name = 'ErroHistorico';
    if (causa !== undefined) this.cause = causa;
  }
}

/** Nome do banco do usuario. Lanca ErroHistorico se o login nao seguir o formato da conta. */
export function nomeBanco(login) {
  if (typeof login !== 'string' || !REGEX_LOGIN.test(login)) {
    throw new ErroHistorico('Login invalido para o Historico_Local');
  }
  return PREFIXO_BANCO + login;
}

// ---------------------------------------------------------------------------
// Auxiliares de IndexedDB
// ---------------------------------------------------------------------------

function pedido(req) {
  return new Promise((resolve, reject) => {
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

function transacaoConcluida(tx) {
  return new Promise((resolve, reject) => {
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error ?? new ErroHistorico('Transacao abortada'));
  });
}

function abrirBanco(idb, nome) {
  return new Promise((resolve, reject) => {
    const req = idb.open(nome, VERSAO_BANCO);
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains(STORE_MENSAGENS)) {
        const mensagens = db.createObjectStore(STORE_MENSAGENS, { keyPath: 'mid' });
        mensagens.createIndex('ts', 'ts', { unique: false });
      }
      if (!db.objectStoreNames.contains(STORE_META)) {
        db.createObjectStore(STORE_META);
      }
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
    req.onblocked = () => reject(new ErroHistorico('Abertura do Historico_Local bloqueada'));
  });
}

function sortearChaveHex() {
  const bytes = new Uint8Array(32);
  globalThis.crypto.getRandomValues(bytes);
  return bytesParaHex(bytes);
}

/**
 * Le (ou cria, na primeira execucao) a Chave_Historico e o identificador do
 * dispositivo em uma unica transacao de escrita, para que duas abas abrindo ao
 * mesmo tempo nao criem chaves diferentes.
 */
async function garantirMeta(db, { dispositivo, chaveTransito }) {
  const tx = db.transaction([STORE_META], 'readwrite');
  const store = tx.objectStore(STORE_META);
  const fim = transacaoConcluida(tx);
  fim.catch(() => {}); // evita rejeicao nao tratada se um pedido falhar antes do `await fim`

  let chave = await pedido(store.get(META_CHAVE_HISTORICO));
  if (typeof chave !== 'string' || !REGEX_CHAVE_HEX.test(chave)) {
    do {
      chave = sortearChaveHex();
    } while (chave === chaveTransito); // a Chave_Historico deve diferir da de transito
    store.put(chave, META_CHAVE_HISTORICO);
  }

  let id = await pedido(store.get(META_DISPOSITIVO));
  if (typeof id !== 'string' || id.length === 0) {
    id = typeof dispositivo === 'string' && dispositivo.length > 0 ? dispositivo : gerarUuidV4();
    store.put(id, META_DISPOSITIVO);
  }

  await fim;
  return { chave, dispositivo: id };
}

// ---------------------------------------------------------------------------
// Validacao e ordenacao
// ---------------------------------------------------------------------------

function ehMensagemValida(m) {
  return (
    m !== null &&
    typeof m === 'object' &&
    typeof m.mid === 'string' && m.mid.length > 0 &&
    typeof m.ts === 'string' && m.ts.length > 0 &&
    TIPOS.has(m.tipo) &&
    m.remetente !== null && typeof m.remetente === 'object' &&
    typeof m.conteudo === 'string'
  );
}

/** Compara dois registros por (ts, mid). */
export function compararPorTsMid(a, b) {
  if (a.ts < b.ts) return -1;
  if (a.ts > b.ts) return 1;
  if (a.mid < b.mid) return -1;
  if (a.mid > b.mid) return 1;
  return 0;
}

function copiarRemetente(r) {
  return {
    login: r.login,
    apelido: r.apelido,
    avatar: r.avatar,
    cor: r.cor,
  };
}

// ---------------------------------------------------------------------------
// Historico_Local
// ---------------------------------------------------------------------------

export class HistoricoLocal {
  #db;
  #chave;
  #dispositivo;
  #login;

  constructor(db, chave, dispositivo, login) {
    this.#db = db;
    this.#chave = chave;
    this.#dispositivo = dispositivo;
    this.#login = login;
  }

  /** Login do dono deste historico. */
  get login() {
    return this.#login;
  }

  /** Identificador (UUID v4) do dispositivo guardado em `meta`. */
  get dispositivo() {
    return this.#dispositivo;
  }

  /**
   * Incorpora mensagens ao historico. Mensagens cujo `mid` ja existe (no banco
   * ou antes no mesmo lote) sao ignoradas; as novas sao recifradas com a
   * Chave_Historico e um nonce novo.
   *
   * Cada mensagem tem `{mid, remetente, ts, tipo, conteudo}`. Por padrao
   * `conteudo` e o texto em claro (como a `ConexaoWeb` entrega depois de
   * decifrar o transito). Com `{chaveTransito}`, `conteudo` e o texto cifrado
   * com a Chave_Transito, como chega no quadro; se nao decifrar, a mensagem e
   * contada em `ilegiveis` e nao e gravada. Mensagens marcadas com
   * `ilegivel: true` ou sem texto tambem nao sao gravadas.
   *
   * @param {Array<object>} mensagens
   * @param {{chaveTransito?: string|Uint8Array}} [opcoes]
   * @returns {Promise<{adicionadas: number, existentes: number, ilegiveis: number, invalidas: number}>}
   */
  async incorporar(mensagens, opcoes = {}) {
    const { chaveTransito } = opcoes;
    const resultado = { adicionadas: 0, existentes: 0, ilegiveis: 0, invalidas: 0 };
    const vistos = new Set();
    const registros = [];

    for (const m of mensagens ?? []) {
      if (m?.ilegivel === true) {
        resultado.ilegiveis++;
        continue;
      }
      if (!ehMensagemValida(m)) {
        resultado.invalidas++;
        continue;
      }
      if (vistos.has(m.mid)) {
        resultado.existentes++; // duplicata no lote: vale a primeira
        continue;
      }
      let claro = m.conteudo;
      if (chaveTransito !== undefined) {
        try {
          claro = decifrar(chaveTransito, m.conteudo);
        } catch {
          resultado.ilegiveis++;
          continue;
        }
      }
      vistos.add(m.mid);
      registros.push({
        mid: m.mid,
        remetente: copiarRemetente(m.remetente),
        ts: m.ts,
        tipo: m.tipo,
        conteudo: cifrar(this.#chave, claro),
      });
    }

    if (registros.length === 0) return resultado;

    const tx = this.#db.transaction([STORE_MENSAGENS], 'readwrite');
    const store = tx.objectStore(STORE_MENSAGENS);
    const fim = transacaoConcluida(tx);
    let existentes = 0;
    for (const registro of registros) {
      const req = store.add(registro);
      req.onerror = (evento) => {
        if (req.error?.name === 'ConstraintError') {
          // "ja existe": evita que o erro aborte a transacao inteira
          evento.preventDefault();
          evento.stopPropagation();
          existentes++;
        }
      };
    }
    await fim;
    resultado.existentes += existentes;
    resultado.adicionadas = registros.length - existentes;
    return resultado;
  }

  /**
   * Lista todas as mensagens decifradas, ordenadas por (ts, mid). Registro que
   * nao decifra vem com `conteudo: null` e `ilegivel: true`.
   * @returns {Promise<Array<{mid, remetente, ts, tipo, conteudo, ilegivel}>>}
   */
  async listar() {
    const brutos = await this.registrosCifrados();
    return brutos.map((r) => {
      let conteudo = null;
      let ilegivel = false;
      try {
        conteudo = decifrar(this.#chave, r.conteudo);
      } catch {
        ilegivel = true;
      }
      return { mid: r.mid, remetente: r.remetente, ts: r.ts, tipo: r.tipo, conteudo, ilegivel };
    });
  }

  /** Registros como gravados (conteudo cifrado), ordenados por (ts, mid). */
  async registrosCifrados() {
    const tx = this.#db.transaction([STORE_MENSAGENS], 'readonly');
    const todos = await pedido(tx.objectStore(STORE_MENSAGENS).getAll());
    return todos.sort(compararPorTsMid);
  }

  /** Quantidade de mensagens guardadas. */
  async contar() {
    const tx = this.#db.transaction([STORE_MENSAGENS], 'readonly');
    return pedido(tx.objectStore(STORE_MENSAGENS).count());
  }

  /** Fecha a conexao com o banco. O conteudo permanece guardado. */
  fechar() {
    this.#db.close();
  }
}

/**
 * Abre (criando, se preciso) o Historico_Local do usuario neste dispositivo.
 *
 * @param {string} login login da conta (`^[a-z0-9_]{3,20}$`)
 * @param {object} [opcoes]
 * @param {IDBFactory} [opcoes.indexedDB] padrao: `globalThis.indexedDB`
 * @param {string} [opcoes.dispositivo] UUID do dispositivo (padrao: o de `meta`, ou um novo)
 * @param {string} [opcoes.chaveTransito] usada so para garantir que a Chave_Historico seja diferente
 * @returns {Promise<HistoricoLocal>}
 */
export async function abrirHistorico(login, opcoes = {}) {
  const nome = nomeBanco(login);
  const idb = opcoes.indexedDB ?? globalThis.indexedDB;
  if (!idb) throw new ErroHistorico('IndexedDB indisponivel neste navegador');

  let db;
  try {
    db = await abrirBanco(idb, nome);
    db.onversionchange = () => db.close();
    const meta = await garantirMeta(db, {
      dispositivo: opcoes.dispositivo,
      chaveTransito: typeof opcoes.chaveTransito === 'string' ? opcoes.chaveTransito.toLowerCase() : undefined,
    });
    return new HistoricoLocal(db, meta.chave, meta.dispositivo, login);
  } catch (e) {
    if (db) db.close();
    if (e instanceof ErroHistorico) throw e;
    throw new ErroHistorico('Falha ao abrir o Historico_Local', e);
  }
}
