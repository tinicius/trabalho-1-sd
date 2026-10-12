"""Conversao de emoticons e carga da Tabela_de_Conversao.

A tabela vem de recursos/emoticons.json (lista de {"emoticon", "emoji"}),
fonte unica dos tres Clientes. Algoritmo: percorre o texto da esquerda para a
direita; em cada posicao tenta os emoticons do mais longo ao mais curto. Exige
fronteira: inicio do texto ou espaco em branco antes; fim do texto, espaco em
branco ou um de ``. , ; ! ?`` depois.

"Espaco em branco" e a propriedade Unicode White_Space, enumerada
explicitamente (Java, JavaScript e Python usam o mesmo conjunto; ``str.isspace``
nao serve porque inclui U+001C a U+001F).
"""

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Mapping, Tuple, Union

_PONTUACAO_FINAL = ".,;!?"

# Unicode White_Space: 0009-000D, 0020, 0085, 00A0, 1680, 2000-200A, 2028,
# 2029, 202F, 205F, 3000.
_ESPACOS = frozenset(
    [chr(c) for c in range(0x0009, 0x000E)]
    + [chr(c) for c in range(0x2000, 0x200B)]
    + ["\u0020", "\u0085", "\u00a0", "\u1680", "\u2028", "\u2029", "\u202f", "\u205f", "\u3000"]
)


def eh_espaco(c: str) -> bool:
    return c in _ESPACOS


@dataclass(frozen=True)
class Par:
    emoticon: str
    emoji: str


class TabelaConversao:
    """Tabela imutavel; os pares ficam em ordem decrescente de comprimento do emoticon."""

    def __init__(self, pares: Iterable[Union[Par, Mapping[str, str]]]):
        vistos = set()
        lista = []
        for p in pares:
            if isinstance(p, Mapping):
                emoticon, emoji = p.get("emoticon"), p.get("emoji")
            elif isinstance(p, Par):
                emoticon, emoji = p.emoticon, p.emoji
            else:
                raise ValueError("Par invalido na Tabela_de_Conversao")
            if not isinstance(emoticon, str) or not isinstance(emoji, str) or emoticon == "":
                raise ValueError("Par invalido na Tabela_de_Conversao")
            if emoticon not in vistos:  # emoticon repetido: vale o primeiro
                vistos.add(emoticon)
                lista.append(Par(emoticon, emoji))
        # sorted e estavel: mesmo tamanho mantem a ordem do arquivo.
        self._pares: Tuple[Par, ...] = tuple(sorted(lista, key=lambda p: -len(p.emoticon)))

    @property
    def pares(self) -> Tuple[Par, ...]:
        return self._pares

    @classmethod
    def de_json(cls, conteudo: str) -> "TabelaConversao":
        """Le a tabela do conteudo JSON ja carregado (injetavel para testes)."""
        if not isinstance(conteudo, str):
            raise ValueError("Conteudo ausente")
        dados = json.loads(conteudo)
        if not isinstance(dados, list):
            raise ValueError("A Tabela_de_Conversao deve ser uma lista JSON")
        return cls(dados)

    @classmethod
    def carregar(cls, caminho: Union[str, Path]) -> "TabelaConversao":
        """Le a tabela de um arquivo JSON em UTF-8."""
        return cls.de_json(Path(caminho).read_text(encoding="utf-8"))


def _fronteira_depois(texto: str, fim: int) -> bool:
    if fim >= len(texto):
        return True
    c = texto[fim]
    return c in _ESPACOS or c in _PONTUACAO_FINAL


def converter(texto: str, tabela: TabelaConversao) -> str:
    """Converte os emoticons delimitados de `texto` em emojis."""
    if not isinstance(texto, str) or not isinstance(tabela, TabelaConversao):
        raise TypeError("Texto e tabela sao obrigatorios")
    n = len(texto)
    saida = []
    i = 0
    while i < n:
        achado = None
        if i == 0 or texto[i - 1] in _ESPACOS:
            for p in tabela.pares:
                if texto.startswith(p.emoticon, i) and _fronteira_depois(texto, i + len(p.emoticon)):
                    achado = p
                    break
        if achado is not None:
            saida.append(achado.emoji)
            i += len(achado.emoticon)
        else:
            saida.append(texto[i])
            i += 1
    return "".join(saida)
