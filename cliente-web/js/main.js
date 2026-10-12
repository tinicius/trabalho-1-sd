// Interface do Cliente_Web e integracao dos modulos.
//
// Liga: ConexaoWeb (conexao.js), HistoricoLocal (historico.js), ReportadorErros
// (erros.js), ControladorEnvio/inserirEmoji (envio.js) e a Tabela_de_Conversao
// (emoticons.js) as telas de login, cadastro e sala do index.html.
//
// Regras:
// - Dados vindos da rede (apelido, texto, cor, ids) entram no DOM apenas por
//   `textContent`, `setAttribute` de valores validados ou `style.color`
//   validado. Nunca `innerHTML`/`insertAdjacentHTML` com dados de mensagens.
// - A tela da sala so aparece depois de `ok:true` no login.
// - Mensagens enviadas aparecem so no eco do Middleware; a incorporacao ao
//   Historico_Local e idempotente por `mid`, e a exibicao ignora `mid` ja
//   mostrado (historico automatico apos login/relogin sem duplicatas).
// - Eventos `message`/`history` sao processados em fila, depois que o
//   Historico_Local do usuario foi aberto e exibido.
// - ReportadorErros: `aposLogin()` depois do login e do relogin automatico
//   (`aoReautenticado`); captura global de erros ligada na inicializacao.
//
// A funcao `iniciarApp` recebe dependencias opcionais para testes (jsdom).

import {
  guardarChaveTransito,
  lerChaveTransito,
  normalizarChave,
  obterDispositivo,
  urlRecurso,
} from './chave.js';
import { ConexaoWeb, ErroRequisicao, EstadoConexao, MAXIMO_TENTATIVAS } from './conexao.js';
import { carregarTabela, criarTabela } from './emoticons.js';
import {
  ControladorEnvio,
  MAXIMO_PONTOS_DE_CODIGO,
  MotivoNaoEnviado,
  ehIdFigurinha,
} from './envio.js';
import { criarReportadorErros, instalarCapturaGlobal } from './erros.js';
import { abrirHistorico, compararPorTsMid } from './historico.js';

// ---------------------------------------------------------------------------
// Constantes
// ---------------------------------------------------------------------------

/** Quantidade de avatares em `recursos/avatares` (av_01 .. av_12). */
export const TOTAL_AVATARES = 12;

/** Quantidade de figurinhas em `recursos/figurinhas` (fig_01 .. fig_12). */
export const TOTAL_FIGURINHAS = 12;

/** Colunas da grade do Seletor_de_Emojis (6 linhas x 8 colunas = 48 emojis). */
export const COLUNAS_EMOJIS = 8;

const REGEX_AVATAR = /^av_[0-9]{2}$/;
const REGEX_COR = /^#[0-9a-fA-F]{6}$/;
const REGEX_LOGIN = /^[a-z0-9_]{3,20}$/;

const NOMES_DE_CAMPO = Object.freeze({
  login: 'login',
  apelido: 'apelido',
  senha: 'senha',
  avatar: 'avatar',
  cor: 'cor',
});

// ---------------------------------------------------------------------------
// Funcoes puras e auxiliares de DOM
// ---------------------------------------------------------------------------

/** `true` se `id` tem o formato `av_NN`. */
export function ehIdAvatar(id) {
  return typeof id === 'string' && REGEX_AVATAR.test(id);
}

/** `true` se `cor` e `#RRGGBB`. */
export function ehCorValida(cor) {
  return typeof cor === 'string' && REGEX_COR.test(cor);
}

/** Identificador de avatar (`av_NN`) na posicao `n` (1 a 99). */
export function idAvatar(n) {
  return `av_${String(n).padStart(2, '0')}`;
}

/** Identificador de figurinha (`fig_NN`) na posicao `n` (1 a 99). */
export function idFigurinhaNumero(n) {
  return `fig_${String(n).padStart(2, '0')}`;
}

/** Data e hora locais de um instante ISO-8601; devolve o texto original se for invalido. */
export function formatarDataHora(ts) {
  const data = new Date(ts);
  if (Number.isNaN(data.getTime())) return String(ts);
  return data.toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'medium' });
}

function limitar(texto, maximo = 40) {
  const s = String(texto ?? '');
  return s.length > maximo ? `${s.slice(0, maximo)}...` : s;
}

/** Mensagem amigavel em portugues para um erro (resposta `ok:false`, rede ou tempo esgotado). */
export function mensagemDeErro(erro) {
  const codigo = erro?.codigo;
  const campo = NOMES_DE_CAMPO[erro?.campo];
  switch (codigo) {
    case 'CREDENCIAIS_INVALIDAS':
      return 'Login ou senha inválidos';
    case 'LOGIN_DUPLICADO':
      return 'Este login já está em uso';
    case 'CAMPO_AUSENTE':
      return campo ? `Preencha o campo ${campo}` : 'Preencha todos os campos';
    case 'CAMPO_INVALIDO':
      return campo ? `Valor inválido no campo ${campo}` : 'Algum campo tem valor inválido';
    case 'DECIFRAGEM_FALHOU':
      return 'Não foi possível validar os dados: confira a chave de trânsito';
    case 'JA_AUTENTICADO':
      return 'Você já está autenticado';
    case 'NAO_AUTENTICADO':
      return 'Faça login para continuar';
    case 'LIMITE_EXCEDIDO':
      return 'Limite de uso excedido, aguarde um instante e tente de novo';
    case 'SERVICO_INDISPONIVEL':
      return 'Serviço indisponível, tente novamente em instantes';
    case 'SEM_CONEXAO':
    case 'CONEXAO_PERDIDA':
      return 'Não foi possível conectar ao servidor';
    case 'TEMPO_ESGOTADO':
      return 'O servidor não respondeu a tempo';
    case 'CONTEUDO_INVALIDO':
      return 'Conteúdo da mensagem inválido';
    default:
      return codigo ? `Erro inesperado (${limitar(codigo, 40)})` : 'Erro inesperado';
  }
}

function criarMarcador(doc, texto) {
  const span = doc.createElement('span');
  span.className = 'marcador';
  span.textContent = texto;
  return span;
}

function criarAvatar(doc, remetente) {
  const id = remetente?.avatar;
  if (!ehIdAvatar(id)) return Object.assign(criarMarcador(doc, `[avatar ${limitar(id)}]`), { className: 'marcador avatar-marcador' });
  const img = doc.createElement('img');
  img.className = 'avatar';
  img.alt = `Avatar de ${limitar(remetente?.apelido, 30)}`;
  img.width = 40;
  img.height = 40;
  img.src = urlRecurso(`avatares/${id}.png`);
  img.addEventListener(
    'error',
    () => img.replaceWith(Object.assign(criarMarcador(doc, `[avatar ${id}]`), { className: 'marcador avatar-marcador' })),
    { once: true },
  );
  return img;
}

function criarConteudo(doc, m) {
  if (m.ilegivel) {
    const aviso = doc.createElement('div');
    aviso.className = 'ilegivel';
    aviso.textContent = 'mensagem ilegível';
    return aviso;
  }
  if (m.tipo === 'figurinha') {
    const id = m.conteudo;
    if (!ehIdFigurinha(id)) {
      const marcador = criarMarcador(doc, `[figurinha ${limitar(id)}]`);
      marcador.classList.add('figurinha-marcador');
      return marcador;
    }
    const img = doc.createElement('img');
    img.className = 'figurinha';
    img.alt = id;
    img.src = urlRecurso(`figurinhas/${id}.png`);
    img.addEventListener(
      'error',
      () => {
        const marcador = criarMarcador(doc, `[figurinha ${id}]`);
        marcador.classList.add('figurinha-marcador');
        img.replaceWith(marcador);
      },
      { once: true },
    );
    return img;
  }
  const texto = doc.createElement('div');
  texto.className = 'texto';
  texto.textContent = String(m.conteudo ?? '');
  return texto;
}

/**
 * Monta o elemento de uma mensagem: `div.mensagem` com avatar (`img`), apelido
 * com `style="color:..."`, data/hora local e texto, imagem da figurinha ou
 * marcador. Todo dado da rede entra por `textContent` ou atributo validado.
 *
 * @param {Document} doc
 * @param {{mid: string, ts: string, tipo: string, conteudo: string|null, ilegivel?: boolean,
 *          remetente: {apelido: string, avatar: string, cor: string}}} m
 * @returns {HTMLDivElement}
 */
export function criarElementoMensagem(doc, m) {
  const el = doc.createElement('div');
  el.className = 'mensagem';
  el.dataset.mid = m.mid;
  el.dataset.ts = m.ts;

  const corpo = doc.createElement('div');
  corpo.className = 'corpo';

  const cabecalho = doc.createElement('div');
  cabecalho.className = 'cabecalho';
  const apelido = doc.createElement('span');
  apelido.className = 'apelido';
  apelido.textContent = String(m.remetente?.apelido ?? '');
  if (ehCorValida(m.remetente?.cor)) apelido.style.color = m.remetente.cor;
  const hora = doc.createElement('time');
  hora.className = 'hora';
  hora.textContent = formatarDataHora(m.ts);
  hora.setAttribute('datetime', String(m.ts));
  cabecalho.append(apelido, hora);

  corpo.append(cabecalho, criarConteudo(doc, m));
  el.append(criarAvatar(doc, m.remetente), corpo);
  return el;
}

/** Insere `el` em `lista` mantendo a ordem (ts, mid); em geral, no fim. */
function inserirOrdenado(lista, el) {
  const chave = { ts: el.dataset.ts, mid: el.dataset.mid };
  let ref = lista.lastElementChild;
  while (ref && compararPorTsMid({ ts: ref.dataset.ts, mid: ref.dataset.mid }, chave) > 0) {
    ref = ref.previousElementSibling;
  }
  if (ref === null) lista.prepend(el);
  else ref.after(el);
}

// ---------------------------------------------------------------------------
// Aplicacao
// ---------------------------------------------------------------------------

/**
 * Inicia a interface sobre o documento (padrao: `document`).
 *
 * @param {object} [opcoes] dependencias injetaveis (testes)
 * @param {Document} [opcoes.doc]
 * @param {Window} [opcoes.janela]
 * @param {Storage} [opcoes.storage] padrao: localStorage
 * @param {IDBFactory} [opcoes.indexedDB]
 * @param {(url: string) => Promise<Response>} [opcoes.buscar] padrao: fetch
 * @param {(o: object) => ConexaoWeb} [opcoes.criarConexao]
 * @param {typeof abrirHistorico} [opcoes.abrirHistorico]
 * @param {object} [opcoes.reportador] padrao: criarReportadorErros
 * @param {boolean} [opcoes.capturaGlobal] padrao: true
 * @returns {{pronto: Promise<void>, reportador: object, conexao: () => (ConexaoWeb|null), encerrar: () => void}}
 */
export function iniciarApp(opcoes = {}) {
  const doc = opcoes.doc ?? globalThis.document;
  const janela = opcoes.janela ?? globalThis.window ?? globalThis;
  const storage = opcoes.storage ?? globalThis.localStorage;
  const buscar = opcoes.buscar ?? ((url) => globalThis.fetch(url));
  const criarConexao = opcoes.criarConexao ?? ((o) => new ConexaoWeb(o));
  const abrirHist = opcoes.abrirHistorico ?? abrirHistorico;
  const dispositivo = obterDispositivo(storage);

  const $ = (id) => {
    const el = doc.getElementById(id);
    if (!el) throw new Error(`Elemento #${id} ausente no index.html`);
    return el;
  };
  const el = {
    estado: $('estado-conexao'),
    botaoReconectar: $('botao-reconectar'),
    botaoSair: $('botao-sair'),
    telaLogin: $('tela-login'),
    telaCadastro: $('tela-cadastro'),
    telaSala: $('tela-sala'),
    formLogin: $('form-login'),
    campoLogin: $('campo-login'),
    campoSenha: $('campo-senha'),
    campoChave: $('campo-chave'),
    erroLogin: $('erro-login'),
    infoLogin: $('info-login'),
    botaoEntrar: $('botao-entrar'),
    botaoIrCadastro: $('botao-ir-cadastro'),
    formCadastro: $('form-cadastro'),
    cadLogin: $('cad-login'),
    cadApelido: $('cad-apelido'),
    cadSenha: $('cad-senha'),
    cadCor: $('cad-cor'),
    cadChave: $('cad-chave'),
    listaAvatares: $('lista-avatares'),
    erroCadastro: $('erro-cadastro'),
    botaoCadastrar: $('botao-cadastrar'),
    botaoIrLogin: $('botao-ir-login'),
    mensagens: $('lista-mensagens'),
    avisoSala: $('aviso-sala'),
    erroEnvio: $('erro-envio'),
    seletorEmojis: $('seletor-emojis'),
    seletorFigurinhas: $('seletor-figurinhas'),
    formEnvio: $('form-envio'),
    botaoEmojis: $('botao-emojis'),
    botaoFigurinhas: $('botao-figurinhas'),
    campoTexto: $('campo-texto'),
    botaoEnviar: $('botao-enviar'),
  };

  // ----- Reportador de erros ------------------------------------------------

  const reportador =
    opcoes.reportador ??
    criarReportadorErros({ dispositivo, indexedDB: opcoes.indexedDB ?? globalThis.indexedDB });
  const desinstalarCaptura = opcoes.capturaGlobal === false ? () => {} : instalarCapturaGlobal(reportador, janela);
  reportador.iniciar?.();

  /** Reporta uma excecao inesperada sem nunca lancar. */
  function reportar(erro, modulo = 'main.js') {
    try {
      reportador.reportar(erro, { modulo });
    } catch {
      // nunca propaga
    }
  }

  // ----- Estado -------------------------------------------------------------

  let conexao = null;
  let conexaoChave = null;
  /** @type {null | {perfil: object, historico: object|null}} */
  let sessao = null;
  let tabela = criarTabela([]);
  let avatarEscolhido = idAvatar(1);

  // Fila de eventos recebidos: espera a sessao estar pronta (historico aberto e exibido).
  let fila = Promise.resolve();
  let sessaoPronta = new Promise(() => {});
  let liberarSessao = () => {};

  function reiniciarFila() {
    fila = Promise.resolve();
    sessaoPronta = new Promise((resolve) => {
      liberarSessao = resolve;
    });
  }

  function enfileirar(tarefa) {
    const pronta = sessaoPronta;
    fila = fila
      .then(() => pronta)
      .then((s) => (s === sessao ? tarefa(s) : undefined))
      .catch((e) => reportar(e));
  }

  // ----- Telas --------------------------------------------------------------

  function mostrarTela(nome) {
    el.telaLogin.hidden = nome !== 'login';
    el.telaCadastro.hidden = nome !== 'cadastro';
    el.telaSala.hidden = nome !== 'sala';
    el.botaoSair.hidden = nome !== 'sala';
  }

  function mostrarTexto(elemento, texto) {
    elemento.textContent = texto ?? '';
    elemento.hidden = !texto;
  }

  function atualizarEstado(texto, { desconectado = false, tentarNovamente = false } = {}) {
    el.estado.textContent = texto ?? '';
    el.estado.hidden = !texto;
    el.estado.classList.toggle('desconectado', desconectado);
    el.botaoReconectar.hidden = !tentarNovamente;
  }

  // ----- Mensagens ----------------------------------------------------------

  function exibir(mensagens) {
    const lista = el.mensagens;
    const perto = lista.scrollHeight - lista.scrollTop - lista.clientHeight < 80;
    const jaExibidas = new Set(Array.from(lista.children, (c) => c.dataset.mid));
    for (const m of mensagens) {
      if (jaExibidas.has(m.mid)) continue;
      jaExibidas.add(m.mid);
      inserirOrdenado(lista, criarElementoMensagem(doc, m));
    }
    if (perto) lista.scrollTop = lista.scrollHeight;
  }

  async function incorporar(s, mensagens) {
    if (!s.historico) return;
    try {
      await s.historico.incorporar(mensagens);
    } catch (e) {
      mostrarTexto(el.avisoSala, 'Não foi possível gravar no histórico local.');
      reportar(e, 'historico.js');
    }
  }

  async function tratarMensagem(s, mensagem) {
    await incorporar(s, [mensagem]);
    exibir([mensagem]);
  }

  async function tratarHistorico(s, { mensagens }) {
    await incorporar(s, mensagens);
    exibir(mensagens);
  }

  // ----- Conexao ------------------------------------------------------------

  function aoEstado(estado, detalhe = {}) {
    if (!sessao) {
      atualizarEstado('');
      return;
    }
    switch (estado) {
      case EstadoConexao.RECONECTANDO:
        atualizarEstado(`Reconectando (tentativa ${detalhe.tentativa ?? '?'} de ${detalhe.maximo ?? MAXIMO_TENTATIVAS})`);
        break;
      case EstadoConexao.DESCONECTADO:
        if (detalhe.motivo === 'credenciais_invalidas') {
          encerrarSessao('Sua sessão terminou: login ou senha inválidos.');
        } else if (detalhe.motivo === 'encerrado') {
          atualizarEstado('');
        } else {
          atualizarEstado('Desconectado', { desconectado: true, tentarNovamente: true });
        }
        break;
      default:
        atualizarEstado('');
    }
  }

  function aoReautenticado(perfil) {
    if (sessao && perfil) sessao.perfil = perfil;
    atualizarEstado('');
    reportador.aposLogin();
  }

  function obterConexao(chave) {
    if (conexao && conexaoChave === chave) return conexao;
    conexao?.fechar();
    const nova = criarConexao({
      chaveTransito: chave,
      dispositivo,
      aoEstado: (estado, detalhe) => {
        if (conexao === nova) aoEstado(estado, detalhe);
      },
      aoMensagem: (m) => {
        if (conexao === nova) enfileirar((s) => tratarMensagem(s, m));
      },
      aoHistorico: (h) => {
        if (conexao === nova) enfileirar((s) => tratarHistorico(s, h));
      },
      aoReautenticado: (perfil) => {
        if (conexao === nova) aoReautenticado(perfil);
      },
      aoErro: (erro, contexto) => {
        if (conexao === nova) reportar(erro, `conexao.js:${contexto}`);
      },
    });
    conexao = nova;
    conexaoChave = chave;
    reportador.definirConexao?.(nova);
    return nova;
  }

  // ----- Sessao -------------------------------------------------------------

  async function iniciarSessao(perfil, chave) {
    const nova = { perfil, historico: null };
    sessao = nova;
    mostrarTexto(el.avisoSala, '');
    try {
      nova.historico = await abrirHist(perfil.login, {
        indexedDB: opcoes.indexedDB,
        dispositivo,
        chaveTransito: chave,
      });
    } catch (e) {
      mostrarTexto(el.avisoSala, 'Histórico local indisponível neste navegador. As mensagens não serão guardadas.');
      reportar(e, 'historico.js');
    }
    el.mensagens.replaceChildren();
    if (nova.historico) {
      try {
        exibir(await nova.historico.listar());
      } catch (e) {
        mostrarTexto(el.avisoSala, 'Não foi possível ler o histórico local.');
        reportar(e, 'historico.js');
      }
    }
    atualizarEstado('');
    mostrarTela('sala');
    el.campoTexto.focus();
    liberarSessao(nova);
    reportador.aposLogin();
  }

  function encerrarSessao(aviso = '') {
    const s = sessao;
    sessao = null;
    reiniciarFila();
    try {
      s?.historico?.fechar();
    } catch (e) {
      reportar(e, 'historico.js');
    }
    el.mensagens.replaceChildren();
    el.campoTexto.value = '';
    el.campoSenha.value = '';
    el.seletorEmojis.hidden = true;
    el.seletorFigurinhas.hidden = true;
    mostrarTexto(el.erroEnvio, '');
    mostrarTexto(el.avisoSala, '');
    atualizarEstado('');
    mostrarTexto(el.infoLogin, '');
    mostrarTexto(el.erroLogin, aviso);
    mostrarTela('login');
  }

  // ----- Login e cadastro ---------------------------------------------------

  function tratarFalha(elementoErro, erro) {
    if (!(erro instanceof ErroRequisicao)) reportar(erro);
    mostrarTexto(elementoErro, mensagemDeErro(erro));
  }

  async function aoEnviarLogin(evento) {
    evento.preventDefault();
    mostrarTexto(el.erroLogin, '');
    mostrarTexto(el.infoLogin, '');
    const login = el.campoLogin.value.trim().toLowerCase();
    const senha = el.campoSenha.value;
    const chave = normalizarChave(el.campoChave.value);
    if (!REGEX_LOGIN.test(login) || senha === '') {
      mostrarTexto(el.erroLogin, mensagemDeErro({ codigo: 'CREDENCIAIS_INVALIDAS' }));
      return;
    }
    if (chave === null) {
      mostrarTexto(el.erroLogin, 'Chave de trânsito inválida: informe 64 dígitos hexadecimais.');
      return;
    }
    el.botaoEntrar.disabled = true;
    try {
      guardarChaveTransito(chave, storage);
      el.cadChave.value = chave;
      reiniciarFila();
      const c = obterConexao(chave);
      const { perfil } = await c.login(login, senha);
      el.campoSenha.value = '';
      await iniciarSessao(perfil, chave);
    } catch (e) {
      tratarFalha(el.erroLogin, e);
    } finally {
      el.botaoEntrar.disabled = false;
    }
  }

  function validarCadastro(campos, chave) {
    if (chave === null) return 'Chave de trânsito inválida: informe 64 dígitos hexadecimais.';
    if (!REGEX_LOGIN.test(campos.login)) return 'Login: 3 a 20 caracteres entre letras minúsculas, números e _.';
    const pontos = Array.from(campos.apelido).length;
    if (pontos < 1 || pontos > 30) return 'Apelido: de 1 a 30 caracteres.';
    if (campos.senha.length < 6 || campos.senha.length > 72) return 'Senha: de 6 a 72 caracteres.';
    if (!ehIdAvatar(campos.avatar)) return 'Escolha um avatar.';
    if (!ehCorValida(campos.cor)) return 'Escolha uma cor válida.';
    return null;
  }

  async function aoEnviarCadastro(evento) {
    evento.preventDefault();
    mostrarTexto(el.erroCadastro, '');
    const campos = {
      login: el.cadLogin.value.trim().toLowerCase(),
      apelido: el.cadApelido.value.trim(),
      senha: el.cadSenha.value,
      avatar: avatarEscolhido,
      cor: el.cadCor.value.toUpperCase(),
    };
    const chave = normalizarChave(el.cadChave.value);
    const problema = validarCadastro(campos, chave);
    if (problema) {
      mostrarTexto(el.erroCadastro, problema);
      return;
    }
    el.botaoCadastrar.disabled = true;
    try {
      guardarChaveTransito(chave, storage);
      el.campoChave.value = chave;
      await obterConexao(chave).registrar(campos);
      el.cadSenha.value = '';
      el.campoLogin.value = campos.login;
      el.campoSenha.value = '';
      mostrarTela('login');
      mostrarTexto(el.erroLogin, '');
      mostrarTexto(el.infoLogin, 'Conta criada. Entre com seu login e sua senha.');
      el.campoSenha.focus();
    } catch (e) {
      tratarFalha(el.erroCadastro, e);
    } finally {
      el.botaoCadastrar.disabled = false;
    }
  }

  async function aoSair() {
    el.botaoSair.disabled = true;
    try {
      await conexao?.logout();
    } catch (e) {
      if (!(e instanceof ErroRequisicao)) reportar(e);
    } finally {
      el.botaoSair.disabled = false;
      encerrarSessao();
    }
  }

  // ----- Sala: envio, emojis e figurinhas -----------------------------------

  const controlador = new ControladorEnvio({
    // A tabela e carregada depois; o getter acompanha a versao mais recente.
    tabela: {
      get pares() {
        return tabela.pares;
      },
    },
    enviar: (tipo, conteudo) => {
      if (!conexao) return Promise.reject(new ErroRequisicao('SEM_CONEXAO', 'Sem conexão com o servidor'));
      return conexao.enviarMensagem(tipo, conteudo);
    },
    campo: {
      ler: () => el.campoTexto.value,
      escrever: (texto) => {
        el.campoTexto.value = texto;
      },
    },
  });

  function tratarResultadoEnvio(resultado) {
    if (resultado.enviado) {
      mostrarTexto(el.erroEnvio, '');
      return;
    }
    switch (resultado.motivo) {
      case MotivoNaoEnviado.EM_BRANCO:
        break;
      case MotivoNaoEnviado.MUITO_LONGO:
        mostrarTexto(el.erroEnvio, `Mensagem muito longa (máximo de ${MAXIMO_PONTOS_DE_CODIGO} caracteres).`);
        break;
      case MotivoNaoEnviado.FIGURINHA_INVALIDA:
        mostrarTexto(el.erroEnvio, 'Figurinha inválida.');
        break;
      default:
        if (resultado.erro && !(resultado.erro instanceof ErroRequisicao)) reportar(resultado.erro);
        mostrarTexto(el.erroEnvio, mensagemDeErro(resultado.erro));
    }
  }

  function aoAcionarEnviar() {
    return controlador.acionarEnviar().then(tratarResultadoEnvio, (e) => reportar(e));
  }

  function aoEscolherEmoji(emoji) {
    const campo = el.campoTexto;
    const tamanho = campo.value.length;
    const posicao = Number.isInteger(campo.selectionStart) ? Math.min(campo.selectionStart, tamanho) : tamanho;
    const nova = controlador.inserirEmojiNoCampo(emoji, posicao);
    campo.focus();
    campo.setSelectionRange?.(nova, nova);
  }

  function aoEscolherFigurinha(id) {
    el.seletorFigurinhas.hidden = true;
    controlador.enviarFigurinha(id).then(tratarResultadoEnvio, (e) => reportar(e));
  }

  function construirGradeEmojis(emojis) {
    const grade = el.seletorEmojis;
    grade.style.display = 'grid';
    grade.style.setProperty('grid-template-columns', `repeat(${COLUNAS_EMOJIS}, 1fr)`);
    const botoes = emojis.map((emoji) => {
      const b = doc.createElement('button');
      b.type = 'button';
      b.textContent = emoji;
      b.setAttribute('aria-label', `Inserir ${emoji}`);
      b.addEventListener('click', () => aoEscolherEmoji(emoji));
      return b;
    });
    grade.replaceChildren(...botoes);
  }

  function construirGradeFigurinhas() {
    const botoes = [];
    for (let n = 1; n <= TOTAL_FIGURINHAS; n += 1) {
      const id = idFigurinhaNumero(n);
      const b = doc.createElement('button');
      b.type = 'button';
      b.dataset.figurinha = id;
      b.setAttribute('aria-label', `Enviar figurinha ${id}`);
      const img = doc.createElement('img');
      img.alt = id;
      img.src = urlRecurso(`figurinhas/${id}.png`);
      img.addEventListener('error', () => img.replaceWith(doc.createTextNode(`[figurinha ${id}]`)), { once: true });
      b.append(img);
      b.addEventListener('click', () => aoEscolherFigurinha(id));
      botoes.push(b);
    }
    el.seletorFigurinhas.replaceChildren(...botoes);
  }

  function construirAvatares() {
    const botoes = [];
    for (let n = 1; n <= TOTAL_AVATARES; n += 1) {
      const id = idAvatar(n);
      const b = doc.createElement('button');
      b.type = 'button';
      b.dataset.avatar = id;
      b.setAttribute('aria-pressed', String(id === avatarEscolhido));
      b.setAttribute('aria-label', `Avatar ${id}`);
      const img = doc.createElement('img');
      img.alt = id;
      img.src = urlRecurso(`avatares/${id}.png`);
      img.addEventListener('error', () => img.replaceWith(doc.createTextNode(`[avatar ${id}]`)), { once: true });
      b.append(img);
      b.addEventListener('click', () => {
        avatarEscolhido = id;
        for (const outro of botoes) outro.setAttribute('aria-pressed', String(outro === b));
      });
      botoes.push(b);
    }
    el.listaAvatares.replaceChildren(...botoes);
  }

  // ----- Recursos (emojis.json e emoticons.json) ----------------------------

  async function carregarRecursos() {
    try {
      const resposta = await buscar(urlRecurso('emojis.json'));
      if (!resposta.ok) throw new Error(`Falha ao carregar emojis.json (HTTP ${resposta.status})`);
      const emojis = await resposta.json();
      if (!Array.isArray(emojis) || !emojis.every((e) => typeof e === 'string')) {
        throw new TypeError('emojis.json fora do formato esperado');
      }
      construirGradeEmojis(emojis);
    } catch (e) {
      el.seletorEmojis.replaceChildren(doc.createTextNode('Emojis indisponíveis no momento.'));
      reportar(e, 'main.js');
    }
    try {
      tabela = await carregarTabela(urlRecurso('emoticons.json'), buscar);
    } catch (e) {
      reportar(e, 'emoticons.js'); // sem tabela, o texto segue sem conversao
    }
  }

  // ----- Ligacao dos eventos ------------------------------------------------

  el.formLogin.addEventListener('submit', (e) => {
    aoEnviarLogin(e).catch((erro) => reportar(erro));
  });
  el.formCadastro.addEventListener('submit', (e) => {
    aoEnviarCadastro(e).catch((erro) => reportar(erro));
  });
  el.botaoIrCadastro.addEventListener('click', () => {
    el.cadChave.value = el.campoChave.value;
    mostrarTexto(el.erroCadastro, '');
    mostrarTela('cadastro');
  });
  el.botaoIrLogin.addEventListener('click', () => {
    el.campoChave.value = el.cadChave.value || el.campoChave.value;
    mostrarTela('login');
  });
  el.botaoSair.addEventListener('click', () => {
    aoSair();
  });
  el.botaoReconectar.addEventListener('click', () => {
    el.botaoReconectar.hidden = true;
    conexao?.tentarNovamente();
  });

  // Botao Enviar (submit do formulario) e Enter chamam a mesma funcao do controlador.
  el.formEnvio.addEventListener('submit', (e) => {
    e.preventDefault();
    aoAcionarEnviar();
  });
  el.campoTexto.addEventListener('keydown', (e) => {
    const resultado = controlador.aoTeclar(e);
    if (resultado) resultado.then(tratarResultadoEnvio, (erro) => reportar(erro));
  });
  el.botaoEmojis.addEventListener('click', () => {
    el.seletorFigurinhas.hidden = true;
    el.seletorEmojis.hidden = !el.seletorEmojis.hidden;
  });
  el.botaoFigurinhas.addEventListener('click', () => {
    el.seletorEmojis.hidden = true;
    el.seletorFigurinhas.hidden = !el.seletorFigurinhas.hidden;
  });

  // ----- Inicializacao ------------------------------------------------------

  construirAvatares();
  construirGradeFigurinhas();
  const chaveGuardada = lerChaveTransito(storage);
  if (chaveGuardada) {
    if (!el.campoChave.value) el.campoChave.value = chaveGuardada;
    el.cadChave.value = el.campoChave.value;
  }
  mostrarTela('login');
  atualizarEstado('');

  const pronto = carregarRecursos();

  return {
    pronto,
    reportador,
    conexao: () => conexao,
    encerrar() {
      reportador.parar?.();
      desinstalarCaptura();
      conexao?.fechar();
      sessao?.historico?.fechar();
    },
  };
}

// Inicializacao automatica no navegador (o index.html carrega este modulo).
// Os testes definem `data-sem-autoinicio` ou chamam `iniciarApp` com outro documento.
if (
  typeof document !== 'undefined' &&
  document.getElementById('tela-login') &&
  !document.documentElement.hasAttribute('data-sem-autoinicio')
) {
  iniciarApp();
}
