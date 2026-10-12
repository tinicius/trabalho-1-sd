// Conversao de emoticons e carga da Tabela_de_Conversao (JavaScript).
//
// A tabela vem de recursos/emoticons.json (lista de {"emoticon","emoji"}),
// fonte unica dos tres Clientes. Algoritmo: percorre o texto da esquerda para
// a direita; em cada posicao tenta os emoticons do mais longo ao mais curto.
// Exige fronteira: inicio do texto ou espaco em branco antes; fim do texto,
// espaco em branco ou um de . , ; ! ? depois.
//
// "Espaco em branco" e a propriedade Unicode White_Space, enumerada
// explicitamente (Java, JavaScript e Python usam o mesmo conjunto).

/** Unicode White_Space: 0009-000D, 0020, 0085, 00A0, 1680, 2000-200A, 2028, 2029, 202F, 205F, 3000. */
export function ehEspaco(c) {
  const u = c.charCodeAt(0);
  return (
    (u >= 0x0009 && u <= 0x000d) ||
    u === 0x0020 ||
    u === 0x0085 ||
    u === 0x00a0 ||
    u === 0x1680 ||
    (u >= 0x2000 && u <= 0x200a) ||
    u === 0x2028 ||
    u === 0x2029 ||
    u === 0x202f ||
    u === 0x205f ||
    u === 0x3000
  );
}

const PONTUACAO_FINAL = '.,;!?';

/**
 * Monta a tabela a partir de uma lista de pares {emoticon, emoji}.
 * Ignora emoticons repetidos (vale o primeiro) e ordena do mais longo ao mais curto.
 * @returns {{pares: ReadonlyArray<{emoticon: string, emoji: string}>}}
 */
export function criarTabela(pares) {
  if (!Array.isArray(pares)) {
    throw new TypeError('A Tabela_de_Conversao deve ser uma lista');
  }
  const vistos = new Set();
  const lista = [];
  for (const p of pares) {
    if (
      p === null ||
      typeof p !== 'object' ||
      typeof p.emoticon !== 'string' ||
      typeof p.emoji !== 'string' ||
      p.emoticon.length === 0
    ) {
      throw new TypeError('Par invalido na Tabela_de_Conversao');
    }
    if (!vistos.has(p.emoticon)) {
      vistos.add(p.emoticon);
      lista.push(Object.freeze({ emoticon: p.emoticon, emoji: p.emoji }));
    }
  }
  // Array.prototype.sort e estavel: mesmo tamanho mantem a ordem do arquivo.
  lista.sort((a, b) => b.emoticon.length - a.emoticon.length);
  return Object.freeze({ pares: Object.freeze(lista) });
}

/** Le a tabela do conteudo JSON ja carregado (injetavel para testes). */
export function tabelaDeJson(conteudo) {
  if (typeof conteudo !== 'string') {
    throw new TypeError('Conteudo ausente');
  }
  return criarTabela(JSON.parse(conteudo));
}

/**
 * Carrega a tabela de uma URL (padrao: /recursos/emoticons.json).
 * `buscar` e injetavel (padrao: fetch) para testes.
 */
export async function carregarTabela(url = '/recursos/emoticons.json', buscar = globalThis.fetch) {
  const resposta = await buscar(url);
  if (!resposta.ok) {
    throw new Error(`Falha ao carregar a Tabela_de_Conversao (HTTP ${resposta.status})`);
  }
  return tabelaDeJson(await resposta.text());
}

function fronteiraDepois(texto, fim) {
  if (fim >= texto.length) return true;
  const c = texto[fim];
  return ehEspaco(c) || PONTUACAO_FINAL.includes(c);
}

/** Converte os emoticons delimitados de `texto` em emojis. */
export function converter(texto, tabela) {
  if (typeof texto !== 'string' || !tabela || !Array.isArray(tabela.pares)) {
    throw new TypeError('Texto e tabela sao obrigatorios');
  }
  const n = texto.length;
  let saida = '';
  let i = 0;
  while (i < n) {
    let achado = null;
    if (i === 0 || ehEspaco(texto[i - 1])) {
      for (const p of tabela.pares) {
        if (texto.startsWith(p.emoticon, i) && fronteiraDepois(texto, i + p.emoticon.length)) {
          achado = p;
          break;
        }
      }
    }
    if (achado !== null) {
      saida += achado.emoji;
      i += achado.emoticon.length;
    } else {
      saida += texto[i];
      i += 1;
    }
  }
  return saida;
}
