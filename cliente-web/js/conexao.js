// Conexao WebSocket do Cliente_Web com o Middleware (porta 9001).
//
// - Um frame de texto WebSocket = um objeto JSON do Protocolo (js/protocolo.js).
// - `dados` (register/login) e `conteudo` (send_message) sao cifrados com a
//   Chave_Transito pela Cifra_Manual (js/cifra.js); mensagens e historico
//   recebidos sao decifrados aqui antes de chegar a interface.
// - `ping` a cada 30 s enquanto a conexao esta aberta.
// - Reconexao automatica com atraso exponencial limitado e jitter de +-20%,
//   ate 10 tentativas consecutivas; depois, estado "desconectado" e
//   `tentarNovamente()`. Para voltar a sala, login e senha ficam apenas na
//   memoria do processo (nunca em localStorage, IndexedDB ou disco) e sao
//   apagados no logout.
//
// A parte pura (calculo do atraso) e exportada e nao depende de rede nem de DOM.

import { cifrar, decifrar, DecifragemFalhou } from './cifra.js';
import {
  Categoria,
  ErroProtocolo,
  TipoCliente,
  interpretarQuadroServidor,
  montarErrorReport,
  montarHistory,
  montarLogin,
  montarLogout,
  montarPing,
  montarRegister,
  montarSendMessage,
  novoId,
  serializar,
} from './protocolo.js';

// ---------------------------------------------------------------------------
// Politica de reconexao (pura)
// ---------------------------------------------------------------------------

/** Porta do listener WebSocket do Middleware. */
export const PORTA_WEBSOCKET = 9001;

/** Intervalo entre pings (keep-alive), em ms. */
export const INTERVALO_PING_MS = 30000;

/** Atraso base da primeira tentativa de reconexao, em ms. */
export const ATRASO_BASE_MS = 1000;

/** Atraso maximo entre tentativas, em ms. */
export const ATRASO_MAXIMO_MS = 30000;

/** Maximo de tentativas consecutivas de reconexao. */
export const MAXIMO_TENTATIVAS = 10;

/** Fracao de jitter aplicada ao atraso (+-20%). */
export const FRACAO_JITTER = 0.2;

/** Tempo maximo de espera por uma resposta do Middleware, em ms. */
export const TEMPO_LIMITE_REQUISICAO_MS = 15000;

/**
 * Atraso base, sem jitter, antes da tentativa `n` de reconexao (n = 0, 1, 2, ...):
 * `min(1000 * 2^n, 30000)` ms. Funcao pura.
 *
 * @param {number} n inteiro maior ou igual a zero
 * @returns {number} milissegundos
 */
export function atrasoReconexao(n) {
  if (!Number.isInteger(n) || n < 0) {
    throw new RangeError('A tentativa deve ser um inteiro maior ou igual a zero');
  }
  return Math.min(ATRASO_BASE_MS * 2 ** n, ATRASO_MAXIMO_MS);
}

/**
 * Atraso da tentativa `n` com jitter de +-20%: valor em [0,8 x base, 1,2 x base].
 * `aleatorio` devolve um numero em [0, 1] (padrao: Math.random); fica injetavel
 * para testes. Funcao pura quando `aleatorio` e determinista.
 *
 * @param {number} n
 * @param {() => number} [aleatorio]
 * @returns {number} milissegundos
 */
export function atrasoComJitter(n, aleatorio = Math.random) {
  const base = atrasoReconexao(n);
  const r = Math.min(1, Math.max(0, aleatorio()));
  // Percentuais inteiros (80 a 120) evitam erro de ponto flutuante nos extremos.
  const percentual = 100 * (1 - FRACAO_JITTER) + 100 * 2 * FRACAO_JITTER * r;
  return (base * percentual) / 100;
}

/**
 * Indica se ainda cabe uma tentativa de reconexao depois de `feitas` tentativas
 * consecutivas ja realizadas. Funcao pura.
 *
 * @param {number} feitas
 * @returns {boolean}
 */
export function podeTentarNovamente(feitas) {
  return Number.isInteger(feitas) && feitas >= 0 && feitas < MAXIMO_TENTATIVAS;
}

/**
 * Monta a URL do WebSocket a partir da localizacao da pagina:
 * `ws://<hostname>:9001` (`wss://` quando a pagina e servida por HTTPS).
 *
 * @param {{hostname: string, protocol?: string}} [localizacao]
 * @returns {string}
 */
export function urlWebSocket(localizacao = globalThis.location) {
  const protocolo = localizacao?.protocol === 'https:' ? 'wss' : 'ws';
  const host = localizacao?.hostname || 'localhost';
  const hostUrl = host.includes(':') && !host.startsWith('[') ? `[${host}]` : host; // IPv6
  return `${protocolo}://${hostUrl}:${PORTA_WEBSOCKET}`;
}

// ---------------------------------------------------------------------------
// Estados e erros
// ---------------------------------------------------------------------------

/** Estados da conexao informados a interface. */
export const EstadoConexao = Object.freeze({
  /** Sem conexao (inicial, apos logout ou apos esgotar as tentativas). */
  DESCONECTADO: 'desconectado',
  /** Abrindo o WebSocket pela primeira vez. */
  CONECTANDO: 'conectando',
  /** WebSocket aberto, sem Sessao autenticada. */
  CONECTADO: 'conectado',
  /** Login confirmado pelo Middleware. */
  AUTENTICADO: 'autenticado',
  /** Conexao perdida; tentando de novo com backoff. */
  RECONECTANDO: 'reconectando',
});

/** Erro de uma requisicao: resposta `ok:false`, falha de rede ou tempo esgotado. */
export class ErroRequisicao extends Error {
  /**
   * @param {string} codigo codigo do Protocolo ou `CONEXAO_PERDIDA`, `TEMPO_ESGOTADO`, `SEM_CONEXAO`
   * @param {string} mensagem
   * @param {string|null} [campo]
   */
  constructor(codigo, mensagem, campo = null) {
    super(mensagem);
    this.name = 'ErroRequisicao';
    this.codigo = codigo;
    this.campo = campo;
  }
}

const WS_ABERTO = 1;

// ---------------------------------------------------------------------------
// Conexao
// ---------------------------------------------------------------------------

/**
 * Conexao WebSocket com o Middleware.
 *
 * Callbacks (todos opcionais):
 *  - `aoEstado(estado, detalhe)`: mudancas de EstadoConexao; `detalhe` traz
 *    `{tentativa, maximo, atrasoMs}` em "reconectando" e `{motivo}` em "desconectado";
 *  - `aoMensagem(mensagem)`: evento `message`, ja decifrado (ver `decifrarMensagem`);
 *  - `aoHistorico({lote, ultimo, mensagens})`: lote de `history`, mensagens ja decifradas;
 *  - `aoReautenticado(perfil)`: relogin automatico bem-sucedido apos reconexao;
 *  - `aoErro(erro, contexto)`: falhas que nao pertencem a nenhuma requisicao
 *    (quadro invalido, mensagem que nao decifra, erro de rede). Nunca recebe conteudo legivel.
 */
export class ConexaoWeb {
  #chave;
  #url;
  #dispositivo;
  #cliente;
  #criarWebSocket;
  #agendar;
  #cancelar;
  #aleatorio;
  #tempoLimite;
  #cb;

  #ws = null;
  #estado = EstadoConexao.DESCONECTADO;
  #pendentes = new Map();
  #aguardandoAbertura = null;
  #timerPing = null;
  #timerReconexao = null;
  #tentativas = 0;
  #encerradoPeloUsuario = false;
  /** Credenciais somente em memoria; apagadas no logout. */
  #credenciais = null;

  /**
   * @param {object} opcoes
   * @param {string|Uint8Array} opcoes.chaveTransito 64 digitos hexadecimais ou 32 bytes
   * @param {string} opcoes.dispositivo UUID do dispositivo (ver chave.js)
   * @param {string} [opcoes.url] padrao: `urlWebSocket()`
   * @param {{tipo: string, versao: string}} [opcoes.cliente]
   * @param {(url: string) => object} [opcoes.criarWebSocket] fabrica de WebSocket (padrao: `new WebSocket(url)`)
   * @param {(fn: Function, ms: number) => any} [opcoes.agendar] padrao: setTimeout
   * @param {(timer: any) => void} [opcoes.cancelar] padrao: clearTimeout
   * @param {() => number} [opcoes.aleatorio] padrao: Math.random
   * @param {number} [opcoes.tempoLimiteMs] tempo maximo de espera por resposta
   */
  constructor({
    chaveTransito,
    dispositivo,
    url,
    cliente = { tipo: TipoCliente.WEB, versao: '1.0' },
    criarWebSocket = (u) => new WebSocket(u),
    agendar = (fn, ms) => setTimeout(fn, ms),
    cancelar = (t) => clearTimeout(t),
    aleatorio = Math.random,
    tempoLimiteMs = TEMPO_LIMITE_REQUISICAO_MS,
    aoEstado,
    aoMensagem,
    aoHistorico,
    aoReautenticado,
    aoErro,
  }) {
    if (!chaveTransito) throw new ErroRequisicao('SEM_CHAVE', 'Chave_Transito ausente');
    this.#chave = chaveTransito;
    this.#dispositivo = dispositivo;
    this.#url = url ?? urlWebSocket();
    this.#cliente = cliente;
    this.#criarWebSocket = criarWebSocket;
    this.#agendar = agendar;
    this.#cancelar = cancelar;
    this.#aleatorio = aleatorio;
    this.#tempoLimite = tempoLimiteMs;
    this.#cb = { aoEstado, aoMensagem, aoHistorico, aoReautenticado, aoErro };
  }

  /** Estado atual (valor de EstadoConexao). */
  get estado() {
    return this.#estado;
  }

  /** Tentativas consecutivas de reconexao ja realizadas. */
  get tentativas() {
    return this.#tentativas;
  }

  // ----- API publica -------------------------------------------------------

  /** Abre o WebSocket. Resolve quando aberto; rejeita se a abertura falhar. */
  conectar() {
    this.#encerradoPeloUsuario = false;
    if (this.#ws && this.#ws.readyState === WS_ABERTO) return Promise.resolve();
    if (this.#aguardandoAbertura) return this.#aguardandoAbertura.promessa;
    this.#definirEstado(EstadoConexao.CONECTANDO);
    return this.#abrir();
  }

  /**
   * Cadastro. Cifra `dados` com a Chave_Transito. Abre a conexao se preciso.
   * @param {{login: string, apelido: string, senha: string, avatar: string, cor: string}} campos
   * @returns {Promise<object>} resposta `ok:true` (`{login}`)
   */
  async registrar(campos) {
    await this.#garantirConexao();
    const id = novoId('reg');
    const quadro = montarRegister(id, cifrar(this.#chave, JSON.stringify(campos)));
    return this.#requisitar(id, quadro);
  }

  /**
   * Login. Cifra `dados` com a Chave_Transito. Em caso de sucesso, guarda
   * login e senha somente em memoria para o relogin automatico.
   * @returns {Promise<{sessao: string, perfil: object}>}
   */
  async login(login, senha) {
    await this.#garantirConexao();
    const resposta = await this.#enviarLogin(login, senha);
    this.#credenciais = { login, senha };
    this.#tentativas = 0;
    this.#definirEstado(EstadoConexao.AUTENTICADO);
    return { sessao: resposta.sessao, perfil: resposta.perfil };
  }

  /**
   * Envia `send_message`, cifrando `conteudo` (texto ou id de figurinha).
   * @param {'texto'|'figurinha'} tipo
   * @param {string} conteudo
   * @returns {Promise<{mid: string, ts: string}>}
   */
  enviarMensagem(tipo, conteudo) {
    const id = novoId('msg');
    const quadro = montarSendMessage(id, tipo, cifrar(this.#chave, conteudo));
    return this.#requisitar(id, quadro);
  }

  /**
   * Pede o historico (`history`). Os lotes chegam por `aoHistorico`; a promessa
   * resolve ao receber o ultimo lote.
   * @param {string|Date} [desde]
   */
  solicitarHistorico(desde) {
    const id = novoId('his');
    return this.#requisitar(id, montarHistory(id, desde), { multiplo: true });
  }

  /**
   * Envia `error_report` (sem exigir login). Resolve com a lista `aceitos`.
   * @param {object[]} eventos ate 20 eventos ja mascarados
   * @returns {Promise<string[]>}
   */
  async enviarErrorReport(eventos) {
    const id = novoId('err');
    const quadro = montarErrorReport(id, eventos);
    const resposta = await this.#requisitar(id, quadro);
    return resposta.aceitos;
  }

  /** `true` se ha WebSocket aberto. */
  get aberta() {
    return this.#ws !== null && this.#ws.readyState === WS_ABERTO;
  }

  /** Logout: avisa o Middleware, apaga as credenciais da memoria e nao reconecta. */
  async logout() {
    const podeAvisar = this.aberta;
    let falha = null;
    if (podeAvisar) {
      try {
        const id = novoId('out');
        await this.#requisitar(id, montarLogout(id));
      } catch (e) {
        falha = e;
      }
    }
    this.fechar();
    if (falha && falha.codigo !== 'CONEXAO_PERDIDA') throw falha;
  }

  /** Fecha a conexao sem reconectar e apaga as credenciais da memoria. */
  fechar() {
    this.#encerradoPeloUsuario = true;
    this.#credenciais = null;
    this.#tentativas = 0;
    this.#cancelarReconexao();
    const ws = this.#ws;
    this.#ws = null;
    this.#pararPing();
    if (ws) this.#fecharSilencioso(ws);
    this.#rejeitarAbertura(new ErroRequisicao('CONEXAO_PERDIDA', 'Conexao encerrada'));
    this.#rejeitarPendentes(new ErroRequisicao('CONEXAO_PERDIDA', 'Conexao encerrada'));
    this.#definirEstado(EstadoConexao.DESCONECTADO, { motivo: 'encerrado' });
  }

  /**
   * Depois de esgotar as tentativas (estado "desconectado" com credenciais em
   * memoria), zera o contador e tenta reconectar de novo.
   */
  tentarNovamente() {
    if (!this.#credenciais) return;
    this.#encerradoPeloUsuario = false;
    this.#tentativas = 0;
    this.#cancelarReconexao();
    this.#agendarReconexao();
  }

  // ----- Abertura ----------------------------------------------------------

  #garantirConexao() {
    if (this.aberta) return Promise.resolve();
    return this.conectar();
  }

  #abrir() {
    let resolver;
    let rejeitar;
    const promessa = new Promise((res, rej) => {
      resolver = res;
      rejeitar = rej;
    });
    this.#aguardandoAbertura = { promessa, resolver, rejeitar };

    let ws;
    try {
      ws = this.#criarWebSocket(this.#url);
    } catch (e) {
      this.#aguardandoAbertura = null;
      this.#definirEstado(EstadoConexao.DESCONECTADO, { motivo: 'falha_ao_abrir' });
      rejeitar(new ErroRequisicao('SEM_CONEXAO', 'Nao foi possivel abrir a conexao'));
      this.#avisarErro(e, 'abrir');
      return promessa;
    }
    this.#ws = ws;

    ws.onopen = () => {
      if (this.#ws !== ws) return;
      const espera = this.#aguardandoAbertura;
      this.#aguardandoAbertura = null;
      if (!this.#credenciais) this.#tentativas = 0;
      this.#iniciarPing();
      if (this.#estado !== EstadoConexao.RECONECTANDO) this.#definirEstado(EstadoConexao.CONECTADO);
      espera?.resolver();
    };
    ws.onmessage = (evento) => {
      if (this.#ws !== ws) return;
      this.#aoReceber(evento.data);
    };
    ws.onerror = () => {
      // O navegador nao expoe detalhes; o encerramento chega em onclose.
    };
    ws.onclose = () => {
      if (this.#ws !== ws) return;
      this.#aoFechar();
    };
    return promessa;
  }

  #aoFechar() {
    this.#ws = null;
    this.#pararPing();
    const erro = new ErroRequisicao('CONEXAO_PERDIDA', 'Conexao perdida');
    this.#rejeitarAbertura(erro);
    this.#rejeitarPendentes(erro);
    if (this.#encerradoPeloUsuario) return;

    if (this.#credenciais) {
      this.#agendarReconexao();
    } else {
      this.#definirEstado(EstadoConexao.DESCONECTADO, { motivo: 'conexao_perdida' });
    }
  }

  // ----- Reconexao ---------------------------------------------------------

  #agendarReconexao() {
    if (!podeTentarNovamente(this.#tentativas)) {
      this.#definirEstado(EstadoConexao.DESCONECTADO, { motivo: 'tentativas_esgotadas' });
      return;
    }
    const n = this.#tentativas;
    const atrasoMs = atrasoComJitter(n, this.#aleatorio);
    this.#tentativas = n + 1;
    this.#definirEstado(EstadoConexao.RECONECTANDO, {
      tentativa: n + 1,
      maximo: MAXIMO_TENTATIVAS,
      atrasoMs,
    });
    this.#timerReconexao = this.#agendar(() => {
      this.#timerReconexao = null;
      this.#reconectar();
    }, atrasoMs);
  }

  #cancelarReconexao() {
    if (this.#timerReconexao !== null) {
      this.#cancelar(this.#timerReconexao);
      this.#timerReconexao = null;
    }
  }

  async #reconectar() {
    if (this.#encerradoPeloUsuario || !this.#credenciais) return;
    try {
      await this.#abrir();
    } catch {
      // onclose ja agendou a proxima tentativa; nada a fazer aqui.
      return;
    }
    const { login, senha } = this.#credenciais ?? {};
    if (login === undefined) return;
    try {
      const resposta = await this.#enviarLogin(login, senha);
      this.#tentativas = 0;
      this.#definirEstado(EstadoConexao.AUTENTICADO);
      this.#cb.aoReautenticado?.(resposta.perfil);
    } catch (e) {
      if (e instanceof ErroRequisicao && e.codigo === 'CREDENCIAIS_INVALIDAS') {
        // Senha ou conta mudou: reconectar nao adianta.
        this.#credenciais = null;
        const ws = this.#ws;
        this.#encerradoPeloUsuario = true;
        this.#ws = null;
        this.#pararPing();
        if (ws) this.#fecharSilencioso(ws);
        this.#definirEstado(EstadoConexao.DESCONECTADO, { motivo: 'credenciais_invalidas' });
        return;
      }
      if (e instanceof ErroRequisicao && e.codigo === 'CONEXAO_PERDIDA') return; // onclose cuida
      // Outro erro (por exemplo SERVICO_INDISPONIVEL): derruba o socket e segue o backoff.
      this.#avisarErro(e, 'relogin');
      const ws = this.#ws;
      if (ws) this.#fecharSilencioso(ws);
      if (this.#ws === ws) this.#aoFechar();
    }
  }

  // ----- Requisicoes -------------------------------------------------------

  #enviarLogin(login, senha) {
    const id = novoId('log');
    const dados = cifrar(this.#chave, JSON.stringify({ login, senha }));
    return this.#requisitar(id, montarLogin(id, dados, this.#dispositivo, this.#cliente));
  }

  #requisitar(id, quadro, { multiplo = false } = {}) {
    return new Promise((resolver, rejeitar) => {
      if (!this.aberta) {
        rejeitar(new ErroRequisicao('SEM_CONEXAO', 'Sem conexao com o servidor'));
        return;
      }
      const entrada = { resolver, rejeitar, multiplo, timer: null };
      entrada.timer = this.#agendar(() => {
        if (this.#pendentes.delete(id)) {
          rejeitar(new ErroRequisicao('TEMPO_ESGOTADO', 'O servidor nao respondeu a tempo'));
        }
      }, this.#tempoLimite);
      this.#pendentes.set(id, entrada);
      try {
        this.#ws.send(serializar(quadro));
      } catch (e) {
        this.#pendentes.delete(id);
        this.#cancelar(entrada.timer);
        rejeitar(new ErroRequisicao('SEM_CONEXAO', 'Falha ao enviar'));
        this.#avisarErro(e, 'enviar');
      }
    });
  }

  #concluir(id, ok, valor) {
    const entrada = this.#pendentes.get(id);
    if (!entrada) return false;
    this.#pendentes.delete(id);
    this.#cancelar(entrada.timer);
    if (ok) entrada.resolver(valor);
    else entrada.rejeitar(valor);
    return true;
  }

  #rejeitarPendentes(erro) {
    for (const [id] of [...this.#pendentes]) this.#concluir(id, false, erro);
  }

  #rejeitarAbertura(erro) {
    const espera = this.#aguardandoAbertura;
    this.#aguardandoAbertura = null;
    espera?.rejeitar(erro);
  }

  // ----- Recepcao ----------------------------------------------------------

  #aoReceber(frame) {
    let quadro;
    try {
      quadro = interpretarQuadroServidor(frame);
    } catch (e) {
      if (e instanceof ErroProtocolo) this.#avisarErro(e, 'quadro_invalido');
      else this.#avisarErro(e, 'receber');
      return;
    }

    try {
      switch (quadro.categoria) {
        case Categoria.MENSAGEM:
          this.#cb.aoMensagem?.(this.#decifrarMensagem(quadro.mensagem));
          break;
        case Categoria.HISTORICO:
          this.#aoHistorico(quadro);
          break;
        case Categoria.ERRO_GERAL:
          this.#avisarErro(new ErroRequisicao(quadro.erro.codigo, quadro.erro.mensagem, quadro.erro.campo ?? null), 'erro_geral');
          break;
        default:
          this.#aoResposta(quadro);
      }
    } catch (e) {
      this.#avisarErro(e, 'tratar_quadro');
    }
  }

  #aoResposta(quadro) {
    if (quadro.id === null) return;
    if (!quadro.ok) {
      this.#concluir(quadro.id, false, erroDe(quadro.erro));
      return;
    }
    this.#concluir(quadro.id, true, quadro);
  }

  #aoHistorico(quadro) {
    if (!quadro.ok) {
      if (quadro.id !== null) this.#concluir(quadro.id, false, erroDe(quadro.erro));
      return;
    }
    const mensagens = quadro.mensagens.map((m) => this.#decifrarMensagem(m));
    this.#cb.aoHistorico?.({ lote: quadro.lote, ultimo: quadro.ultimo, mensagens });
    if (quadro.id !== null && quadro.ultimo) this.#concluir(quadro.id, true, quadro);
  }

  /**
   * Decifra `conteudo`. Devolve `{mid, remetente, ts, tipo, conteudo, ilegivel}`:
   * `conteudo` e o texto (ou id da figurinha) em claro, ou `null` com
   * `ilegivel: true` quando a decifragem falha (o erro e reportado sem conteudo).
   */
  #decifrarMensagem(mensagem) {
    let conteudo = null;
    let ilegivel = false;
    try {
      conteudo = decifrar(this.#chave, mensagem.conteudo);
    } catch (e) {
      ilegivel = true;
      if (e instanceof DecifragemFalhou) {
        this.#avisarErro(new ErroRequisicao('DECIFRAGEM_FALHOU', 'Mensagem recebida nao pode ser decifrada'), 'decifrar');
      } else {
        this.#avisarErro(e, 'decifrar');
      }
    }
    return {
      mid: mensagem.mid,
      remetente: mensagem.remetente,
      ts: mensagem.ts,
      tipo: mensagem.tipo,
      conteudo,
      ilegivel,
    };
  }

  // ----- Ping --------------------------------------------------------------

  #iniciarPing() {
    this.#pararPing();
    const ciclo = () => {
      this.#timerPing = this.#agendar(() => {
        this.#timerPing = null;
        if (!this.aberta) return;
        try {
          this.#ws.send(serializar(montarPing(novoId('p'))));
        } catch (e) {
          this.#avisarErro(e, 'ping');
        }
        ciclo();
      }, INTERVALO_PING_MS);
    };
    ciclo();
  }

  #pararPing() {
    if (this.#timerPing !== null) {
      this.#cancelar(this.#timerPing);
      this.#timerPing = null;
    }
  }

  // ----- Utilitarios -------------------------------------------------------

  #definirEstado(estado, detalhe = {}) {
    this.#estado = estado;
    try {
      this.#cb.aoEstado?.(estado, detalhe);
    } catch (e) {
      this.#avisarErro(e, 'aoEstado');
    }
  }

  #avisarErro(erro, contexto) {
    try {
      this.#cb.aoErro?.(erro, contexto);
    } catch {
      // Falha no tratador de erros nunca se propaga (evita laco de reporte).
    }
  }

  #fecharSilencioso(ws) {
    ws.onopen = ws.onmessage = ws.onerror = ws.onclose = null;
    try {
      ws.close();
    } catch {
      // ignora
    }
  }
}

function erroDe(erro) {
  return new ErroRequisicao(erro.codigo, erro.mensagem, erro.campo ?? null);
}
