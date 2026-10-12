"""Configuracao e recursos do Cliente_Terceiro.

Responsabilidades:
  * ler a Chave_Transito de ``<raiz>/.comunicador/chave_transito.txt`` (entrega
    manual, fora do socket). O valor da chave nunca aparece em
    mensagens de erro;
  * manter o identificador de dispositivo (UUID v4) em
    ``<raiz>/.comunicador/python/dispositivo.id``, gerado na primeira execucao;
  * carregar ``emoticons.json``, ``emojis.json`` e os PNG de ``recursos/``
    (fonte unica dos tres Clientes).

Este modulo nao depende de Tkinter: a GUI cria os ``PhotoImage`` a partir dos
caminhos/bytes aqui expostos.
"""

from __future__ import annotations

import json
import os
import re
import uuid
from pathlib import Path
from typing import Optional, Union

from comunicador.nucleo.emoticons import TabelaConversao

CAMINHO = Union[str, Path]

# .../<raiz>/cliente-python/comunicador/nucleo/config.py
RAIZ_PROJETO = Path(__file__).resolve().parents[3]

NOME_DIRETORIO_BASE = ".comunicador"
NOME_ARQUIVO_CHAVE = "chave_transito.txt"
NOME_SUBDIRETORIO_CLIENTE = "python"
NOME_ARQUIVO_DISPOSITIVO = "dispositivo.id"

VARIAVEL_RECURSOS = "COMUNICADOR_RECURSOS"

TAMANHO_CHAVE_BYTES = 32
TAMANHO_CHAVE_HEX = 2 * TAMANHO_CHAVE_BYTES

QUANTIDADE_EMOJIS = 48
QUANTIDADE_FIGURINHAS = 12
QUANTIDADE_AVATARES = 12

_RE_HEX = re.compile(r"[0-9a-fA-F]{64}")
_RE_FIGURINHA = re.compile(r"fig_[0-9]{2}")
_RE_AVATAR = re.compile(r"av_[0-9]{2}")
_ASSINATURA_PNG = b"\x89PNG\r\n\x1a\n"


class ErroDeConfiguracao(Exception):
    """Configuracao ausente ou invalida. A mensagem nunca inclui o valor da chave."""


# ---------------------------------------------------------------------------
# Diretorios
# ---------------------------------------------------------------------------
def diretorio_base(home: Optional[CAMINHO] = None) -> Path:
    """``<raiz do projeto>/.comunicador`` (ou ``<home>/.comunicador``, para testes)."""
    raiz = Path(home) if home is not None else RAIZ_PROJETO
    return raiz / NOME_DIRETORIO_BASE


def diretorio_cliente(home: Optional[CAMINHO] = None) -> Path:
    """``<raiz>/.comunicador/python``: dados locais do Cliente_Terceiro."""
    return diretorio_base(home) / NOME_SUBDIRETORIO_CLIENTE


def caminho_chave_transito(home: Optional[CAMINHO] = None) -> Path:
    return diretorio_base(home) / NOME_ARQUIVO_CHAVE


def caminho_dispositivo(home: Optional[CAMINHO] = None) -> Path:
    return diretorio_cliente(home) / NOME_ARQUIVO_DISPOSITIVO


def _criar_diretorio_privado(diretorio: Path) -> None:
    diretorio.mkdir(parents=True, exist_ok=True)
    try:
        os.chmod(diretorio, 0o700)
    except OSError:  # sistemas sem suporte a permissoes POSIX
        pass


# ---------------------------------------------------------------------------
# Chave_Transito
# ---------------------------------------------------------------------------
def chave_de_hex(texto: str) -> bytes:
    """Converte 64 caracteres hexadecimais em 32 bytes.

    Espacos e quebras de linha nas pontas sao ignorados (o arquivo copiado
    costuma terminar em ``\\n``). Qualquer outro desvio levanta
    ``ErroDeConfiguracao`` sem ecoar o conteudo recebido.
    """
    if not isinstance(texto, str):
        raise ErroDeConfiguracao("Chave_Transito ausente: esperados 64 caracteres hexadecimais")
    limpo = texto.strip()
    if _RE_HEX.fullmatch(limpo) is None:
        raise ErroDeConfiguracao(
            "Chave_Transito invalida: esperados 64 caracteres hexadecimais (0-9, a-f)"
        )
    return bytes.fromhex(limpo)


def ler_chave_transito(caminho: Optional[CAMINHO] = None) -> bytes:
    """Le a Chave_Transito (32 bytes) de ``<raiz>/.comunicador/chave_transito.txt``.

    ``caminho`` permite apontar para outro arquivo (testes). A chave nunca
    trafega pelo socket: ela deve ser copiada manualmente junto com a
    instalacao.
    """
    arquivo = Path(caminho) if caminho is not None else caminho_chave_transito()
    try:
        texto = arquivo.read_text(encoding="ascii")
    except FileNotFoundError:
        raise ErroDeConfiguracao(
            f"Chave_Transito nao encontrada em {arquivo}. Copie o arquivo "
            "gerado por scripts/gerar_chave.py para esse local."
        ) from None
    except (OSError, UnicodeDecodeError):
        raise ErroDeConfiguracao(f"Nao foi possivel ler a Chave_Transito em {arquivo}") from None
    return chave_de_hex(texto)


# ---------------------------------------------------------------------------
# Identificador de dispositivo
# ---------------------------------------------------------------------------
def _uuid_v4_valido(texto: str) -> Optional[str]:
    try:
        u = uuid.UUID(texto.strip())
    except (ValueError, AttributeError):
        return None
    return str(u) if u.version == 4 else None


def obter_id_dispositivo(caminho: Optional[CAMINHO] = None) -> str:
    """Devolve o UUID v4 do dispositivo, criando-o na primeira execucao.

    Arquivo padrao: ``<raiz>/.comunicador/python/dispositivo.id``. Um conteudo
    invalido (corrompido) e substituido por um novo identificador.
    """
    arquivo = Path(caminho) if caminho is not None else caminho_dispositivo()
    try:
        existente = _uuid_v4_valido(arquivo.read_text(encoding="ascii"))
    except (OSError, UnicodeDecodeError):
        existente = None
    if existente is not None:
        return existente
    novo = str(uuid.uuid4())
    try:
        _criar_diretorio_privado(arquivo.parent)
        temporario = arquivo.with_name(arquivo.name + ".tmp")
        temporario.write_text(novo + "\n", encoding="ascii")
        os.replace(temporario, arquivo)
    except OSError:
        raise ErroDeConfiguracao(
            f"Nao foi possivel gravar o identificador de dispositivo em {arquivo}"
        ) from None
    return novo


# ---------------------------------------------------------------------------
# Recursos compartilhados (recursos/)
# ---------------------------------------------------------------------------
def diretorio_recursos(explicito: Optional[CAMINHO] = None) -> Path:
    """Localiza a pasta ``recursos/``.

    Ordem: argumento explicito, variavel ``COMUNICADOR_RECURSOS``, pasta
    ``recursos/`` na raiz do repositorio (dois niveis acima de ``cliente-python``).
    """
    if explicito is not None:
        candidatos = [Path(explicito)]
    else:
        candidatos = []
        ambiente = os.environ.get(VARIAVEL_RECURSOS)
        if ambiente:
            candidatos.append(Path(ambiente))
        # .../<raiz>/cliente-python/comunicador/nucleo/config.py
        candidatos.append(Path(__file__).resolve().parents[3] / "recursos")
    for c in candidatos:
        if c.is_dir():
            return c
    raise ErroDeConfiguracao(
        f"Pasta de recursos nao encontrada (defina {VARIAVEL_RECURSOS} ou execute a partir do repositorio)"
    )


def carregar_tabela_emoticons(recursos: Optional[CAMINHO] = None) -> TabelaConversao:
    """Carrega a Tabela_de_Conversao de ``recursos/emoticons.json``."""
    arquivo = diretorio_recursos(recursos) / "emoticons.json"
    try:
        return TabelaConversao.carregar(arquivo)
    except (OSError, ValueError) as e:  # json.JSONDecodeError e subclasse de ValueError
        raise ErroDeConfiguracao(f"emoticons.json invalido ou ilegivel: {type(e).__name__}") from None


def carregar_emojis(recursos: Optional[CAMINHO] = None) -> tuple[str, ...]:
    """Carrega os 48 emojis (6 linhas x 8 colunas, ordem fixa) de ``emojis.json``."""
    arquivo = diretorio_recursos(recursos) / "emojis.json"
    try:
        dados = json.loads(arquivo.read_text(encoding="utf-8"))
    except (OSError, ValueError) as e:
        raise ErroDeConfiguracao(f"emojis.json invalido ou ilegivel: {type(e).__name__}") from None
    if (
        not isinstance(dados, list)
        or len(dados) != QUANTIDADE_EMOJIS
        or not all(isinstance(x, str) and x != "" for x in dados)
    ):
        raise ErroDeConfiguracao(f"emojis.json deve ser uma lista de {QUANTIDADE_EMOJIS} textos nao vazios")
    return tuple(dados)


def _listar_pngs(recursos: Optional[CAMINHO], subpasta: str, padrao: re.Pattern) -> tuple[str, ...]:
    pasta = diretorio_recursos(recursos) / subpasta
    if not pasta.is_dir():
        raise ErroDeConfiguracao(f"Pasta {subpasta}/ nao encontrada em recursos/")
    ids = sorted(p.stem for p in pasta.glob("*.png") if padrao.fullmatch(p.stem))
    return tuple(ids)


def listar_figurinhas(recursos: Optional[CAMINHO] = None) -> tuple[str, ...]:
    """Identificadores ``fig_NN`` disponiveis em ``recursos/figurinhas/``, em ordem."""
    return _listar_pngs(recursos, "figurinhas", _RE_FIGURINHA)


def listar_avatares(recursos: Optional[CAMINHO] = None) -> tuple[str, ...]:
    """Identificadores ``av_NN`` disponiveis em ``recursos/avatares/``, em ordem."""
    return _listar_pngs(recursos, "avatares", _RE_AVATAR)


def caminho_figurinha(identificador: str, recursos: Optional[CAMINHO] = None) -> Path:
    """Caminho do PNG da figurinha ``fig_NN`` (o identificador e validado)."""
    if not isinstance(identificador, str) or _RE_FIGURINHA.fullmatch(identificador) is None:
        raise ErroDeConfiguracao("Identificador de figurinha invalido")
    return diretorio_recursos(recursos) / "figurinhas" / f"{identificador}.png"


def caminho_avatar(identificador: str, recursos: Optional[CAMINHO] = None) -> Path:
    """Caminho do PNG do avatar ``av_NN`` (o identificador e validado)."""
    if not isinstance(identificador, str) or _RE_AVATAR.fullmatch(identificador) is None:
        raise ErroDeConfiguracao("Identificador de avatar invalido")
    return diretorio_recursos(recursos) / "avatares" / f"{identificador}.png"


def ler_png(caminho: CAMINHO) -> bytes:
    """Le um PNG e confere a assinatura. Falha com ``ErroDeConfiguracao``.

    A GUI usa os bytes (``PhotoImage(data=...)``); se falhar, exibe a
    representacao textual alternativa.
    """
    try:
        dados = Path(caminho).read_bytes()
    except OSError:
        raise ErroDeConfiguracao("Imagem nao encontrada ou ilegivel") from None
    if not dados.startswith(_ASSINATURA_PNG):
        raise ErroDeConfiguracao("Arquivo de imagem nao e um PNG valido")
    return dados
