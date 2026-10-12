// ReportadorErros e Buffer_Offline_Erros do Cliente_Web.
//
// - `ReportadorErros.reportar(excecao)` e o ponto central: monta o evento,
//   mascara segredos (js/mascaramento.js), grava no Buffer_Offline_Erros e, se
//   ha conexao, envia `error_report`. Sem conexao ou com falha de envio, o
//   evento permanece no buffer.
// - Flag de reentrancia `emReporte` (contador com `try/finally`): enquanto o
//   Reportador esta enviando ou gravando, qualquer erro novo vai so para o
//   console local e nao gera evento. Excecoes internas nunca saem
//   do Reportador.
// - Buffer_Offline_Erros (IndexedDB `comunicador_global`, store
//   `erros_pendentes`, chave `evid`): no maximo 100 eventos e 256 KiB; ao
//   exceder, descarta o mais antigo (FIFO) e conta o descarte; cada evento tem
//   no maximo 5 tentativas de envio. O contador `descartados` segue no
//   proximo evento enviado e so e abatido quando esse evento e confirmado.
// - Esvaziamento: depois do login e a cada 60 s, ate 20 eventos por
//   `error_report`; so os `evid` presentes em `aceitos` saem do buffer.
//
// A logica do buffer (`BufferOfflineErros`) e independente de rede e de DOM:
// opera sobre um armazenamento com `carregar()` e `gravar(estado)`; ha uma
// implementacao em memoria (testes) e outra em IndexedDB.

import { gerarUuidV4 } from './chave.js';
import { mascarar } from './mascaramento.js';
import { MAXIMO_EVENTOS_ERRO } from './protocolo.js';

// ---------------------------------------------------------------------------
// Constantes
// ---------------------------------------------------------------------------

/** Maximo de eventos retidos no buffer. */
export const MAXIMO_EVENTOS_BUFFER = 100;

/** Maximo de bytes (JSON em UTF-8) retidos no buffer: 256 KiB. */
export const MAXIMO_BYTES_BUFFER = 256 * 1024;

/** Maximo de tentativas de envio por evento. */
export const MAXIMO_TENTATIVAS_EVENTO = 5;

/** Tamanho maximo, em bytes, de um `error_report` (o Middleware aceita 64 KiB). */
export const MAXIMO_BYTES_LOTE = 60 * 1024;

/** Intervalo do reenvio do buffer, em ms. */
export const INTERVALO_ESVAZIAMENTO_MS = 60000;

/** Limites de `descricao` e `pilha`. */
export const MAXIMO_DESCRICAO = 2000;
export const MAXIMO_PILHA = 8000;

export const NOME_BANCO_GLOBAL = 'comunicador_global';
export const STORE_ERROS = 'erros_pendentes';
export const STORE_META_ERROS = 'meta_erros';
const META_DESCARTADOS = 'descartados';
const VERSAO_BANCO = 1;

const CODIGOS_SEM_CONEXAO = new Set(['SEM_CONEXAO', 'CONEXAO_PERDIDA']);

// ---------------------------------------------------------------------------
// Funcoes puras
// ---------------------------------------------------------------------------

const codificador = new TextEncoder();

/** Tamanho, em bytes, do JSON UTF-8 de `valor`. */
export function tamanhoEmBytes(valor) {
  return codificador.encode(JSON.stringify(valor)).length;
}

/** Corta `texto` em `maximo` unidades sem deixar meio par substituto no fim. */
export function truncar(texto, maximo) {
  if (texto.length <= maximo) return texto;
  let fim = maximo;
  const ultimo = texto.charCodeAt(fim - 1);
  if (ultimo >= 0xd800 && ultimo <= 0xdbff) fim -= 1;
  return texto.slice(0, fim);
}

/** Descreve uma excecao qualquer (Error, string, objeto, valor de rejeicao) em `{tipo, descricao, pilha}`. */
export function descreverExcecao(excecao) {
  if (excecao !== null && typeof excecao === 'object') {
    const tipo = String(excecao.name ?? excecao.constructor?.name ?? 'Object') || 'Error';
    let descricao = excecao.message;
    if (typeof descricao !== 'string') {
      try {
        descricao = JSON.stringify(excecao) ?? String(excecao);
      } catch {
        descricao = String(excecao);
      }
    }
    return { tipo, descricao, pilha: typeof excecao.stack === 'string' ? excecao.stack : '' };
  }
  if (typeof excecao === 'string') return { tipo: 'Error', descricao: excecao, pilha: '' };
  return { tipo: typeof excecao, descricao: String(excecao), pilha: '' };
}

/** Nome do arquivo de uma URL ou caminho (sem consulta nem fragmento), ou `null`. */
export function nomeArquivo(origem) {
  if (typeof origem !== 'string' || origem === '') return null;
  const limpo = origem.split(/[?#]/)[0];
  const nome = limpo.slice(limpo.lastIndexOf('/') + 1);
  return nome === '' ? null : nome;
}

/** Modulo de origem: o informado, senao o primeiro arquivo `.js`/`.mjs`/`.html` da pilha, senao `desconhecido`. */
export function moduloDe(excecao, informado) {
  const dado = nomeArquivo(informado);
  if (dado !== null) return dado;
  const pilha = excecao !== null && typeof excecao === 'object' ? excecao.stack : undefined;
  if (typeof pilha === 'string') {
    const m = /([A-Za-z0-9_.-]+\.(?:m?js|html))(?:\?[^\s:)]*)?:\d+/.exec(pilha);
    if (m) return m[1];
  }
  return 'desconhecido';
}

/**
 * Monta o evento do buffer: `{evid, ts, modulo, tipo, descricao, pilha,
 * dispositivo, cliente, tentativas}`. `descricao` e `pilha` sao mascaradas e so
 * depois truncadas (truncar antes poderia deixar parte de um segredo curto
 * demais para ser reconhecido).
 */
export function montarEvento({ excecao, modulo, ts, evid, dispositivo = null, cliente = 'web' }) {
  const { tipo, descricao, pilha } = descreverExcecao(excecao);
  return {
    evid,
    ts,
    modulo: moduloDe(excecao, modulo),
    tipo: truncar(String(mascarar(tipo)), 200),
    descricao: truncar(mascarar(descricao), MAXIMO_DESCRICAO),
    pilha: truncar(mascarar(pilha), MAXIMO_PILHA),
    dispositivo,
    cliente,
    tentativas: 0,
  };
}

/** Forma enviada no `error_report`: sem `tentativas` e `seq`, com `descartados`. */
function paraEnvio(evento, descartados) {
  return {
    ts: evento.ts,
    modulo: evento.modulo,
    tipo: evento.tipo,
    descricao: evento.descricao,
    pilha: evento.pilha,
    dispositivo: evento.dispositivo,
    cliente: evento.cliente,
    evid: evento.evid,
    descartados,
  };
}

/**
 * Aplica os limites do buffer a `estado = {eventos, descartados}` e devolve o
 * novo estado: enquanto houver mais de 100 eventos ou mais de 256 KiB, descarta
 * o mais antigo e soma 1 a `descartados`.
 */
export function aplicarLimites(estado, { maxEventos = MAXIMO_EVENTOS_BUFFER, maxBytes = MAXIMO_BYTES_BUFFER } = {}) {
  const eventos = [...estado.eventos];
  const tamanhos = eventos.map(tamanhoEmBytes);
  let total = tamanhos.reduce((a, b) => a + b, 0);
  let descartados = estado.descartados;
  while (eventos.length > 0 && (eventos.length > maxEventos || total > maxBytes)) {
    eventos.shift();
    total -= tamanhos.shift();
    descartados += 1;
  }
  return { eventos, descartados };
}

/** Indica se `erro` representa falta de conexao (a tentativa nao e contada). */
export function ehFalhaDeConexao(erro) {
  return CODIGOS_SEM_CONEXAO.has(erro?.codigo);
}

// ---------------------------------------------------------------------------
// Armazenamentos
// ---------------------------------------------------------------------------

/** Armazenamento em memoria (testes e fallback). Interface: `carregar()` e `gravar(estado)`. */
export class ArmazenamentoMemoria {
  #estado = { eventos: [], descartados: 0 };

  async carregar() {
    return structuredClone(this.#estado);
  }

  async gravar(estado) {
    this.#estado = structuredClone({ eventos: estado.eventos, descartados: estado.descartados });
  }
}

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
    tx.onabort = () => reject(tx.error ?? new Error('Transacao abortada'));
  });
}

/**
 * Armazenamento no IndexedDB: banco `comunicador_global`, store
 * `erros_pendentes` (chave `evid`) e store `meta_erros` (contador `descartados`).
 * `gravar` substitui o conteudo em uma unica transacao (atomica).
 */
export class ArmazenamentoIndexedDB {
  #idb;
  #nome;
  #db = null;

  /**
   * @param {object} [opcoes]
   * @param {IDBFactory} [opcoes.indexedDB] padrao: `globalThis.indexedDB`
   * @param {string} [opcoes.nome] padrao: `comunicador_global`
   */
  constructor({ indexedDB = globalThis.indexedDB, nome = NOME_BANCO_GLOBAL } = {}) {
    if (!indexedDB) throw new Error('IndexedDB indisponivel neste navegador');
    this.#idb = indexedDB;
    this.#nome = nome;
  }

  #abrir() {
    if (this.#db) return Promise.resolve(this.#db);
    return new Promise((resolve, reject) => {
      const req = this.#idb.open(this.#nome, VERSAO_BANCO);
      req.onupgradeneeded = () => {
        const db = req.result;
        if (!db.objectStoreNames.contains(STORE_ERROS)) db.createObjectStore(STORE_ERROS, { keyPath: 'evid' });
        if (!db.objectStoreNames.contains(STORE_META_ERROS)) db.createObjectStore(STORE_META_ERROS);
      };
      req.onsuccess = () => {
        this.#db = req.result;
        this.#db.onversionchange = () => {
          this.#db?.close();
          this.#db = null;
        };
        resolve(this.#db);
      };
      req.onerror = () => reject(req.error);
      req.onblocked = () => reject(new Error('Abertura do Buffer_Offline_Erros bloqueada'));
    });
  }

  async carregar() {
    const db = await this.#abrir();
    const tx = db.transaction([STORE_ERROS, STORE_META_ERROS], 'readonly');
    const pEventos = pedido(tx.objectStore(STORE_ERROS).getAll());
    const pContador = pedido(tx.objectStore(STORE_META_ERROS).get(META_DESCARTADOS));
    const [eventos, contador] = await Promise.all([pEventos, pContador]);
    eventos.sort((a, b) => (a.seq ?? 0) - (b.seq ?? 0));
    return { eventos, descartados: Number.isInteger(contador) && contador > 0 ? contador : 0 };
  }

  async gravar(estado) {
    const db = await this.#abrir();
    const tx = db.transaction([STORE_ERROS, STORE_META_ERROS], 'readwrite');
    const erros = tx.objectStore(STORE_ERROS);
    erros.clear();
    for (const evento of estado.eventos) erros.put(evento);
    tx.objectStore(STORE_META_ERROS).put(estado.descartados, META_DESCARTADOS);
    await transacaoConcluida(tx);
  }

  /** Fecha o banco (usado por testes). */
  fechar() {
    this.#db?.close();
    this.#db = null;
  }
}

// ---------------------------------------------------------------------------
// Buffer_Offline_Erros
// ---------------------------------------------------------------------------

/**
 * Buffer_Offline_Erros. Todas as operacoes sao serializadas (uma de cada vez),
 * porque cada uma le o estado, o altera e o grava de volta.
 */
export class BufferOfflineErros {
  #armazenamento;
  #maxEventos;
  #maxBytes;
  #maxTentativas;
  #fila = Promise.resolve();

  /**
   * @param {{carregar: () => Promise<object>, gravar: (estado: object) => Promise<void>}} armazenamento
   * @param {object} [limites] sobrescreve os limites padrao (testes)
   */
  constructor(armazenamento, { maxEventos = MAXIMO_EVENTOS_BUFFER, maxBytes = MAXIMO_BYTES_BUFFER, maxTentativas = MAXIMO_TENTATIVAS_EVENTO } = {}) {
    this.#armazenamento = armazenamento;
    this.#maxEventos = maxEventos;
    this.#maxBytes = maxBytes;
    this.#maxTentativas = maxTentativas;
  }

  #serializar(operacao) {
    const resultado = this.#fila.then(operacao);
    this.#fila = resultado.then(
      () => undefined,
      () => undefined,
    );
    return resultado;
  }

  /** Eventos retidos, do mais antigo ao mais novo (copias). */
  listar() {
    return this.#serializar(async () => (await this.#armazenamento.carregar()).eventos);
  }

  /** Quantidade de eventos retidos. */
  tamanho() {
    return this.#serializar(async () => (await this.#armazenamento.carregar()).eventos.length);
  }

  /** Descartes ainda nao informados ao Middleware. */
  descartados() {
    return this.#serializar(async () => (await this.#armazenamento.carregar()).descartados);
  }

  /**
   * Retem `evento` (com `evid`). Ignora um `evid` ja presente. Se estourar os
   * limites, descarta os mais antigos. Devolve `{retido, descartadosAgora}`.
   */
  adicionar(evento) {
    return this.#serializar(async () => {
      const estado = await this.#armazenamento.carregar();
      if (estado.eventos.some((e) => e.evid === evento.evid)) return { retido: true, descartadosAgora: 0 };
      const ultimo = estado.eventos.length > 0 ? estado.eventos[estado.eventos.length - 1].seq ?? 0 : 0;
      const novo = { ...evento, tentativas: evento.tentativas ?? 0, seq: ultimo + 1 };
      const limitado = aplicarLimites(
        { eventos: [...estado.eventos, novo], descartados: estado.descartados },
        { maxEventos: this.#maxEventos, maxBytes: this.#maxBytes },
      );
      await this.#armazenamento.gravar(limitado);
      return {
        retido: limitado.eventos.some((e) => e.evid === novo.evid),
        descartadosAgora: limitado.descartados - estado.descartados,
      };
    });
  }

  /**
   * Seleciona o proximo lote para `error_report`: do mais antigo ao mais novo,
   * ate 20 eventos e `maxBytes` (ao menos um). `apenas` restringe a esses `evid`.
   * O primeiro evento leva o contador `descartados`; os demais, zero.
   * Devolve `null` se nao houver o que enviar; senao
   * `{eventos, evids, descartadosEnviados, evidComContador}`.
   */
  prepararLote({ apenas = null, maxEventos = MAXIMO_EVENTOS_ERRO, maxBytes = MAXIMO_BYTES_LOTE } = {}) {
    return this.#serializar(async () => {
      const estado = await this.#armazenamento.carregar();
      const candidatos = apenas === null ? estado.eventos : estado.eventos.filter((e) => apenas.includes(e.evid));
      const eventos = [];
      let total = 0;
      for (const candidato of candidatos) {
        if (eventos.length >= maxEventos) break;
        const forma = paraEnvio(candidato, eventos.length === 0 ? estado.descartados : 0);
        const tamanho = tamanhoEmBytes(forma);
        if (eventos.length > 0 && total + tamanho > maxBytes) break;
        eventos.push(forma);
        total += tamanho;
      }
      if (eventos.length === 0) return null;
      return {
        eventos,
        evids: eventos.map((e) => e.evid),
        descartadosEnviados: eventos[0].descartados,
        evidComContador: eventos[0].evid,
      };
    });
  }

  /**
   * Registra o resultado do envio de `lote`.
   * - Remove SOMENTE os eventos do lote cujo `evid` consta de `aceitos`; se o
   *   evento que levava o contador foi confirmado, abate `descartadosEnviados`.
   * - Se `contarTentativa`, os demais eventos do lote ganham uma tentativa e,
   *   ao chegar ao maximo, sao descartados e contados.
   * Devolve `{removidos, descartadosAgora, restantes}`.
   */
  resolverLote(lote, { aceitos = [], contarTentativa = true } = {}) {
    return this.#serializar(async () => {
      const estado = await this.#armazenamento.carregar();
      const confirmados = new Set(Array.isArray(aceitos) ? aceitos.filter((a) => typeof a === 'string') : []);
      const doLote = new Set(lote.evids);
      let descartados = estado.descartados;
      let removidos = 0;
      let descartadosAgora = 0;
      const eventos = [];
      for (const evento of estado.eventos) {
        if (!doLote.has(evento.evid)) {
          eventos.push(evento);
          continue;
        }
        if (confirmados.has(evento.evid)) {
          removidos += 1;
          if (evento.evid === lote.evidComContador) {
            descartados = Math.max(0, descartados - lote.descartadosEnviados);
          }
          continue;
        }
        if (!contarTentativa) {
          eventos.push(evento);
          continue;
        }
        const tentativas = (evento.tentativas ?? 0) + 1;
        if (tentativas >= this.#maxTentativas) {
          descartados += 1;
          descartadosAgora += 1;
          continue;
        }
        eventos.push({ ...evento, tentativas });
      }
      await this.#armazenamento.gravar({ eventos, descartados });
      return { removidos, descartadosAgora, restantes: eventos.length };
    });
  }
}

// ---------------------------------------------------------------------------
// ReportadorErros
// ---------------------------------------------------------------------------

/**
 * Ponto central de reporte de excecoes do Cliente_Web.
 *
 * Nunca lanca nem rejeita: falhas internas vao para o console local e nao
 * geram novo evento.
 */
export class ReportadorErros {
  #buffer;
  #conexao;
  #dispositivo;
  #cliente;
  #relogio;
  #gerarId;
  #console;
  #agendarPeriodico;
  #cancelarPeriodico;
  #intervaloMs;

  #profundidade = 0;
  #envio = Promise.resolve();
  #timer = null;

  /**
   * @param {object} opcoes
   * @param {BufferOfflineErros} opcoes.buffer
   * @param {{aberta: boolean, enviarErrorReport: (eventos: object[]) => Promise<string[]>}} [opcoes.conexao]
   *   uma `ConexaoWeb` (pode ser definida depois, com `definirConexao`)
   * @param {string|null|(() => string|null)} [opcoes.dispositivo] UUID do dispositivo
   * @param {string} [opcoes.cliente] padrao: `web`
   * @param {() => Date} [opcoes.relogio]
   * @param {() => string} [opcoes.gerarId] gera o `evid`
   * @param {{error: Function}} [opcoes.console]
   * @param {(fn: Function, ms: number) => any} [opcoes.agendarPeriodico] padrao: setInterval
   * @param {(timer: any) => void} [opcoes.cancelarPeriodico] padrao: clearInterval
   * @param {number} [opcoes.intervaloMs] padrao: 60 s
   */
  constructor({
    buffer,
    conexao = null,
    dispositivo = null,
    cliente = 'web',
    relogio = () => new Date(),
    gerarId = () => gerarUuidV4().replaceAll('-', ''),
    console: consoleLocal = globalThis.console,
    agendarPeriodico = (fn, ms) => setInterval(fn, ms),
    cancelarPeriodico = (t) => clearInterval(t),
    intervaloMs = INTERVALO_ESVAZIAMENTO_MS,
  }) {
    this.#buffer = buffer;
    this.#conexao = conexao;
    this.#dispositivo = dispositivo;
    this.#cliente = cliente;
    this.#relogio = relogio;
    this.#gerarId = gerarId;
    this.#console = consoleLocal;
    this.#agendarPeriodico = agendarPeriodico;
    this.#cancelarPeriodico = cancelarPeriodico;
    this.#intervaloMs = intervaloMs;
  }

  /** `true` enquanto o Reportador esta reportando, enviando ou gravando. */
  get emReporte() {
    return this.#profundidade > 0;
  }

  /** Define (ou troca) a conexao usada para enviar. */
  definirConexao(conexao) {
    this.#conexao = conexao;
  }

  /**
   * Reporta uma excecao. Se chamado durante um reporte (`emReporte`), o erro vai
   * so para o console e nenhum evento e criado. Devolve o evento criado ou `null`.
   *
   * @param {*} excecao Error, string ou qualquer valor lancado/rejeitado
   * @param {{modulo?: string}} [opcoes] arquivo de origem, quando conhecido
   * @returns {Promise<object|null>}
   */
  async reportar(excecao, { modulo } = {}) {
    if (this.emReporte) {
      this.#registrarLocal('Erro durante o reporte de erros (nao reportado)', excecao);
      return null;
    }
    this.#profundidade += 1;
    try {
      const evento = montarEvento({
        excecao,
        modulo,
        ts: this.#relogio().toISOString(),
        evid: this.#gerarId(),
        dispositivo: this.#dispositivoAtual(),
        cliente: this.#cliente,
      });
      const { retido } = await this.#buffer.adicionar(evento);
      if (retido && this.#conectado()) {
        await this.#enviarLote({ apenas: [evento.evid] });
      }
      return evento;
    } catch (e) {
      this.#registrarLocal('Falha interna do ReportadorErros', e);
      return null;
    } finally {
      this.#profundidade -= 1;
    }
  }

  /**
   * Envia o que estiver retido, em lotes de ate 20 eventos, enquanto houver
   * conexao e os lotes forem integralmente confirmados. Devolve a quantidade
   * de eventos confirmados.
   */
  async esvaziar() {
    this.#profundidade += 1;
    try {
      let confirmados = 0;
      for (let i = 0; i < Math.ceil(MAXIMO_EVENTOS_BUFFER / MAXIMO_EVENTOS_ERRO) + 1; i += 1) {
        if (!this.#conectado()) break;
        const resultado = await this.#enviarLote({});
        if (resultado === null) break;
        confirmados += resultado.removidos;
        if (resultado.restantesNoLote > 0) break;
      }
      return confirmados;
    } catch (e) {
      this.#registrarLocal('Falha ao esvaziar o Buffer_Offline_Erros', e);
      return 0;
    } finally {
      this.#profundidade -= 1;
    }
  }

  /** Chamar depois do login (e do relogin automatico): esvazia o buffer. */
  aposLogin() {
    return this.esvaziar();
  }

  /** Inicia o esvaziamento periodico (a cada 60 s). Idempotente. */
  iniciar() {
    if (this.#timer !== null) return;
    this.#timer = this.#agendarPeriodico(() => {
      this.esvaziar();
    }, this.#intervaloMs);
  }

  /** Para o esvaziamento periodico. */
  parar() {
    if (this.#timer === null) return;
    this.#cancelarPeriodico(this.#timer);
    this.#timer = null;
  }

  // ----- Internos ----------------------------------------------------------

  /**
   * Envia um lote e registra o resultado no buffer. Os envios sao serializados
   * para que o mesmo evento nao saia em dois `error_report` ao mesmo tempo.
   * Devolve `null` se nao havia lote; senao `{removidos, restantesNoLote}`.
   */
  #enviarLote(opcoes) {
    const execucao = this.#envio.then(async () => {
      if (!this.#conectado()) return null;
      const lote = await this.#buffer.prepararLote(opcoes);
      if (lote === null) return null;
      let aceitos = [];
      let contarTentativa = true;
      try {
        const resposta = await this.#conexao.enviarErrorReport(lote.eventos);
        aceitos = Array.isArray(resposta) ? resposta : [];
      } catch (e) {
        contarTentativa = !ehFalhaDeConexao(e);
        this.#registrarLocal('Falha ao enviar error_report', e);
      }
      const { removidos } = await this.#buffer.resolverLote(lote, { aceitos, contarTentativa });
      return { removidos, restantesNoLote: lote.evids.length - removidos };
    });
    this.#envio = execucao.then(
      () => undefined,
      () => undefined,
    );
    return execucao;
  }

  #conectado() {
    return Boolean(this.#conexao && this.#conexao.aberta);
  }

  #dispositivoAtual() {
    try {
      const valor = typeof this.#dispositivo === 'function' ? this.#dispositivo() : this.#dispositivo;
      return typeof valor === 'string' && valor !== '' ? valor : null;
    } catch {
      return null;
    }
  }

  #registrarLocal(mensagem, erro) {
    try {
      this.#console?.error?.(`[ReportadorErros] ${mensagem}`, erro);
    } catch {
      // Nem o console pode gerar novo evento.
    }
  }
}

// ---------------------------------------------------------------------------
// Captura global
// ---------------------------------------------------------------------------

/**
 * Liga `window.onerror` e `unhandledrejection` ao `reportador`. Preserva um
 * `onerror` anterior (chamado primeiro) e devolve uma funcao que desfaz a ligacao.
 *
 * @param {ReportadorErros} reportador
 * @param {EventTarget & {onerror?: Function}} [alvo] padrao: `globalThis`
 * @returns {() => void}
 */
export function instalarCapturaGlobal(reportador, alvo = globalThis) {
  const anterior = alvo.onerror ?? null;

  const aoErro = function (mensagem, origem, _linha, _coluna, erro) {
    try {
      const excecao = erro ?? new Error(typeof mensagem === 'string' ? mensagem : 'Erro nao tratado');
      reportador.reportar(excecao, { modulo: origem });
    } catch {
      // nunca propaga
    }
    try {
      return typeof anterior === 'function' ? anterior.apply(this, arguments) : false;
    } catch {
      return false;
    }
  };
  const aoRejeitar = (evento) => {
    try {
      reportador.reportar(evento?.reason);
    } catch {
      // nunca propaga
    }
  };

  alvo.onerror = aoErro;
  alvo.addEventListener('unhandledrejection', aoRejeitar);

  return () => {
    if (alvo.onerror === aoErro) alvo.onerror = anterior;
    alvo.removeEventListener('unhandledrejection', aoRejeitar);
  };
}

/**
 * Cria o buffer persistente (IndexedDB) e o Reportador, sem ligar captura nem
 * timer. Se o IndexedDB nao existir, usa o armazenamento em memoria.
 */
export function criarReportadorErros(opcoes = {}) {
  let armazenamento;
  try {
    armazenamento = opcoes.armazenamento ?? new ArmazenamentoIndexedDB({ indexedDB: opcoes.indexedDB ?? globalThis.indexedDB });
  } catch {
    armazenamento = new ArmazenamentoMemoria();
  }
  const buffer = new BufferOfflineErros(armazenamento);
  const { armazenamento: _a, indexedDB: _i, ...resto } = opcoes;
  return new ReportadorErros({ buffer, ...resto });
}
