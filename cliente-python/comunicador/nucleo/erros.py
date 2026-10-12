"""ReportadorErros e Buffer_Offline_Erros do Cliente_Terceiro.

O comportamento acompanha ``cliente-web/js/erros.js``:

- ``ReportadorErros.reportar(excecao)`` e o ponto central: monta o evento,
  mascara segredos (``mascaramento.py``), grava no Buffer_Offline_Erros e, se
  ha conexao, envia ``error_report``. Sem conexao ou com falha de envio, o
  evento permanece no buffer.
- Flag de reentrancia ``em_reporte`` (profundidade por thread, com
  ``try/finally``): enquanto a thread esta reportando, enviando ou gravando,
  qualquer erro novo vai so para o console local e nao gera evento.
  Excecoes internas nunca saem do Reportador.
- Buffer_Offline_Erros: no maximo 100 eventos e 256 KiB (JSON em UTF-8); ao
  exceder, descarta o mais antigo (FIFO) e conta o descarte; cada evento tem no
  maximo 5 tentativas de envio. O contador ``descartados`` segue no primeiro
  evento do proximo lote e so e abatido quando esse evento e confirmado.
- Esvaziamento: depois do login e a cada 60 s, ate 20 eventos (e 60 KiB) por
  ``error_report``; so os ``evid`` presentes em ``aceitos`` saem do buffer.

A logica do buffer (``BufferOfflineErros``) e pura: nao conhece rede, Tkinter
nem relogio. Opera sobre um armazenamento com ``carregar()`` e
``gravar(estado)``; ha uma implementacao em memoria (testes) e uma em arquivo
(``<raiz>/.comunicador/python/erros_pendentes.txt``).

Formato do arquivo: um objeto JSON por linha. Cada linha de evento traz
``{"evid","ts","modulo","tipo","descricao","pilha","dispositivo","cliente",
"tentativas"}``. O contador de descartes pendentes fica em uma linha propria
``{"descartados": N}`` (sem ``evid``) no inicio do arquivo. Linhas ilegiveis
sao ignoradas. A gravacao e atomica (arquivo temporario + ``os.replace``).

"""
from __future__ import annotations

import json
import os
import sys
import threading
import traceback
import uuid
from collections import deque
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, NamedTuple, Optional, Protocol, Union

from comunicador.nucleo.config import diretorio_cliente
from comunicador.nucleo.mascaramento import mascarar

# ---------------------------------------------------------------------------
# Constantes
# ---------------------------------------------------------------------------

MAXIMO_EVENTOS_BUFFER = 100
"""Maximo de eventos retidos no buffer."""

MAXIMO_BYTES_BUFFER = 256 * 1024
"""Maximo de bytes (JSON em UTF-8) retidos no buffer: 256 KiB."""

MAXIMO_TENTATIVAS_EVENTO = 5
"""Maximo de tentativas de envio por evento."""

MAXIMO_EVENTOS_ERRO = 20
"""Maximo de eventos por ``error_report`` (limite do Middleware)."""

MAXIMO_BYTES_LOTE = 60 * 1024
"""Tamanho maximo de um ``error_report`` (o Middleware aceita 64 KiB)."""

INTERVALO_ESVAZIAMENTO_S = 60.0
"""Intervalo do reenvio do buffer, em segundos."""

TEMPO_LIMITE_ENVIO_S = 20.0
"""Tempo maximo de espera pela confirmacao de um ``error_report``."""

MAXIMO_DESCRICAO = 2000
MAXIMO_PILHA = 8000
MAXIMO_TIPO = 200
"""Limites de ``descricao``, ``pilha`` e ``tipo``."""

NOME_ARQUIVO_ERROS = "erros_pendentes.txt"

_CODIGOS_SEM_CONEXAO = frozenset({"SEM_CONEXAO", "CONEXAO_PERDIDA"})

Evento = dict
Estado = dict  # {"eventos": [Evento, ...], "descartados": int}


# ---------------------------------------------------------------------------
# Funcoes puras
# ---------------------------------------------------------------------------

def tamanho_em_bytes(valor: Any) -> int:
    """Tamanho, em bytes, do JSON compacto UTF-8 de ``valor``."""
    return len(json.dumps(valor, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))


def truncar(texto: str, maximo: int) -> str:
    """Corta ``texto`` em ``maximo`` caracteres (pontos de codigo)."""
    return texto if len(texto) <= maximo else texto[:maximo]


def nome_arquivo(origem: Any) -> Optional[str]:
    """Nome do arquivo de um caminho (sem consulta nem fragmento), ou ``None``."""
    if not isinstance(origem, str) or origem == "":
        return None
    limpo = origem.replace("\\", "/")
    for separador in ("?", "#"):
        limpo = limpo.split(separador)[0]
    nome = limpo[limpo.rfind("/") + 1:]
    return nome or None


def modulo_de(excecao: Any, informado: Any = None) -> str:
    """Modulo de origem: o informado, senao o arquivo do quadro mais interno
    da pilha, senao ``desconhecido``."""
    dado = nome_arquivo(informado)
    if dado is not None:
        return dado
    tb = getattr(excecao, "__traceback__", None)
    ultimo = None
    while tb is not None:
        ultimo = tb
        tb = tb.tb_next
    if ultimo is not None:
        nome = nome_arquivo(ultimo.tb_frame.f_code.co_filename)
        if nome is not None:
            return nome
    return "desconhecido"


def descrever_excecao(excecao: Any) -> tuple[str, str, str]:
    """Descreve uma excecao qualquer em ``(tipo, descricao, pilha)``."""
    if isinstance(excecao, BaseException):
        tipo = type(excecao).__name__ or "Error"
        try:
            descricao = str(excecao)
        except Exception:  # noqa: BLE001 - __str__ defeituoso
            descricao = repr(type(excecao))
        try:
            pilha = "".join(
                traceback.format_exception(type(excecao), excecao, excecao.__traceback__)
            )
        except Exception:  # noqa: BLE001
            pilha = ""
        return tipo, descricao, pilha
    if isinstance(excecao, str):
        return "Error", excecao, ""
    return type(excecao).__name__, str(excecao), ""


def agora_iso(momento: Optional[datetime] = None) -> str:
    """Instante em UTC no formato ``2024-01-31T12:00:00.123Z`` (igual ao ``toISOString`` da web)."""
    m = (momento or datetime.now(timezone.utc)).astimezone(timezone.utc)
    return m.strftime("%Y-%m-%dT%H:%M:%S.") + f"{m.microsecond // 1000:03d}Z"


def montar_evento(
    excecao: Any,
    *,
    modulo: Optional[str] = None,
    ts: str,
    evid: str,
    dispositivo: Optional[str] = None,
    cliente: str = "python",
) -> Evento:
    """Monta o evento do buffer. ``descricao`` e ``pilha`` sao mascaradas e so
    depois truncadas (truncar antes poderia deixar um segredo curto demais para
    ser reconhecido)."""
    tipo, descricao, pilha = descrever_excecao(excecao)
    return {
        "evid": evid,
        "ts": ts,
        "modulo": modulo_de(excecao, modulo),
        "tipo": truncar(str(mascarar(tipo)), MAXIMO_TIPO),
        "descricao": truncar(mascarar(descricao), MAXIMO_DESCRICAO),
        "pilha": truncar(mascarar(pilha), MAXIMO_PILHA),
        "dispositivo": dispositivo,
        "cliente": cliente,
        "tentativas": 0,
    }


def _para_envio(evento: Evento, descartados: int) -> dict:
    """Forma enviada no ``error_report``: sem ``tentativas``, com ``descartados``."""
    return {
        "ts": evento["ts"],
        "modulo": evento["modulo"],
        "tipo": evento["tipo"],
        "descricao": evento["descricao"],
        "pilha": evento["pilha"],
        "dispositivo": evento.get("dispositivo"),
        "cliente": evento.get("cliente", "python"),
        "evid": evento["evid"],
        "descartados": descartados,
    }


def aplicar_limites(
    estado: Estado,
    *,
    max_eventos: int = MAXIMO_EVENTOS_BUFFER,
    max_bytes: int = MAXIMO_BYTES_BUFFER,
) -> Estado:
    """Aplica os limites a ``estado`` e devolve o novo estado: enquanto houver
    mais de ``max_eventos`` eventos ou mais de ``max_bytes`` bytes, descarta o
    mais antigo e soma 1 a ``descartados``. Nao altera ``estado``."""
    eventos = list(estado["eventos"])
    tamanhos = [tamanho_em_bytes(e) for e in eventos]
    total = sum(tamanhos)
    descartados = estado["descartados"]
    inicio = 0
    while inicio < len(eventos) and ((len(eventos) - inicio) > max_eventos or total > max_bytes):
        total -= tamanhos[inicio]
        inicio += 1
        descartados += 1
    return {"eventos": eventos[inicio:], "descartados": descartados}


def eh_falha_de_conexao(erro: BaseException) -> bool:
    """Indica se ``erro`` representa falta de conexao (a tentativa nao e contada)."""
    return getattr(erro, "codigo", None) in _CODIGOS_SEM_CONEXAO


# ---------------------------------------------------------------------------
# Armazenamentos
# ---------------------------------------------------------------------------

class Armazenamento(Protocol):
    def carregar(self) -> Estado: ...
    def gravar(self, estado: Estado) -> None: ...


class ArmazenamentoMemoria:
    """Armazenamento em memoria (testes e fallback)."""

    def __init__(self) -> None:
        self._estado: Estado = {"eventos": [], "descartados": 0}

    def carregar(self) -> Estado:
        return json.loads(json.dumps(self._estado))

    def gravar(self, estado: Estado) -> None:
        self._estado = json.loads(
            json.dumps({"eventos": estado["eventos"], "descartados": estado["descartados"]})
        )


def caminho_erros_pendentes(home: Optional[Union[str, Path]] = None) -> Path:
    """``<raiz>/.comunicador/python/erros_pendentes.txt``."""
    return diretorio_cliente(home) / NOME_ARQUIVO_ERROS


def _evento_valido(obj: Any) -> bool:
    return (
        isinstance(obj, dict)
        and isinstance(obj.get("evid"), str)
        and obj["evid"] != ""
        and all(isinstance(obj.get(c), str) for c in ("ts", "modulo", "tipo", "descricao", "pilha"))
    )


class ArmazenamentoArquivo:
    """Armazenamento em arquivo: um evento JSON por linha (ver docstring do modulo)."""

    def __init__(self, caminho: Optional[Union[str, Path]] = None) -> None:
        self._caminho = Path(caminho) if caminho is not None else caminho_erros_pendentes()

    @property
    def caminho(self) -> Path:
        return self._caminho

    def carregar(self) -> Estado:
        eventos: list[Evento] = []
        vistos: set[str] = set()
        descartados = 0
        try:
            texto = self._caminho.read_text(encoding="utf-8", errors="replace")
        except FileNotFoundError:
            return {"eventos": [], "descartados": 0}
        for linha in texto.splitlines():
            linha = linha.strip()
            if not linha:
                continue
            try:
                obj = json.loads(linha)
            except ValueError:
                continue  # linha ilegivel ou incompleta
            if not isinstance(obj, dict):
                continue
            if "evid" not in obj:
                valor = obj.get("descartados")
                if isinstance(valor, int) and not isinstance(valor, bool) and valor > 0:
                    descartados = valor
                continue
            if not _evento_valido(obj) or obj["evid"] in vistos:
                continue
            tentativas = obj.get("tentativas")
            obj["tentativas"] = tentativas if isinstance(tentativas, int) and not isinstance(tentativas, bool) and tentativas >= 0 else 0
            vistos.add(obj["evid"])
            eventos.append(obj)
        return {"eventos": eventos, "descartados": descartados}

    def gravar(self, estado: Estado) -> None:
        diretorio = self._caminho.parent
        diretorio.mkdir(parents=True, exist_ok=True)
        try:
            os.chmod(diretorio, 0o700)
        except OSError:
            pass
        linhas = []
        if estado["descartados"] > 0:
            linhas.append(json.dumps({"descartados": estado["descartados"]}, separators=(",", ":")))
        for evento in estado["eventos"]:
            linhas.append(json.dumps(evento, ensure_ascii=False, separators=(",", ":")))
        conteudo = "".join(linha + "\n" for linha in linhas)
        temporario = self._caminho.with_name(self._caminho.name + ".tmp")
        with open(temporario, "w", encoding="utf-8", newline="\n") as f:
            f.write(conteudo)
            f.flush()
            os.fsync(f.fileno())
        try:
            os.chmod(temporario, 0o600)
        except OSError:
            pass
        os.replace(temporario, self._caminho)


# ---------------------------------------------------------------------------
# Buffer_Offline_Erros
# ---------------------------------------------------------------------------

class Lote(NamedTuple):
    """Lote selecionado para um ``error_report``."""

    eventos: list
    evids: list
    descartados_enviados: int
    evid_com_contador: str


class ResultadoAdicao(NamedTuple):
    retido: bool
    descartados_agora: int


class ResultadoLote(NamedTuple):
    removidos: int
    descartados_agora: int
    restantes: int


class BufferOfflineErros:
    """Buffer_Offline_Erros. Todas as operacoes sao serializadas por um lock,
    porque cada uma le o estado, o altera e o grava de volta."""

    def __init__(
        self,
        armazenamento: Armazenamento,
        *,
        max_eventos: int = MAXIMO_EVENTOS_BUFFER,
        max_bytes: int = MAXIMO_BYTES_BUFFER,
        max_tentativas: int = MAXIMO_TENTATIVAS_EVENTO,
    ) -> None:
        self._armazenamento = armazenamento
        self._max_eventos = max_eventos
        self._max_bytes = max_bytes
        self._max_tentativas = max_tentativas
        self._lock = threading.RLock()

    def listar(self) -> list[Evento]:
        """Eventos retidos, do mais antigo ao mais novo (copias)."""
        with self._lock:
            return self._armazenamento.carregar()["eventos"]

    def tamanho(self) -> int:
        with self._lock:
            return len(self._armazenamento.carregar()["eventos"])

    def descartados(self) -> int:
        """Descartes ainda nao informados ao Middleware."""
        with self._lock:
            return self._armazenamento.carregar()["descartados"]

    def adicionar(self, evento: Evento) -> ResultadoAdicao:
        """Retem ``evento`` (com ``evid``). Ignora um ``evid`` ja presente. Se
        estourar os limites, descarta os mais antigos."""
        with self._lock:
            estado = self._armazenamento.carregar()
            if any(e["evid"] == evento["evid"] for e in estado["eventos"]):
                return ResultadoAdicao(True, 0)
            novo = {**evento, "tentativas": evento.get("tentativas", 0) or 0}
            limitado = aplicar_limites(
                {"eventos": [*estado["eventos"], novo], "descartados": estado["descartados"]},
                max_eventos=self._max_eventos,
                max_bytes=self._max_bytes,
            )
            self._armazenamento.gravar(limitado)
            return ResultadoAdicao(
                any(e["evid"] == novo["evid"] for e in limitado["eventos"]),
                limitado["descartados"] - estado["descartados"],
            )

    def preparar_lote(
        self,
        *,
        apenas: Optional[list[str]] = None,
        max_eventos: int = MAXIMO_EVENTOS_ERRO,
        max_bytes: int = MAXIMO_BYTES_LOTE,
    ) -> Optional[Lote]:
        """Seleciona o proximo lote: do mais antigo ao mais novo, ate
        ``max_eventos`` e ``max_bytes`` (ao menos um). ``apenas`` restringe a
        esses ``evid``. O primeiro evento leva o contador ``descartados``; os
        demais, zero. Devolve ``None`` se nao houver o que enviar."""
        with self._lock:
            estado = self._armazenamento.carregar()
            candidatos = (
                estado["eventos"] if apenas is None else [e for e in estado["eventos"] if e["evid"] in apenas]
            )
            eventos: list[dict] = []
            total = 0
            for candidato in candidatos:
                if len(eventos) >= max_eventos:
                    break
                forma = _para_envio(candidato, estado["descartados"] if not eventos else 0)
                tamanho = tamanho_em_bytes(forma)
                if eventos and total + tamanho > max_bytes:
                    break
                eventos.append(forma)
                total += tamanho
            if not eventos:
                return None
            return Lote(eventos, [e["evid"] for e in eventos], eventos[0]["descartados"], eventos[0]["evid"])

    def resolver_lote(
        self,
        lote: Lote,
        *,
        aceitos: Any = (),
        contar_tentativa: bool = True,
    ) -> ResultadoLote:
        """Registra o resultado do envio de ``lote``.

        - Remove SOMENTE os eventos do lote cujo ``evid`` consta de ``aceitos``;
          se o evento que levava o contador foi confirmado, abate
          ``descartados_enviados``.
        - Se ``contar_tentativa``, os demais eventos do lote ganham uma
          tentativa e, ao chegar ao maximo, sao descartados e contados.
        """
        with self._lock:
            estado = self._armazenamento.carregar()
            confirmados = {a for a in aceitos if isinstance(a, str)} if isinstance(aceitos, (list, tuple, set, frozenset)) else set()
            do_lote = set(lote.evids)
            descartados = estado["descartados"]
            removidos = 0
            descartados_agora = 0
            eventos: list[Evento] = []
            for evento in estado["eventos"]:
                if evento["evid"] not in do_lote:
                    eventos.append(evento)
                    continue
                if evento["evid"] in confirmados:
                    removidos += 1
                    if evento["evid"] == lote.evid_com_contador:
                        descartados = max(0, descartados - lote.descartados_enviados)
                    continue
                if not contar_tentativa:
                    eventos.append(evento)
                    continue
                tentativas = (evento.get("tentativas") or 0) + 1
                if tentativas >= self._max_tentativas:
                    descartados += 1
                    descartados_agora += 1
                    continue
                eventos.append({**evento, "tentativas": tentativas})
            self._armazenamento.gravar({"eventos": eventos, "descartados": descartados})
            return ResultadoLote(removidos, descartados_agora, len(eventos))


# ---------------------------------------------------------------------------
# ReportadorErros
# ---------------------------------------------------------------------------

def _console_padrao(mensagem: str, erro: Any) -> None:
    print(f"[ReportadorErros] {mensagem}: {type(erro).__name__}: {erro}", file=sys.stderr)


class ReportadorErros:
    """Ponto central de reporte de excecoes do Cliente_Terceiro.

    Nunca levanta excecao: falhas internas vao para o console local e nao geram
    novo evento.

    ``conexao`` e uma ``Conexao`` (ou equivalente) com ``aberta`` e
    ``enviar_error_report(eventos)``, que devolve um ``Future`` (ou diretamente
    a lista ``aceitos``). Pode ser definida depois com ``definir_conexao``.

    Com ``segundo_plano=True`` (padrao), o envio e o esvaziamento rodam em
    threads daemon, para que a thread da interface nunca espere pela rede;
    com ``False`` tudo roda na thread chamadora (testes).
    """

    def __init__(
        self,
        buffer: BufferOfflineErros,
        *,
        conexao: Any = None,
        dispositivo: Union[None, str, Callable[[], Optional[str]]] = None,
        cliente: str = "python",
        relogio: Callable[[], datetime] = lambda: datetime.now(timezone.utc),
        gerar_id: Callable[[], str] = lambda: uuid.uuid4().hex,
        console: Callable[[str, Any], None] = _console_padrao,
        intervalo_s: float = INTERVALO_ESVAZIAMENTO_S,
        tempo_limite_envio_s: float = TEMPO_LIMITE_ENVIO_S,
        segundo_plano: bool = True,
    ) -> None:
        self._buffer = buffer
        self._conexao = conexao
        self._dispositivo = dispositivo
        self._cliente = cliente
        self._relogio = relogio
        self._gerar_id = gerar_id
        self._console = console
        self._intervalo_s = intervalo_s
        self._tempo_limite = tempo_limite_envio_s
        self._segundo_plano = segundo_plano

        self._local = threading.local()           # profundidade da flag, por thread
        self._lock_envio = threading.Lock()       # um error_report por vez
        self._lock_fila = threading.Lock()
        self._fila: deque[str] = deque(maxlen=MAXIMO_EVENTOS_BUFFER)
        self._trabalhador_ativo = False
        self._parar = threading.Event()
        self._periodico: Optional[threading.Thread] = None

    # ----- flag de reentrancia -----------------------------------------------

    @property
    def em_reporte(self) -> bool:
        """``True`` enquanto a thread atual esta reportando, enviando ou gravando."""
        return getattr(self._local, "profundidade", 0) > 0

    def _entrar(self) -> None:
        self._local.profundidade = getattr(self._local, "profundidade", 0) + 1

    def _sair(self) -> None:
        self._local.profundidade = max(0, getattr(self._local, "profundidade", 1) - 1)

    # ----- API publica -------------------------------------------------------

    def definir_conexao(self, conexao: Any) -> None:
        """Define (ou troca) a conexao usada para enviar."""
        self._conexao = conexao

    def reportar(self, excecao: Any, *, modulo: Optional[str] = None) -> Optional[Evento]:
        """Reporta uma excecao. Se chamado durante um reporte (``em_reporte``),
        o erro vai so para o console e nenhum evento e criado. Devolve o evento
        criado ou ``None``."""
        if self.em_reporte:
            self._registrar_local("Erro durante o reporte de erros (nao reportado)", excecao)
            return None
        self._entrar()
        try:
            evento = montar_evento(
                excecao,
                modulo=modulo,
                ts=agora_iso(self._relogio()),
                evid=self._gerar_id(),
                dispositivo=self._dispositivo_atual(),
                cliente=self._cliente,
            )
            retido = self._buffer.adicionar(evento).retido
            if retido and self._conectado():
                if self._segundo_plano:
                    self._agendar(evento["evid"])
                else:
                    self._enviar_lote(apenas=[evento["evid"]])
            return evento
        except Exception as e:  # noqa: BLE001
            self._registrar_local("Falha interna do ReportadorErros", e)
            return None
        finally:
            self._sair()

    def esvaziar(self) -> int:
        """Envia o que estiver retido, em lotes de ate 20 eventos, enquanto
        houver conexao e os lotes forem integralmente confirmados. Devolve a
        quantidade de eventos confirmados."""
        self._entrar()
        try:
            confirmados = 0
            rodadas = -(-MAXIMO_EVENTOS_BUFFER // MAXIMO_EVENTOS_ERRO) + 1
            for _ in range(rodadas):
                if not self._conectado():
                    break
                resultado = self._enviar_lote(apenas=None)
                if resultado is None:
                    break
                removidos, restantes_no_lote = resultado
                confirmados += removidos
                if restantes_no_lote > 0:
                    break
            return confirmados
        except Exception as e:  # noqa: BLE001
            self._registrar_local("Falha ao esvaziar o Buffer_Offline_Erros", e)
            return 0
        finally:
            self._sair()

    def apos_login(self) -> Optional[int]:
        """Chamar depois do login (e do relogin automatico): esvazia o buffer.
        Em segundo plano, devolve ``None`` imediatamente."""
        if not self._segundo_plano:
            return self.esvaziar()
        threading.Thread(target=self.esvaziar, name="comunicador-erros-login", daemon=True).start()
        return None

    def iniciar(self) -> None:
        """Inicia o esvaziamento periodico (a cada 60 s). Idempotente."""
        if self._periodico is not None and self._periodico.is_alive():
            return
        self._parar = threading.Event()
        parar = self._parar

        def laco() -> None:
            while not parar.wait(self._intervalo_s):
                self.esvaziar()

        self._periodico = threading.Thread(target=laco, name="comunicador-erros-periodico", daemon=True)
        self._periodico.start()

    def parar(self) -> None:
        """Para o esvaziamento periodico."""
        self._parar.set()
        self._periodico = None

    # ----- internos ----------------------------------------------------------

    def _agendar(self, evid: str) -> None:
        """Enfileira o envio de ``evid`` para uma unica thread de trabalho."""
        with self._lock_fila:
            self._fila.append(evid)
            if self._trabalhador_ativo:
                return
            self._trabalhador_ativo = True
        try:
            threading.Thread(target=self._drenar, name="comunicador-erros-envio", daemon=True).start()
        except Exception as e:  # noqa: BLE001
            with self._lock_fila:
                self._trabalhador_ativo = False
            self._registrar_local("Falha ao iniciar o envio de error_report", e)

    def _drenar(self) -> None:
        try:
            while True:
                with self._lock_fila:
                    if not self._fila:
                        self._trabalhador_ativo = False
                        return
                    evid = self._fila.popleft()
                self._entrar()
                try:
                    if self._conectado():
                        self._enviar_lote(apenas=[evid])
                except Exception as e:  # noqa: BLE001
                    self._registrar_local("Falha ao enviar error_report", e)
                finally:
                    self._sair()
        finally:
            with self._lock_fila:
                self._trabalhador_ativo = False

    def _enviar_lote(self, *, apenas: Optional[list[str]]) -> Optional[tuple[int, int]]:
        """Envia um lote e registra o resultado no buffer. Os envios sao
        serializados para que o mesmo evento nao saia em dois ``error_report``
        ao mesmo tempo. Devolve ``None`` se nao havia lote; senao
        ``(removidos, restantes_no_lote)``."""
        with self._lock_envio:
            if not self._conectado():
                return None
            lote = self._buffer.preparar_lote(apenas=apenas)
            if lote is None:
                return None
            aceitos: list = []
            contar_tentativa = True
            try:
                resposta = self._conexao.enviar_error_report(lote.eventos)
                if hasattr(resposta, "result"):
                    resposta = resposta.result(timeout=self._tempo_limite)
                aceitos = list(resposta) if isinstance(resposta, (list, tuple)) else []
            except Exception as e:  # noqa: BLE001
                contar_tentativa = not eh_falha_de_conexao(e)
                self._registrar_local("Falha ao enviar error_report", e)
            removidos = self._buffer.resolver_lote(lote, aceitos=aceitos, contar_tentativa=contar_tentativa).removidos
            return removidos, len(lote.evids) - removidos

    def _conectado(self) -> bool:
        return bool(self._conexao is not None and getattr(self._conexao, "aberta", False))

    def _dispositivo_atual(self) -> Optional[str]:
        try:
            valor = self._dispositivo() if callable(self._dispositivo) else self._dispositivo
            return valor if isinstance(valor, str) and valor != "" else None
        except Exception:  # noqa: BLE001
            return None

    def _registrar_local(self, mensagem: str, erro: Any) -> None:
        try:
            self._console(mensagem, erro)
        except Exception:  # noqa: BLE001 - nem o console pode gerar novo evento
            pass


# ---------------------------------------------------------------------------
# Captura global
# ---------------------------------------------------------------------------

def instalar_captura_global(reportador: ReportadorErros, raiz_tk: Any = None) -> Callable[[], None]:
    """Liga ``sys.excepthook``, ``threading.excepthook`` e, se ``raiz_tk`` for
    dado, ``Tk.report_callback_exception`` ao ``reportador``. Preserva os
    ganchos anteriores (chamados depois do reporte) e devolve uma funcao que
    desfaz a ligacao."""
    anterior_sys = sys.excepthook
    anterior_thread = threading.excepthook
    anterior_tk = getattr(raiz_tk, "report_callback_exception", None) if raiz_tk is not None else None

    def _reportar(tipo: Any, valor: Any, tb: Any) -> None:
        try:
            if valor is None:
                return
            if tb is not None and valor.__traceback__ is None:
                valor = valor.with_traceback(tb)
            reportador.reportar(valor)
        except Exception:  # noqa: BLE001 - nunca propaga
            pass

    def gancho_sys(tipo, valor, tb):
        if not (isinstance(tipo, type) and issubclass(tipo, KeyboardInterrupt)):
            _reportar(tipo, valor, tb)
        try:
            anterior_sys(tipo, valor, tb)
        except Exception:  # noqa: BLE001
            pass

    def gancho_thread(args):
        if not issubclass(args.exc_type, SystemExit):
            _reportar(args.exc_type, args.exc_value, args.exc_traceback)
        try:
            anterior_thread(args)
        except Exception:  # noqa: BLE001
            pass

    def gancho_tk(tipo, valor, tb):
        _reportar(tipo, valor, tb)
        try:
            if anterior_tk is not None:
                anterior_tk(tipo, valor, tb)
        except Exception:  # noqa: BLE001
            pass

    sys.excepthook = gancho_sys
    threading.excepthook = gancho_thread
    if raiz_tk is not None:
        raiz_tk.report_callback_exception = gancho_tk

    def desfazer() -> None:
        if sys.excepthook is gancho_sys:
            sys.excepthook = anterior_sys
        if threading.excepthook is gancho_thread:
            threading.excepthook = anterior_thread
        if raiz_tk is not None and getattr(raiz_tk, "report_callback_exception", None) is gancho_tk:
            raiz_tk.report_callback_exception = anterior_tk

    return desfazer


def criar_reportador_erros(
    *,
    caminho: Optional[Union[str, Path]] = None,
    armazenamento: Optional[Armazenamento] = None,
    **opcoes: Any,
) -> ReportadorErros:
    """Cria o buffer persistente (arquivo em ``<raiz>/.comunicador/python/``) e o
    Reportador, sem ligar captura nem timer."""
    buffer = BufferOfflineErros(armazenamento if armazenamento is not None else ArmazenamentoArquivo(caminho))
    return ReportadorErros(buffer, **opcoes)
