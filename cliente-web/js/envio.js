// Controlador de envio e insercao de emoji do Cliente_Web (sem DOM).
//
// - O botao Enviar e a tecla Enter chamam a MESMA funcao (`acionarEnviar`).
// - Texto vazio ou so de espacos em branco Unicode: nao envia e nao altera
//   nenhum estado (nem o campo de texto).
// - Texto valido: converte emoticons (Tabela_de_Conversao), entrega a
//   `enviar('texto', conteudo)` (ConexaoWeb.enviarMensagem, que cifra o
//   `conteudo` com a Chave_Transito) e limpa o campo.
// - Figurinha: envia `enviar('figurinha', 'fig_NN')` sem tocar no campo.
// - `inserirEmoji` e funcao pura: antes + emoji + depois.
//
// Nada aqui depende de DOM, rede ou relogio: o campo e a funcao de envio sao
// injetados, o que permite testar o controlador sem interface.

import { converter, ehEspaco } from './emoticons.js';

/** Tamanho maximo do texto, em pontos de codigo (limite do Middleware). */
export const MAXIMO_PONTOS_DE_CODIGO = 2000;

/** Formato aceito para o identificador de uma figurinha. */
export const PADRAO_FIGURINHA = /^fig_[0-9]{2}$/;

/** Motivos pelos quais um acionamento nao resultou em envio. */
export const MotivoNaoEnviado = Object.freeze({
  EM_BRANCO: 'em_branco',
  MUITO_LONGO: 'muito_longo',
  FIGURINHA_INVALIDA: 'figurinha_invalida',
  FALHA: 'falha',
});

// ---------------------------------------------------------------------------
// Funcoes puras
// ---------------------------------------------------------------------------

/**
 * `true` se `texto` e vazio ou composto somente de espacos em branco Unicode
 * (propriedade White_Space, a mesma usada na conversao de emoticons).
 *
 * @param {string} texto
 * @returns {boolean}
 */
export function ehEmBranco(texto) {
  if (typeof texto !== 'string') {
    throw new TypeError('O texto deve ser uma string');
  }
  for (let i = 0; i < texto.length; i += 1) {
    if (!ehEspaco(texto[i])) return false;
  }
  return true;
}

/** Quantidade de pontos de codigo Unicode de `texto`. */
export function contarPontosDeCodigo(texto) {
  let n = 0;
  for (const _ of texto) n += 1; // eslint-disable-line no-unused-vars
  return n;
}

/**
 * Insere `emoji` em `texto` na `posicao` do cursor.
 * `posicao` e um indice de unidade UTF-16 em [0, texto.length], como
 * `selectionStart` de um campo de texto. Resultado: `antes + emoji + depois`,
 * com `antes = texto.slice(0, posicao)` e `depois = texto.slice(posicao)`.
 *
 * @param {string} texto
 * @param {number} posicao
 * @param {string} emoji
 * @returns {string}
 */
export function inserirEmoji(texto, posicao, emoji) {
  if (typeof texto !== 'string' || typeof emoji !== 'string') {
    throw new TypeError('Texto e emoji devem ser strings');
  }
  if (!Number.isInteger(posicao) || posicao < 0 || posicao > texto.length) {
    throw new RangeError('Posicao do cursor fora do texto');
  }
  return texto.slice(0, posicao) + emoji + texto.slice(posicao);
}

/**
 * Como `inserirEmoji`, mas tambem devolve a nova posicao do cursor (logo apos
 * o emoji), para a interface reposicionar o cursor.
 *
 * @returns {{texto: string, posicao: number}}
 */
export function inserirEmojiComCursor(texto, posicao, emoji) {
  const novo = inserirEmoji(texto, posicao, emoji);
  return { texto: novo, posicao: posicao + emoji.length };
}

/** `true` se `id` tem o formato `fig_NN`. */
export function ehIdFigurinha(id) {
  return typeof id === 'string' && PADRAO_FIGURINHA.test(id);
}

// ---------------------------------------------------------------------------
// Controlador
// ---------------------------------------------------------------------------

/**
 * Controlador de envio.
 *
 * @typedef {object} CampoDeTexto
 * @property {() => string} ler devolve o texto atual do campo
 * @property {(texto: string) => void} escrever substitui o texto do campo
 *
 * @typedef {object} ResultadoEnvio
 * @property {boolean} enviado
 * @property {string} [motivo] valor de MotivoNaoEnviado quando `enviado` e falso
 * @property {*} [resposta] resposta do Middleware quando `enviado` e verdadeiro
 * @property {*} [erro] erro de `enviar` quando o motivo e FALHA
 */
export class ControladorEnvio {
  #tabela;
  #enviar;
  #campo;

  /**
   * @param {object} opcoes
   * @param {{pares: ReadonlyArray<{emoticon: string, emoji: string}>}} opcoes.tabela Tabela_de_Conversao (emoticons.js)
   * @param {(tipo: 'texto'|'figurinha', conteudo: string) => Promise<*>} opcoes.enviar
   *        normalmente `(t, c) => conexao.enviarMensagem(t, c)`; cifra `conteudo`
   * @param {CampoDeTexto} opcoes.campo
   */
  constructor({ tabela, enviar, campo }) {
    if (!tabela || typeof enviar !== 'function' || !campo) {
      throw new TypeError('tabela, enviar e campo sao obrigatorios');
    }
    this.#tabela = tabela;
    this.#enviar = enviar;
    this.#campo = campo;
  }

  /**
   * Acao unica do botao Enviar e da tecla Enter.
   * Texto em branco: devolve `{enviado:false, motivo:'em_branco'}` sem chamar
   * `enviar` e sem escrever no campo.
   *
   * @returns {Promise<ResultadoEnvio>}
   */
  async acionarEnviar() {
    const original = this.#campo.ler();
    if (ehEmBranco(original)) {
      return { enviado: false, motivo: MotivoNaoEnviado.EM_BRANCO };
    }

    const conteudo = converter(original, this.#tabela);
    if (contarPontosDeCodigo(conteudo) > MAXIMO_PONTOS_DE_CODIGO) {
      // Manter o campo intacto para o usuario encurtar o texto.
      return { enviado: false, motivo: MotivoNaoEnviado.MUITO_LONGO };
    }

    // Limpa antes de aguardar a resposta: um segundo Enter nao reenvia o mesmo texto.
    this.#campo.escrever('');
    try {
      const resposta = await this.#enviar('texto', conteudo);
      return { enviado: true, resposta };
    } catch (erro) {
      // Devolve o texto digitado, a menos que o usuario ja tenha digitado outra coisa.
      if (this.#campo.ler() === '') this.#campo.escrever(original);
      return { enviado: false, motivo: MotivoNaoEnviado.FALHA, erro };
    }
  }

  /**
   * Manipulador de teclado do campo de texto: Enter (fora de composicao IME)
   * aciona o mesmo envio do botao. Devolve `null` se a tecla nao e tratada.
   *
   * @param {{key: string, isComposing?: boolean, preventDefault?: () => void}} evento
   * @returns {Promise<ResultadoEnvio>|null}
   */
  aoTeclar(evento) {
    if (evento?.key !== 'Enter' || evento.isComposing) return null;
    evento.preventDefault?.();
    return this.acionarEnviar();
  }

  /**
   * Envia uma figurinha pelo identificador (`fig_NN`), sem alterar o campo
   * de texto.
   *
   * @param {string} id
   * @returns {Promise<ResultadoEnvio>}
   */
  async enviarFigurinha(id) {
    if (!ehIdFigurinha(id)) {
      return { enviado: false, motivo: MotivoNaoEnviado.FIGURINHA_INVALIDA };
    }
    try {
      const resposta = await this.#enviar('figurinha', id);
      return { enviado: true, resposta };
    } catch (erro) {
      return { enviado: false, motivo: MotivoNaoEnviado.FALHA, erro };
    }
  }

  /**
   * Insere `emoji` no campo na posicao do cursor (Seletor_de_Emojis)
   * e devolve a nova posicao do cursor. Independe da conversao de emoticons.
   *
   * @param {string} emoji
   * @param {number} posicao
   * @returns {number} nova posicao do cursor
   */
  inserirEmojiNoCampo(emoji, posicao) {
    const { texto, posicao: nova } = inserirEmojiComCursor(this.#campo.ler(), posicao, emoji);
    this.#campo.escrever(texto);
    return nova;
  }
}
