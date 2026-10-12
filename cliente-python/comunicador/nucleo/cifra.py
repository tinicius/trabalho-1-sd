"""Cifra_Manual: cifra de fluxo com MAC manual (encrypt-then-MAC).

Implementacao de referencia em Python. Java e JavaScript
implementam o mesmo algoritmo.

Somente aritmetica de inteiros de 32 bits sem sinal (soma, XOR, rotacao,
multiplicacao modulo 2^32). Nenhuma biblioteca de cifragem e usada: o modulo
importa apenas `base64` (transporte textual), `binascii` (excecao do Base64)
e `os.urandom` (nonce).

Formato dos bytes cifrados:
    versao (1 byte, 0x01) | nonce (8) | texto cifrado (n) | tag (8)
saida = Base64 padrao com preenchimento '='.

Limitacoes declaradas: gerador pseudoaleatorio comum, MAC caseiro
de 64 bits, tamanho do texto preservado, sem troca de chaves. Tecnica
didatica, insuficiente para uso real.
"""

import base64
import binascii
import os

VERSAO = 0x01
TAMANHO_CHAVE = 32
TAMANHO_NONCE = 8
TAMANHO_TAG = 8
TAMANHO_MINIMO = 1 + TAMANHO_NONCE + TAMANHO_TAG  # 17 bytes (texto vazio)

_M32 = 0xFFFFFFFF


class DecifragemFalhou(Exception):
    """Falha ao decifrar: Base64 invalido, versao desconhecida, MAC invalido
    ou UTF-8 invalido. A mensagem e sempre fixa e nunca inclui dados."""


# ---------------------------------------------------------------------------
# Primitivas de 32 bits
# ---------------------------------------------------------------------------

def _rotl(x: int, k: int) -> int:
    return ((x << k) | (x >> (32 - k))) & _M32


def _imul(a: int, b: int) -> int:
    return (a * b) & _M32


# ---------------------------------------------------------------------------
# Passos A e B
# ---------------------------------------------------------------------------

def absorve(h: list, dados: bytes) -> None:
    """Passo A: mistura `dados` no estado `h` de quatro palavras (in place)."""
    for i, byte in enumerate(dados):
        j = i & 3
        x = h[j] ^ byte
        x = _rotl(x, 5)
        x = _imul(x, 0x9E3779B1)
        h[j] = x
        k = (j + 1) & 3
        h[k] = (h[k] + (x >> 13)) & _M32


def mistura(h: list) -> None:
    """Passo B: difusao final, 8 rodadas (in place)."""
    for _ in range(8):
        for j in range(4):
            x = h[j] ^ (h[(j + 3) & 3] >> 16)
            x = _imul(x, 0x85EBCA6B)
            x = _rotl(x, 13) ^ h[(j + 1) & 3]
            h[j] = x


# ---------------------------------------------------------------------------
# Passo C: gerador de keystream
# ---------------------------------------------------------------------------

class _Gerador:
    """Gerador xorshift com saida misturada, semeado por chave || nonce."""

    def __init__(self, chave: bytes, nonce: bytes) -> None:
        h = [0x243F6A88, 0x85A308D3, 0x13198A2E, 0x03707344]
        absorve(h, chave + nonce)
        mistura(h)
        if h[0] == 0 and h[1] == 0 and h[2] == 0 and h[3] == 0:
            h[0] = 1
        self._s = h
        for _ in range(8):  # aquecimento
            self.proximo()

    def proximo(self) -> int:
        s = self._s
        r = _imul(_rotl(_imul(s[1], 5), 7), 9)
        t = (s[1] << 9) & _M32
        s[2] ^= s[0]
        s[3] ^= s[1]
        s[1] ^= s[2]
        s[0] ^= s[3]
        s[2] ^= t
        s[3] = _rotl(s[3], 11)
        return r


def _keystream(chave: bytes, nonce: bytes, tamanho: int) -> bytes:
    """Concatena saidas de proximo() em 4 bytes little-endian, truncado."""
    gerador = _Gerador(chave, nonce)
    saida = bytearray()
    while len(saida) < tamanho:
        saida += gerador.proximo().to_bytes(4, "little")
    return bytes(saida[:tamanho])


# ---------------------------------------------------------------------------
# Passo D: MAC manual de 8 bytes
# ---------------------------------------------------------------------------

def _mac(chave: bytes, versao: int, nonce: bytes, ct: bytes) -> bytes:
    h = [0x452821E6, 0x38D01377, 0xBE5466CF, 0x34E90C6C]
    absorve(h, b"\x4d" + chave)  # 0x4D = 'M', separa dominios
    absorve(h, bytes([versao]) + nonce + ct)
    absorve(h, len(ct).to_bytes(4, "big"))
    mistura(h)
    return ((h[0] ^ h[2]).to_bytes(4, "big")) + ((h[1] ^ h[3]).to_bytes(4, "big"))


def _xor(a: bytes, b: bytes) -> bytes:
    return bytes(x ^ y for x, y in zip(a, b))


def _validar_chave(chave: bytes) -> None:
    if not isinstance(chave, (bytes, bytearray)) or len(chave) != TAMANHO_CHAVE:
        raise ValueError("chave deve ter 32 bytes")


# ---------------------------------------------------------------------------
# API publica
# ---------------------------------------------------------------------------

def cifrar_com_nonce(chave: bytes, nonce: bytes, texto: str) -> str:
    """Cifra `texto` com o `nonce` fornecido.

    Retorna Base64 de `0x01 || nonce || ct || tag`.
    """
    _validar_chave(chave)
    if not isinstance(nonce, (bytes, bytearray)) or len(nonce) != TAMANHO_NONCE:
        raise ValueError("nonce deve ter 8 bytes")
    chave = bytes(chave)
    nonce = bytes(nonce)
    pt = texto.encode("utf-8")
    ct = _xor(pt, _keystream(chave, nonce, len(pt)))
    tag = _mac(chave, VERSAO, nonce, ct)
    return base64.b64encode(bytes([VERSAO]) + nonce + ct + tag).decode("ascii")


def cifrar(chave: bytes, texto: str) -> str:
    """Cifra `texto` com um nonce aleatorio de 8 bytes (os.urandom)."""
    return cifrar_com_nonce(chave, os.urandom(TAMANHO_NONCE), texto)


def _base64_estrito(b64: str) -> bytes:
    """Decodifica Base64 padrao rejeitando alfabeto invalido, quebras de linha,
    preenchimento incorreto e bits residuais nao canonicos."""
    if not isinstance(b64, str):
        raise DecifragemFalhou("base64 invalido")
    try:
        bruto = b64.encode("ascii")
        dados = base64.b64decode(bruto, validate=True)
    except (UnicodeEncodeError, binascii.Error, ValueError):
        raise DecifragemFalhou("base64 invalido") from None
    if base64.b64encode(dados) != bruto:  # forma canonica
        raise DecifragemFalhou("base64 invalido")
    return dados


def decifrar(chave: bytes, b64: str) -> str:
    """Decifra o resultado de `cifrar`. Lanca DecifragemFalhou em qualquer falha."""
    _validar_chave(chave)
    chave = bytes(chave)
    dados = _base64_estrito(b64)
    if len(dados) < TAMANHO_MINIMO:
        raise DecifragemFalhou("tamanho invalido")
    if dados[0] != VERSAO:
        raise DecifragemFalhou("versao desconhecida")
    nonce = dados[1:1 + TAMANHO_NONCE]
    ct = dados[1 + TAMANHO_NONCE:-TAMANHO_TAG]
    tag = dados[-TAMANHO_TAG:]

    esperado = _mac(chave, dados[0], nonce, ct)
    diferenca = 0
    for a, b in zip(esperado, tag):  # tempo constante: acumula XOR
        diferenca |= a ^ b
    if diferenca != 0:
        raise DecifragemFalhou("falha de MAC")

    pt = _xor(ct, _keystream(chave, nonce, len(ct)))
    try:
        return pt.decode("utf-8")  # estrito por padrao
    except UnicodeDecodeError:
        raise DecifragemFalhou("utf-8 invalido") from None
