// Mascaramento de segredos (JavaScript).
//
// Regras:
//  1. O valor que segue senha, password, pwd, chave, key, token ou secret nas
//     formas `nome=valor`, `nome: valor` e `"nome":"valor"` vira `***`.
//  2. Sequencias Base64 com 24 ou mais caracteres e hexadecimais com 32 ou mais
//     viram `[REDIGIDO]`.
// A funcao e idempotente e produz a mesma saida das versoes Java
// (Mascaramento.java) e Python (mascaramento.py): os espacos em branco sao
// listados explicitamente (ASCII) e o fim de texto e testado por lookahead.
//
// Limite conhecido: texto de conversa arbitrario dentro de uma excecao de
// biblioteca nao e reconhecido; a defesa e a convencao de que as excecoes do
// projeto nunca incluem mensagens nem senhas.

export const SEGREDO = '***';
export const REDIGIDO = '[REDIGIDO]';

// Espacos em branco ASCII: espaco, \t, \n, \r, \f e \v.
const ESPACOS = ' \\t\\n\\r\\f\\v';
const NOMES = 'senha|password|pwd|chave|key|token|secret';

// Forma JSON: grupo 1 = nome; 2 = `":"` com espacos; 3 = valor (curto, ate uma
// aspa seguida de fim de texto ou delimitador).
// Forma simples: 4 = nome; 5 = aspa opcional; 6 = separador; 7 = valor (ate o
// proximo espaco em branco).
const SEGREDO_NOMEADO = new RegExp(
  '(?:(' + NOMES + ')("[ \\t]*:[ \\t]*")([^\\n\\r]*?)"' +
    '(?![^' + ESPACOS + ',}\\]])' +
    '|(' + NOMES + ')(["\']?)([ \\t]*[=:][ \\t]*)([^' + ESPACOS + ']+))',
  'gi',
);
const HEXADECIMAL = /[0-9A-Fa-f]{32,}/g;
const BASE64 = /[A-Za-z0-9+/]{24,}={0,2}/g;

/**
 * Mascara segredos em `texto`.
 * @param {string|null|undefined} texto
 * @returns {string|null|undefined} texto mascarado (null/undefined voltam iguais)
 */
export function mascarar(texto) {
  if (texto === null || texto === undefined) {
    return texto;
  }
  let resultado = String(texto).replace(
    SEGREDO_NOMEADO,
    (_m, n1, sep1, _v1, n2, aspa, sep2) =>
      n1 !== undefined ? n1 + sep1 + SEGREDO + '"' : n2 + aspa + sep2 + SEGREDO,
  );
  resultado = resultado.replace(HEXADECIMAL, REDIGIDO);
  return resultado.replace(BASE64, REDIGIDO);
}
