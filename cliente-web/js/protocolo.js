// Protocolo do Comunicador Distribuido de Grupo (lado JavaScript / Cliente_Web).
//
// Este modulo apenas monta e interpreta quadros JSON. Ele NAO cifra nada: os
// campos `dados` (register/login) e `conteudo` (send_message) devem chegar aqui
// ja cifrados pela Cifra_Manual (js/cifra.js) com a Chave_Transito.
//
// Transporte WebSocket: um frame de texto = um objeto JSON. Um "\n" final
// (ou "\r\n") e tolerado e ignorado. Frames binarios sao rejeitados.

/** Versao atual do Protocolo. */
export const VERSAO_PROTOCOLO = 1;

/** Tamanho maximo de um quadro, em bytes UTF-8 (1 MiB), igual ao do Middleware. */
export const TAMANHO_MAXIMO_QUADRO = 1024 * 1024;

/** Tamanho maximo do campo `id` de correlacao. */
export const TAMANHO_MAXIMO_ID = 64;

/** Maximo de eventos por requisicao `error_report`. */
export const MAXIMO_EVENTOS_ERRO = 20;

/** Operacoes de requisicao conhecidas. */
export const Operacao = Object.freeze({
  REGISTER: "register",
  LOGIN: "login",
  SEND_MESSAGE: "send_message",
  HISTORY: "history",
  ERROR_REPORT: "error_report",
  PING: "ping",
  LOGOUT: "logout",
});

/** Operacoes que so existem como evento enviado pelo Middleware. */
export const OPERACAO_MESSAGE = "message";

/** `op` usado pelo Middleware quando nao conseguiu interpretar o quadro. */
export const OPERACAO_ERRO = "erro";

/** Tipos de mensagem de conversa. */
export const TipoMensagem = Object.freeze({
  TEXTO: "texto",
  FIGURINHA: "figurinha",
});

/** Tipos de Cliente aceitos no campo `cliente.tipo` do login. */
export const TipoCliente = Object.freeze({
  DESKTOP: "desktop",
  WEB: "web",
  PYTHON: "python",
});

/** Codigos de erro do Protocolo. */
export const CodigoErro = Object.freeze({
  FORMATO_INVALIDO: "FORMATO_INVALIDO",
  MENSAGEM_MUITO_GRANDE: "MENSAGEM_MUITO_GRANDE",
  VERSAO_NAO_SUPORTADA: "VERSAO_NAO_SUPORTADA",
  OPERACAO_DESCONHECIDA: "OPERACAO_DESCONHECIDA",
  NAO_AUTENTICADO: "NAO_AUTENTICADO",
  JA_AUTENTICADO: "JA_AUTENTICADO",
  CREDENCIAIS_INVALIDAS: "CREDENCIAIS_INVALIDAS",
  LOGIN_DUPLICADO: "LOGIN_DUPLICADO",
  CAMPO_AUSENTE: "CAMPO_AUSENTE",
  CAMPO_INVALIDO: "CAMPO_INVALIDO",
  DECIFRAGEM_FALHOU: "DECIFRAGEM_FALHOU",
  CONTEUDO_INVALIDO: "CONTEUDO_INVALIDO",
  LIMITE_EXCEDIDO: "LIMITE_EXCEDIDO",
  SERVICO_INDISPONIVEL: "SERVICO_INDISPONIVEL",
  ERRO_INTERNO: "ERRO_INTERNO",
});

/** Categorias devolvidas por `interpretarQuadroServidor`. */
export const Categoria = Object.freeze({
  /** Evento `message`: nova mensagem distribuida a todas as Sessoes. */
  MENSAGEM: "mensagem",
  /** Quadro `history` (automatico apos o login ou resposta a `history`). */
  HISTORICO: "historico",
  /** Resposta (`ok` true ou false) a uma requisicao. */
  RESPOSTA: "resposta",
  /** Resposta `op:"erro"` a um quadro que o Middleware nao conseguiu interpretar. */
  ERRO_GERAL: "erro_geral",
});

/** Erro lancado ao montar ou interpretar quadros fora do Protocolo. */
export class ErroProtocolo extends Error {
  /**
   * @param {string} codigo um valor de CodigoErro
   * @param {string} mensagem texto fixo, sem eco de dados recebidos
   * @param {string|null} [campo] nome do campo problematico, quando houver
   */
  constructor(codigo, mensagem, campo = null) {
    super(mensagem);
    this.name = "ErroProtocolo";
    this.codigo = codigo;
    this.campo = campo;
  }
}

const MENSAGENS_FIXAS = Object.freeze({
  [CodigoErro.FORMATO_INVALIDO]: "Mensagem fora do formato do Protocolo",
  [CodigoErro.MENSAGEM_MUITO_GRANDE]: "Mensagem acima do tamanho maximo",
  [CodigoErro.VERSAO_NAO_SUPORTADA]: "Versao do Protocolo nao suportada",
});

function falhar(codigo, campo = null) {
  throw new ErroProtocolo(codigo, MENSAGENS_FIXAS[codigo] ?? codigo, campo);
}

function ehObjetoSimples(valor) {
  return valor !== null && typeof valor === "object" && !Array.isArray(valor);
}

const codificador = new TextEncoder();

function tamanhoUtf8(texto) {
  // Cada unidade UTF-16 gera no maximo 3 bytes; evita codificar textos pequenos.
  if (texto.length * 3 <= TAMANHO_MAXIMO_QUADRO) return texto.length * 3;
  return codificador.encode(texto).length;
}

// ---------------------------------------------------------------------------
// Serializacao e delimitacao
// ---------------------------------------------------------------------------

/**
 * Serializa um quadro como texto JSON de uma unica linha, sem "\n" final.
 * E o conteudo de um frame de texto WebSocket. O JSON escapa quebras de linha
 * dentro de strings, logo o resultado nunca contem "\n" literal.
 *
 * @param {object} quadro
 * @returns {string}
 */
export function serializar(quadro) {
  if (!ehObjetoSimples(quadro)) {
    throw new ErroProtocolo(CodigoErro.FORMATO_INVALIDO, MENSAGENS_FIXAS[CodigoErro.FORMATO_INVALIDO]);
  }
  const texto = JSON.stringify(quadro);
  if (tamanhoUtf8(texto) > TAMANHO_MAXIMO_QUADRO) falhar(CodigoErro.MENSAGEM_MUITO_GRANDE);
  return texto;
}

/**
 * Serializa um quadro terminado em "\n" (Delimitador do transporte TCP).
 * O Cliente_Web usa WebSocket e normalmente nao precisa dele; fica disponivel
 * para testes de ida e volta e para o espiao de trafego.
 *
 * @param {object} quadro
 * @returns {string}
 */
export function serializarComDelimitador(quadro) {
  return `${serializar(quadro)}\n`;
}

/**
 * Remove no maximo um terminador de linha final ("\n" ou "\r\n").
 * @param {string} texto
 * @returns {string}
 */
function removerTerminador(texto) {
  if (texto.endsWith("\r\n")) return texto.slice(0, -2);
  if (texto.endsWith("\n")) return texto.slice(0, -1);
  return texto;
}

/**
 * Interpreta um frame WebSocket e devolve o envelope validado (objeto JSON).
 *
 * Regras:
 *  - o frame deve ser texto (string); binarios (ArrayBuffer, Blob, typed arrays)
 *    sao rejeitados com FORMATO_INVALIDO;
 *  - um "\n" (ou "\r\n") final e tolerado;
 *  - o JSON deve ser um objeto com `v` (inteiro) e `op` (string nao vazia);
 *  - `v` diferente de 1 gera VERSAO_NAO_SUPORTADA;
 *  - campos desconhecidos sao mantidos e devem ser ignorados pelo chamador.
 *
 * @param {unknown} frame
 * @returns {object} o objeto JSON, sem alteracoes
 * @throws {ErroProtocolo}
 */
export function interpretarQuadro(frame) {
  if (typeof frame !== "string") falhar(CodigoErro.FORMATO_INVALIDO);
  if (tamanhoUtf8(frame) > TAMANHO_MAXIMO_QUADRO) falhar(CodigoErro.MENSAGEM_MUITO_GRANDE);

  const corpo = removerTerminador(frame);
  if (corpo.trim() === "") falhar(CodigoErro.FORMATO_INVALIDO);

  let objeto;
  try {
    objeto = JSON.parse(corpo);
  } catch {
    falhar(CodigoErro.FORMATO_INVALIDO);
  }

  if (!ehObjetoSimples(objeto)) falhar(CodigoErro.FORMATO_INVALIDO);
  if (!("v" in objeto)) falhar(CodigoErro.FORMATO_INVALIDO, "v");
  if (!Number.isInteger(objeto.v)) falhar(CodigoErro.FORMATO_INVALIDO, "v");
  if (typeof objeto.op !== "string" || objeto.op === "") falhar(CodigoErro.FORMATO_INVALIDO, "op");
  if (objeto.v !== VERSAO_PROTOCOLO) falhar(CodigoErro.VERSAO_NAO_SUPORTADA, "v");
  if ("id" in objeto && objeto.id !== null && typeof objeto.id !== "string") {
    falhar(CodigoErro.FORMATO_INVALIDO, "id");
  }
  return objeto;
}

// ---------------------------------------------------------------------------
// Construtores de requisicoes
// ---------------------------------------------------------------------------

let contadorId = 0;

/**
 * Gera um identificador de correlacao (ate 64 caracteres).
 * @param {string} [prefixo]
 * @returns {string}
 */
export function novoId(prefixo = "r") {
  const aleatorio =
    typeof globalThis.crypto?.randomUUID === "function"
      ? globalThis.crypto.randomUUID()
      : `${Date.now().toString(36)}-${(contadorId++).toString(36)}`;
  return `${prefixo}-${aleatorio}`.slice(0, TAMANHO_MAXIMO_ID);
}

function envelope(op, id) {
  const quadro = { v: VERSAO_PROTOCOLO, op };
  if (id !== undefined && id !== null) {
    if (typeof id !== "string" || id.length === 0 || id.length > TAMANHO_MAXIMO_ID) {
      throw new ErroProtocolo(CodigoErro.CAMPO_INVALIDO, "Campo invalido", "id");
    }
    quadro.id = id;
  }
  return quadro;
}

function exigirTexto(valor, campo) {
  if (typeof valor !== "string") {
    throw new ErroProtocolo(CodigoErro.CAMPO_INVALIDO, "Campo invalido", campo);
  }
  return valor;
}

/**
 * Requisicao `register`. `dados` e a cifra (Transito) do JSON
 * `{login, apelido, senha, avatar, cor}`.
 */
export function montarRegister(id, dados) {
  return { ...envelope(Operacao.REGISTER, id), dados: exigirTexto(dados, "dados") };
}

/**
 * Requisicao `login`. `dados` e a cifra (Transito) do JSON `{login, senha}`.
 *
 * @param {string|null} id
 * @param {string} dados
 * @param {string} dispositivo UUID do dispositivo
 * @param {{tipo: string, versao: string}} [cliente] padrao: web
 */
export function montarLogin(id, dados, dispositivo, cliente = { tipo: TipoCliente.WEB, versao: "1.0" }) {
  exigirTexto(dados, "dados");
  exigirTexto(dispositivo, "dispositivo");
  if (!ehObjetoSimples(cliente) || !Object.values(TipoCliente).includes(cliente.tipo)) {
    throw new ErroProtocolo(CodigoErro.CAMPO_INVALIDO, "Campo invalido", "cliente");
  }
  return { ...envelope(Operacao.LOGIN, id), dados, dispositivo, cliente: { ...cliente } };
}

/**
 * Requisicao `send_message`. `conteudo` e a cifra (Transito) do texto ou do
 * identificador da figurinha. O remetente nunca vai no quadro: o Middleware o
 * obtem da Sessao.
 *
 * @param {string|null} id
 * @param {"texto"|"figurinha"} tipo
 * @param {string} conteudo
 */
export function montarSendMessage(id, tipo, conteudo) {
  if (!Object.values(TipoMensagem).includes(tipo)) {
    throw new ErroProtocolo(CodigoErro.CAMPO_INVALIDO, "Campo invalido", "tipo");
  }
  return { ...envelope(Operacao.SEND_MESSAGE, id), tipo, conteudo: exigirTexto(conteudo, "conteudo") };
}

/**
 * Requisicao `history`. `desde` (opcional) e um instante ISO-8601 UTC.
 * @param {string|null} id
 * @param {string|Date} [desde]
 */
export function montarHistory(id, desde) {
  const quadro = envelope(Operacao.HISTORY, id);
  if (desde !== undefined && desde !== null) {
    const texto = desde instanceof Date ? desde.toISOString() : exigirTexto(desde, "desde");
    quadro.desde = texto;
  }
  return quadro;
}

/** Requisicao `logout`. */
export function montarLogout(id) {
  return envelope(Operacao.LOGOUT, id);
}

/**
 * Requisicao `error_report` com ate 20 eventos de erro (Modelos de Dados).
 * @param {string|null} id
 * @param {object[]} eventos
 */
export function montarErrorReport(id, eventos) {
  if (!Array.isArray(eventos) || eventos.length === 0 || eventos.length > MAXIMO_EVENTOS_ERRO) {
    throw new ErroProtocolo(CodigoErro.CAMPO_INVALIDO, "Campo invalido", "eventos");
  }
  if (!eventos.every(ehObjetoSimples)) {
    throw new ErroProtocolo(CodigoErro.CAMPO_INVALIDO, "Campo invalido", "eventos");
  }
  return { ...envelope(Operacao.ERROR_REPORT, id), eventos };
}

/** Requisicao `ping`. */
export function montarPing(id) {
  return envelope(Operacao.PING, id);
}

// ---------------------------------------------------------------------------
// Interpretacao de respostas e eventos do Middleware
// ---------------------------------------------------------------------------

function validarPerfil(perfil, campo) {
  if (!ehObjetoSimples(perfil)) falhar(CodigoErro.FORMATO_INVALIDO, campo);
  for (const chave of ["login", "apelido", "avatar", "cor"]) {
    if (typeof perfil[chave] !== "string") falhar(CodigoErro.FORMATO_INVALIDO, `${campo}.${chave}`);
  }
  return perfil;
}

/**
 * Valida o objeto `mensagem` de `message` e `history`.
 * `conteudo` continua cifrado (Chave_Transito); decifrar e responsabilidade do chamador.
 */
function validarMensagem(mensagem, campo) {
  if (!ehObjetoSimples(mensagem)) falhar(CodigoErro.FORMATO_INVALIDO, campo);
  for (const chave of ["mid", "ts", "conteudo"]) {
    if (typeof mensagem[chave] !== "string") falhar(CodigoErro.FORMATO_INVALIDO, `${campo}.${chave}`);
  }
  if (!Object.values(TipoMensagem).includes(mensagem.tipo)) {
    falhar(CodigoErro.FORMATO_INVALIDO, `${campo}.tipo`);
  }
  validarPerfil(mensagem.remetente, `${campo}.remetente`);
  return mensagem;
}

function validarErro(erro) {
  if (!ehObjetoSimples(erro) || typeof erro.codigo !== "string" || erro.codigo === "") {
    falhar(CodigoErro.FORMATO_INVALIDO, "erro");
  }
  if (typeof erro.mensagem !== "string") falhar(CodigoErro.FORMATO_INVALIDO, "erro.mensagem");
  if ("campo" in erro && erro.campo !== null && typeof erro.campo !== "string") {
    falhar(CodigoErro.FORMATO_INVALIDO, "erro.campo");
  }
  return erro;
}

/**
 * Interpreta um frame enviado pelo Middleware e o classifica.
 *
 * Retorna sempre `{categoria, op, id, bruto, ...}`:
 *  - MENSAGEM:   `{mensagem}` (evento `message`, sem `id` nem `ok`);
 *  - HISTORICO:  `{ok, lote, ultimo, mensagens}` quando `ok`, ou `{ok:false, erro}`;
 *  - RESPOSTA:   `{ok:true, ...}` ou `{ok:false, erro}`; os campos extras da
 *                resposta (`sessao`, `perfil`, `mid`, `ts`, `aceitos`, `login`)
 *                ficam em `bruto`, e `perfil` tambem e exposto no topo quando existe;
 *  - ERRO_GERAL: `{ok:false, erro}` com `op:"erro"` e `id:null`.
 *
 * @param {unknown} frame
 * @throws {ErroProtocolo} FORMATO_INVALIDO se o quadro nao seguir o Protocolo
 */
export function interpretarQuadroServidor(frame) {
  const bruto = interpretarQuadro(frame);
  const op = bruto.op;
  const id = typeof bruto.id === "string" ? bruto.id : null;

  if (op === OPERACAO_MESSAGE) {
    return {
      categoria: Categoria.MENSAGEM,
      op,
      id,
      mensagem: validarMensagem(bruto.mensagem, "mensagem"),
      bruto,
    };
  }

  if (typeof bruto.ok !== "boolean") falhar(CodigoErro.FORMATO_INVALIDO, "ok");

  if (!bruto.ok) {
    const categoria = op === OPERACAO_ERRO ? Categoria.ERRO_GERAL : op === Operacao.HISTORY ? Categoria.HISTORICO : Categoria.RESPOSTA;
    return { categoria, op, id, ok: false, erro: validarErro(bruto.erro), bruto };
  }

  if (op === OPERACAO_ERRO) falhar(CodigoErro.FORMATO_INVALIDO, "ok");

  if (op === Operacao.HISTORY) {
    if (!Array.isArray(bruto.mensagens)) falhar(CodigoErro.FORMATO_INVALIDO, "mensagens");
    if (!Number.isInteger(bruto.lote) || bruto.lote < 1) falhar(CodigoErro.FORMATO_INVALIDO, "lote");
    if (typeof bruto.ultimo !== "boolean") falhar(CodigoErro.FORMATO_INVALIDO, "ultimo");
    bruto.mensagens.forEach((m, i) => validarMensagem(m, `mensagens[${i}]`));
    return {
      categoria: Categoria.HISTORICO,
      op,
      id,
      ok: true,
      lote: bruto.lote,
      ultimo: bruto.ultimo,
      mensagens: bruto.mensagens,
      bruto,
    };
  }

  const resposta = { categoria: Categoria.RESPOSTA, op, id, ok: true, bruto };
  if (op === Operacao.LOGIN) {
    if (typeof bruto.sessao !== "string") falhar(CodigoErro.FORMATO_INVALIDO, "sessao");
    resposta.sessao = bruto.sessao;
    resposta.perfil = validarPerfil(bruto.perfil, "perfil");
  } else if (op === Operacao.SEND_MESSAGE) {
    if (typeof bruto.mid !== "string") falhar(CodigoErro.FORMATO_INVALIDO, "mid");
    if (typeof bruto.ts !== "string") falhar(CodigoErro.FORMATO_INVALIDO, "ts");
    resposta.mid = bruto.mid;
    resposta.ts = bruto.ts;
  } else if (op === Operacao.ERROR_REPORT) {
    if (!Array.isArray(bruto.aceitos) || !bruto.aceitos.every((e) => typeof e === "string")) {
      falhar(CodigoErro.FORMATO_INVALIDO, "aceitos");
    }
    resposta.aceitos = bruto.aceitos;
  }
  return resposta;
}
