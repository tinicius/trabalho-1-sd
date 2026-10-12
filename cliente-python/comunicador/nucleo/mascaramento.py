"""Mascaramento de segredos (Python).

Regras:
 1. O valor que segue senha, password, pwd, chave, key, token ou secret nas
    formas `nome=valor`, `nome: valor` e `"nome":"valor"` vira `***`.
 2. Sequencias Base64 com 24 ou mais caracteres e hexadecimais com 32 ou mais
    viram `[REDIGIDO]`.

A funcao e idempotente e produz a mesma saida das versoes Java
(Mascaramento.java) e JavaScript (mascaramento.js): os espacos em branco sao
listados explicitamente (ASCII), a comparacao de maiusculas e ASCII e o fim de
texto e testado por lookahead (e nao por `$`).

Limite conhecido: texto de conversa arbitrario dentro de uma excecao de
biblioteca nao e reconhecido; a defesa e a convencao de que as excecoes do
projeto nunca incluem mensagens nem senhas.
"""
import re

SEGREDO = "***"
REDIGIDO = "[REDIGIDO]"

# Espacos em branco ASCII: espaco, \t, \n, \r, \f e \v.
_ESPACOS = " \\t\\n\\r\\f\\v"
_NOMES = "senha|password|pwd|chave|key|token|secret"

# Forma JSON: grupo 1 = nome; 2 = `":"` com espacos; 3 = valor (curto, ate uma
# aspa seguida de fim de texto ou delimitador).
# Forma simples: 4 = nome; 5 = aspa opcional; 6 = separador; 7 = valor (ate o
# proximo espaco em branco).
_SEGREDO_NOMEADO = re.compile(
    "(?:(" + _NOMES + ')("[ \\t]*:[ \\t]*")([^\\n\\r]*?)"'
    "(?![^" + _ESPACOS + ",}\\]])"
    "|(" + _NOMES + ")([\"']?)([ \\t]*[=:][ \\t]*)([^" + _ESPACOS + "]+))",
    re.ASCII | re.IGNORECASE,
)
_HEXADECIMAL = re.compile(r"[0-9A-Fa-f]{32,}")
_BASE64 = re.compile(r"[A-Za-z0-9+/]{24,}={0,2}")


def _substituir_segredo(m):
    if m.group(1) is not None:
        return m.group(1) + m.group(2) + SEGREDO + '"'
    return m.group(4) + m.group(5) + m.group(6) + SEGREDO


def mascarar(texto):
    """Mascara segredos em `texto`. `None` volta como `None`."""
    if texto is None:
        return None
    resultado = _SEGREDO_NOMEADO.sub(_substituir_segredo, texto)
    resultado = _HEXADECIMAL.sub(REDIGIDO, resultado)
    return _BASE64.sub(REDIGIDO, resultado)
