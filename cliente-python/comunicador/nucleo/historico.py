"""Historico_Local do Cliente_Terceiro em arquivo.

Mesma semantica de
``cliente-web/js/historico.js``:

* Um diretorio por usuario: ``<raiz>/.comunicador/python/<login>/``; cada
  dispositivo tem o proprio ``~/``. Nele ficam ``historico.txt`` (um
  JSON por linha, mesmo formato do Repositorio_Conversas, com ``conteudo``
  cifrado com a Chave_Historico) e ``historico.key`` (32 bytes
  aleatorios em hexadecimal, gerados neste dispositivo e nunca transmitidos).
* A Chave_Historico e distinta da Chave_Transito.
* Incorporacao idempotente por ``mid``: o conjunto de ``mid``
  e carregado na abertura e so os novos sao anexados. Duplicatas no mesmo lote:
  vale a primeira.
* Nada e removido automaticamente. Cada dispositivo recifra com a
  propria chave e um nonce novo.
* Exibicao ordenada por ``(ts, mid)``, nao pela ordem do arquivo. Linhas
  ilegiveis sao ignoradas; a ultima linha incompleta (sem ``\\n``, queda durante
  a escrita) tambem e ignorada e descartada antes do proximo anexo.
"""

from __future__ import annotations

import json
import os
import re
import secrets
import threading
from pathlib import Path
from typing import Any, Iterable, Mapping, NamedTuple, Optional, Union

from comunicador.nucleo import cifra
from comunicador.nucleo.config import (
    ErroDeConfiguracao,
    chave_de_hex,
    diretorio_cliente,
)

CAMINHO = Union[str, Path]

NOME_ARQUIVO_HISTORICO = "historico.txt"
NOME_ARQUIVO_CHAVE = "historico.key"

_RE_LOGIN = re.compile(r"[a-z0-9_]{3,20}")
_RE_CHAVE_HEX = re.compile(r"[0-9a-f]{64}")
_TIPOS = frozenset({"texto", "figurinha"})


class ErroHistorico(Exception):
    """Erro de uso ou de armazenamento do Historico_Local.

    A mensagem nunca inclui chaves nem conteudo de mensagens.
    """


class ResultadoIncorporacao(NamedTuple):
    adicionadas: int
    existentes: int
    ilegiveis: int
    invalidas: int


# ---------------------------------------------------------------------------
# Caminhos
# ---------------------------------------------------------------------------
def validar_login(login: str) -> str:
    """Confere o formato da conta (``^[a-z0-9_]{3,20}$``); evita sair do diretorio."""
    if not isinstance(login, str) or _RE_LOGIN.fullmatch(login) is None:
        raise ErroHistorico("Login invalido para o Historico_Local")
    return login


def diretorio_historico(login: str, home: Optional[CAMINHO] = None) -> Path:
    """``<raiz>/.comunicador/python/<login>``."""
    return diretorio_cliente(home) / validar_login(login)


# ---------------------------------------------------------------------------
# Auxiliares de arquivo
# ---------------------------------------------------------------------------
def _criar_diretorio_privado(diretorio: Path) -> None:
    diretorio.mkdir(parents=True, exist_ok=True)
    try:
        os.chmod(diretorio, 0o700)
    except OSError:  # sistemas sem permissoes POSIX
        pass


def _sortear_chave_hex() -> str:
    return secrets.token_bytes(cifra.TAMANHO_CHAVE).hex()


def _ler_chave_valida(arquivo: Path) -> Optional[str]:
    try:
        texto = arquivo.read_text(encoding="ascii").strip()
    except (OSError, UnicodeDecodeError):
        return None
    return texto if _RE_CHAVE_HEX.fullmatch(texto) else None


def _garantir_chave(arquivo: Path, chave_transito_hex: Optional[str]) -> bytes:
    """Le (ou cria, na primeira execucao) a Chave_Historico.

    A criacao e atomica e exclusiva (arquivo temporario + ``os.link``), para que
    dois processos abrindo ao mesmo tempo nao fiquem com chaves diferentes e
    ninguem leia um arquivo pela metade. Um conteudo invalido e substituido,
    como no Cliente_Web.
    """
    existente = _ler_chave_valida(arquivo)
    if existente is not None:
        return bytes.fromhex(existente)

    chave = _sortear_chave_hex()
    while chave == chave_transito_hex:  # deve diferir da Chave_Transito
        chave = _sortear_chave_hex()

    temporario = arquivo.with_name(f"{arquivo.name}.{os.getpid()}.{secrets.token_hex(4)}.tmp")
    try:
        descritor = os.open(temporario, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descritor, "w", encoding="ascii") as f:
            f.write(chave + "\n")
            f.flush()
            os.fsync(f.fileno())
        try:
            if arquivo.exists():
                # arquivo presente mas invalido: substitui
                os.replace(temporario, arquivo)
            else:
                try:
                    os.link(temporario, arquivo)  # falha se outro processo criou antes
                except FileExistsError:
                    outra = _ler_chave_valida(arquivo)
                    if outra is not None:
                        return bytes.fromhex(outra)
                    os.replace(temporario, arquivo)
                except OSError:  # sistema de arquivos sem hard link
                    os.replace(temporario, arquivo)
        finally:
            try:
                temporario.unlink()
            except OSError:
                pass
    except OSError:
        raise ErroHistorico("Nao foi possivel gravar a Chave_Historico") from None

    final = _ler_chave_valida(arquivo)
    if final is None:
        raise ErroHistorico("Nao foi possivel gravar a Chave_Historico")
    return bytes.fromhex(final)


# ---------------------------------------------------------------------------
# Validacao e ordenacao
# ---------------------------------------------------------------------------
def _eh_texto_nao_vazio(x: Any) -> bool:
    return isinstance(x, str) and len(x) > 0


def _mensagem_valida(m: Any) -> bool:
    return (
        isinstance(m, Mapping)
        and _eh_texto_nao_vazio(m.get("mid"))
        and _eh_texto_nao_vazio(m.get("ts"))
        and m.get("tipo") in _TIPOS
        and isinstance(m.get("remetente"), Mapping)
        and isinstance(m.get("conteudo"), str)
    )


def _copiar_remetente(r: Mapping[str, Any]) -> dict[str, Any]:
    return {
        "login": r.get("login"),
        "apelido": r.get("apelido"),
        "avatar": r.get("avatar"),
        "cor": r.get("cor"),
    }


def _chave_de_ordenacao(registro: Mapping[str, Any]) -> tuple[str, str]:
    return (registro["ts"], registro["mid"])


def _normalizar_chave_transito(chave: Union[bytes, bytearray, str]) -> bytes:
    if isinstance(chave, (bytes, bytearray)):
        if len(chave) != cifra.TAMANHO_CHAVE:
            raise ErroHistorico("Chave_Transito deve ter 32 bytes")
        return bytes(chave)
    try:
        return chave_de_hex(chave)
    except ErroDeConfiguracao:
        raise ErroHistorico("Chave_Transito invalida") from None


def _carregar_arquivo(arquivo: Path) -> tuple[dict[str, dict[str, Any]], int]:
    """Le o historico. Devolve ``(registros por mid, fim_valido)``.

    ``fim_valido`` e o deslocamento logo depois do ultimo ``\\n``; o que vem
    depois e uma linha incompleta, ignorada. Linhas ilegiveis ou invalidas sao
    ignoradas; em ``mid`` repetido vale a primeira.
    """
    try:
        dados = arquivo.read_bytes()
    except FileNotFoundError:
        return {}, 0
    except OSError:
        raise ErroHistorico("Falha ao ler o Historico_Local") from None

    fim_valido = dados.rfind(b"\n") + 1
    registros: dict[str, dict[str, Any]] = {}
    for bruta in dados[:fim_valido].split(b"\n"):
        if not bruta.strip():
            continue
        try:
            obj = json.loads(bruta.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            continue
        if not _mensagem_valida(obj) or obj["mid"] in registros:
            continue
        registros[obj["mid"]] = {
            "mid": obj["mid"],
            "remetente": _copiar_remetente(obj["remetente"]),
            "ts": obj["ts"],
            "tipo": obj["tipo"],
            "conteudo": obj["conteudo"],
        }
    return registros, fim_valido


# ---------------------------------------------------------------------------
# Historico_Local
# ---------------------------------------------------------------------------
class HistoricoLocal:
    """Historico de um usuario neste dispositivo. Seguro para uso entre threads."""

    def __init__(
        self,
        login: str,
        arquivo: Path,
        chave: bytes,
        registros: dict[str, dict[str, Any]],
        fim_valido: int,
    ) -> None:
        self._login = login
        self._arquivo = arquivo
        self._chave = chave
        self._registros = registros
        self._fim_valido = fim_valido
        self._trava = threading.RLock()

    # -- propriedades --------------------------------------------------------
    @property
    def login(self) -> str:
        return self._login

    @property
    def caminho(self) -> Path:
        """Caminho de ``historico.txt``."""
        return self._arquivo

    def __enter__(self) -> "HistoricoLocal":
        return self

    def __exit__(self, *_exc: object) -> None:
        self.fechar()

    def fechar(self) -> None:
        """Nada fica aberto entre operacoes; existe por simetria com o Cliente_Web."""

    # -- incorporacao --------------------------------------------------------
    def incorporar(
        self,
        mensagens: Iterable[Mapping[str, Any]],
        chave_transito: Union[bytes, bytearray, str, None] = None,
    ) -> ResultadoIncorporacao:
        """Incorpora mensagens ao historico, de forma idempotente por ``mid``.

        Cada mensagem tem ``{mid, remetente, ts, tipo, conteudo}``. Por padrao
        ``conteudo`` e o texto em claro. Com ``chave_transito``, ``conteudo`` e
        o texto cifrado com a Chave_Transito, como chega no quadro; se nao
        decifrar, a mensagem e contada em ``ilegiveis`` e nao e gravada.
        Mensagens com ``ilegivel: True`` tambem nao sao gravadas.

        Se ``mid`` ja existe (no arquivo ou antes, no mesmo lote), ignora. As
        novas sao recifradas com a Chave_Historico e um nonce novo e anexadas
        em uma unica escrita. Se a escrita falhar, nada e adicionado e
        ``ErroHistorico`` e levantado.
        """
        transito = _normalizar_chave_transito(chave_transito) if chave_transito is not None else None
        adicionadas = existentes = ilegiveis = invalidas = 0
        novos: list[dict[str, Any]] = []
        vistos: set[str] = set()

        with self._trava:
            for m in mensagens or ():
                if isinstance(m, Mapping) and m.get("ilegivel") is True:
                    ilegiveis += 1
                    continue
                if not _mensagem_valida(m):
                    invalidas += 1
                    continue
                mid = m["mid"]
                if mid in self._registros or mid in vistos:
                    existentes += 1  # duplicata: vale a primeira
                    continue
                claro = m["conteudo"]
                if transito is not None:
                    try:
                        claro = cifra.decifrar(transito, claro)
                    except cifra.DecifragemFalhou:
                        ilegiveis += 1
                        continue
                vistos.add(mid)
                novos.append(
                    {
                        "mid": mid,
                        "remetente": _copiar_remetente(m["remetente"]),
                        "ts": m["ts"],
                        "tipo": m["tipo"],
                        "conteudo": cifra.cifrar(self._chave, claro),
                    }
                )

            if novos:
                self._anexar(novos)
                for r in novos:
                    self._registros[r["mid"]] = r
                adicionadas = len(novos)

        return ResultadoIncorporacao(adicionadas, existentes, ilegiveis, invalidas)

    def _anexar(self, registros: list[dict[str, Any]]) -> None:
        texto = "".join(
            json.dumps(r, ensure_ascii=True, separators=(",", ":")) + "\n" for r in registros
        )
        try:
            _criar_diretorio_privado(self._arquivo.parent)
            novo = not self._arquivo.exists()
            with open(self._arquivo, "ab") as f:
                if f.tell() > self._fim_valido:
                    # descarta a linha incompleta deixada por uma queda anterior
                    f.truncate(self._fim_valido)
                    f.seek(self._fim_valido)
                f.write(texto.encode("ascii"))
                f.flush()
                os.fsync(f.fileno())
                self._fim_valido = f.tell()
            if novo:
                try:
                    os.chmod(self._arquivo, 0o600)
                except OSError:
                    pass
        except OSError:
            raise ErroHistorico("Falha ao gravar o Historico_Local") from None

    # -- consulta ------------------------------------------------------------
    def listar(self) -> list[dict[str, Any]]:
        """Mensagens decifradas, ordenadas por ``(ts, mid)``.

        Registro que nao decifra vem com ``conteudo=None`` e ``ilegivel=True``.
        """
        saida = []
        for r in self.registros_cifrados():
            try:
                conteudo: Optional[str] = cifra.decifrar(self._chave, r["conteudo"])
                ilegivel = False
            except cifra.DecifragemFalhou:
                conteudo, ilegivel = None, True
            saida.append(
                {
                    "mid": r["mid"],
                    "remetente": r["remetente"],
                    "ts": r["ts"],
                    "tipo": r["tipo"],
                    "conteudo": conteudo,
                    "ilegivel": ilegivel,
                }
            )
        return saida

    def registros_cifrados(self) -> list[dict[str, Any]]:
        """Registros como gravados (``conteudo`` cifrado), ordenados por ``(ts, mid)``."""
        with self._trava:
            copia = [
                {**r, "remetente": dict(r["remetente"])} for r in self._registros.values()
            ]
        copia.sort(key=_chave_de_ordenacao)
        return copia

    def mids(self) -> frozenset[str]:
        with self._trava:
            return frozenset(self._registros)

    def contar(self) -> int:
        with self._trava:
            return len(self._registros)

    def __len__(self) -> int:
        return self.contar()

    def __contains__(self, mid: object) -> bool:
        with self._trava:
            return mid in self._registros


def abrir_historico(
    login: str,
    *,
    home: Optional[CAMINHO] = None,
    chave_transito: Union[bytes, bytearray, str, None] = None,
) -> HistoricoLocal:
    """Abre (criando, se preciso) o Historico_Local do usuario neste dispositivo.

    ``home`` troca ``~`` (testes). ``chave_transito`` serve apenas para garantir
    que a Chave_Historico gerada seja diferente dela.
    """
    diretorio = diretorio_historico(login, home)
    transito_hex = (
        _normalizar_chave_transito(chave_transito).hex() if chave_transito is not None else None
    )
    try:
        _criar_diretorio_privado(diretorio)
    except OSError:
        raise ErroHistorico("Falha ao abrir o Historico_Local") from None
    chave = _garantir_chave(diretorio / NOME_ARQUIVO_CHAVE, transito_hex)
    arquivo = diretorio / NOME_ARQUIVO_HISTORICO
    registros, fim_valido = _carregar_arquivo(arquivo)
    return HistoricoLocal(login, arquivo, chave, registros, fim_valido)
