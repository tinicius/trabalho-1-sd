"""Conexao TCP do Cliente_Terceiro com o Middleware (porta 9000).

O comportamento acompanha
``cliente-web/js/conexao.js``:

- um objeto JSON por linha (``protocolo.LeitorDeLinhas``), UTF-8;
- ``dados`` (register/login) e ``conteudo`` (send_message) sao cifrados com a
  Chave_Transito pela Cifra_Manual (``cifra.py``); mensagens e historico
  recebidos sao decifrados aqui, antes de chegarem a interface;
- ``ping`` a cada 30 s enquanto a conexao esta aberta;
- reconexao automatica com atraso exponencial limitado e jitter de +-20%, ate
  10 tentativas consecutivas; depois disso, estado "desconectado" e
  ``tentar_novamente()``;
- para voltar a sala, login e senha ficam **somente na memoria do processo**
  (nunca em disco) e sao apagados no logout;
- uma thread de rede (daemon) le o socket e publica eventos em uma
  ``queue.Queue``; a interface Tkinter consulta a fila com ``after(100, ...)``
  e nunca bloqueia na rede. Operacoes que esperam resposta devolvem um
  ``concurrent.futures.Future``.

A parte pura (``atraso_reconexao``, ``atraso_com_jitter``,
``pode_tentar_novamente``) nao depende de rede, relogio ou threads.

Eventos publicados na fila (todos ``NamedTuple``):
``EventoEstado``, ``EventoMensagem``, ``EventoHistorico``,
``EventoReautenticado`` e ``EventoErro``. Eventos de erro nunca carregam
conteudo legivel de mensagem, senha ou chave.
"""
from __future__ import annotations

import json
import queue
import random
import select
import socket
import threading
import time
import uuid
from concurrent.futures import Future, InvalidStateError
from typing import Any, Callable, NamedTuple, Optional

from comunicador.nucleo import protocolo
from comunicador.nucleo.cifra import TAMANHO_CHAVE, DecifragemFalhou, cifrar, decifrar
from comunicador.nucleo.protocolo import CodigoErro, ErroDeProtocolo, LeitorDeLinhas

# ---------------------------------------------------------------------------
# Politica de reconexao (pura)
# ---------------------------------------------------------------------------

PORTA_TCP = 9000
"""Porta TCP do Middleware (``porta.tcp``)."""

INTERVALO_PING_S = 30.0
"""Intervalo entre pings (keep-alive), em segundos."""

ATRASO_BASE_MS = 1000
"""Atraso base da primeira tentativa de reconexao, em ms."""

ATRASO_MAXIMO_MS = 30000
"""Atraso maximo (sem jitter) entre tentativas, em ms."""

MAXIMO_TENTATIVAS = 10
"""Maximo de tentativas consecutivas de reconexao."""

FRACAO_JITTER = 0.2
"""Fracao de jitter aplicada ao atraso (+-20%)."""

TEMPO_LIMITE_REQUISICAO_S = 15.0
"""Tempo maximo de espera por uma resposta do Middleware, em segundos."""

TEMPO_LIMITE_CONEXAO_S = 5.0
"""Tempo maximo para abrir o socket TCP, em segundos."""

# Com base de 1 s e limite de 30 s, 2^5 ja satura; limitar o expoente evita
# calcular potencias gigantes para ``n`` enorme sem mudar o resultado.
_EXPOENTE_MAXIMO = 16

_SONDA_S = 0.5
"""Periodo com que a thread de rede reavalia ping, prazos e encerramento."""


def atraso_reconexao(n: int) -> int:
    """Atraso base, sem jitter, antes da tentativa ``n`` (0, 1, 2, ...), em ms.

    ``min(1000 * 2**n, 30000)``: 1, 2, 4, 8, 16, 30, 30... segundos.
    Funcao pura. Levanta ``ValueError`` se ``n`` nao for inteiro >= 0.
    """
    if isinstance(n, bool) or not isinstance(n, int) or n < 0:
        raise ValueError("A tentativa deve ser um inteiro maior ou igual a zero")
    return min(ATRASO_BASE_MS * 2 ** min(n, _EXPOENTE_MAXIMO), ATRASO_MAXIMO_MS)


def atraso_com_jitter(n: int, aleatorio: Callable[[], float] = random.random) -> float:
    """Atraso da tentativa ``n`` com jitter de +-20%, em ms.

    O resultado fica em ``[0,8 * base, 1,2 * base]``. ``aleatorio`` devolve um
    numero em ``[0, 1]`` (padrao ``random.random``) e e injetavel para testes;
    com ``aleatorio`` determinista a funcao e pura.
    """
    base = atraso_reconexao(n)
    r = min(1.0, max(0.0, aleatorio()))
    # Percentuais inteiros nos extremos (80 e 120) evitam erro de ponto flutuante.
    percentual = 100 * (1 - FRACAO_JITTER) + 100 * 2 * FRACAO_JITTER * r
    return base * percentual / 100


def pode_tentar_novamente(feitas: int) -> bool:
    """Indica se cabe mais uma tentativa depois de ``feitas`` tentativas
    consecutivas ja realizadas (limite de 10). Funcao pura."""
    return (
        isinstance(feitas, int)
        and not isinstance(feitas, bool)
        and 0 <= feitas < MAXIMO_TENTATIVAS
    )


# ---------------------------------------------------------------------------
# Estados, erros e eventos
# ---------------------------------------------------------------------------

class EstadoConexao:
    """Estados da conexao informados a interface."""

    DESCONECTADO = "desconectado"
    """Sem conexao (inicial, apos logout, falha ao abrir ou tentativas esgotadas)."""
    CONECTANDO = "conectando"
    """Abrindo o socket pela primeira vez."""
    CONECTADO = "conectado"
    """Socket aberto, sem Sessao autenticada."""
    AUTENTICADO = "autenticado"
    """Login confirmado pelo Middleware."""
    RECONECTANDO = "reconectando"
    """Conexao perdida; tentando de novo com backoff."""


class ErroRequisicao(Exception):
    """Erro de uma requisicao: resposta ``ok:false``, falha de rede ou tempo esgotado.

    ``codigo`` e um codigo do Protocolo ou ``CONEXAO_PERDIDA``,
    ``TEMPO_ESGOTADO``, ``SEM_CONEXAO``, ``SEM_CHAVE``. A mensagem nunca inclui
    dados do usuario.
    """

    def __init__(self, codigo: str, mensagem: str, campo: Optional[str] = None) -> None:
        super().__init__(mensagem)
        self.codigo = codigo
        self.mensagem = mensagem
        self.campo = campo


class MensagemRecebida(NamedTuple):
    """Mensagem ja decifrada. ``conteudo`` e ``None`` e ``ilegivel`` verdadeiro
    quando a decifragem falha."""

    mid: Optional[str]
    remetente: Any
    ts: Optional[str]
    tipo: Optional[str]
    conteudo: Optional[str]
    ilegivel: bool


class EventoEstado(NamedTuple):
    """Mudanca de estado. ``detalhe`` traz ``tentativa``, ``maximo`` e
    ``atraso_ms`` em "reconectando" e ``motivo`` em "desconectado"."""

    estado: str
    detalhe: dict


class EventoMensagem(NamedTuple):
    mensagem: MensagemRecebida


class EventoHistorico(NamedTuple):
    lote: int
    ultimo: bool
    mensagens: list


class EventoReautenticado(NamedTuple):
    """Relogin automatico bem-sucedido apos reconexao."""

    perfil: Any


class EventoErro(NamedTuple):
    """Falha que nao pertence a nenhuma requisicao (quadro invalido, mensagem
    que nao decifra, erro de rede). Nunca carrega conteudo legivel."""

    erro: Exception
    contexto: str


def novo_id(prefixo: str) -> str:
    """Identificador de correlacao (ate 64 caracteres)."""
    return f"{prefixo}-{uuid.uuid4().hex[:16]}"


class _Pendente:
    __slots__ = ("futuro", "prazo")

    def __init__(self, futuro: Future, prazo: float) -> None:
        self.futuro = futuro
        self.prazo = prazo


def _completar(futuro: Future, valor: Any = None, erro: Optional[BaseException] = None) -> None:
    try:
        if erro is not None:
            futuro.set_exception(erro)
        else:
            futuro.set_result(valor)
    except InvalidStateError:
        pass  # ja concluido ou cancelado


def _futuro_falho(erro: BaseException) -> Future:
    futuro: Future = Future()
    futuro.set_exception(erro)
    return futuro


def _mapear(origem: Future, funcao: Callable[[Any], Any]) -> Future:
    """Futuro derivado: aplica ``funcao`` ao resultado de ``origem``."""
    destino: Future = Future()

    def pronto(f: Future) -> None:
        erro = f.exception()
        if erro is not None:
            _completar(destino, erro=erro)
            return
        try:
            _completar(destino, funcao(f.result()))
        except Exception as e:  # noqa: BLE001
            _completar(destino, erro=e)

    origem.add_done_callback(pronto)
    return destino


def _criar_socket_padrao(host: str, porta: int, tempo_limite: float) -> socket.socket:
    sock = socket.create_connection((host, porta), timeout=tempo_limite)
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    return sock


# ---------------------------------------------------------------------------
# Conexao
# ---------------------------------------------------------------------------

class Conexao:
    """Conexao TCP com o Middleware. Segura para uso a partir da thread da interface.

    Metodos publicos nao bloqueiam: devolvem ``Future`` ou apenas publicam
    eventos em ``fila``.
    """

    def __init__(
        self,
        chave_transito: bytes,
        dispositivo: str,
        *,
        host: str = "localhost",
        porta: int = PORTA_TCP,
        fila: Optional["queue.Queue[Any]"] = None,
        tipo_cliente: str = "python",
        versao_cliente: str = "1",
        intervalo_ping: float = INTERVALO_PING_S,
        tempo_limite_requisicao: float = TEMPO_LIMITE_REQUISICAO_S,
        tempo_limite_conexao: float = TEMPO_LIMITE_CONEXAO_S,
        atraso_fn: Callable[[int], float] = atraso_com_jitter,
        criar_socket: Callable[[str, int, float], socket.socket] = _criar_socket_padrao,
    ) -> None:
        if not chave_transito or len(chave_transito) != TAMANHO_CHAVE:
            raise ErroRequisicao("SEM_CHAVE", "Chave_Transito ausente ou invalida")
        self._chave = bytes(chave_transito)
        self._dispositivo = dispositivo
        self._host = host
        self._porta = porta
        self.fila: "queue.Queue[Any]" = fila if fila is not None else queue.Queue()
        self._tipo_cliente = tipo_cliente
        self._versao_cliente = versao_cliente
        self._intervalo_ping = intervalo_ping
        self._tempo_limite_requisicao = tempo_limite_requisicao
        self._tempo_limite_conexao = tempo_limite_conexao
        self._atraso_fn = atraso_fn
        self._criar_socket = criar_socket

        # Ordem de aquisicao: _lock_envio -> _lock. Futuros nunca sao
        # concluidos com algum lock tomado.
        self._lock = threading.RLock()
        self._lock_envio = threading.Lock()
        self._estado = EstadoConexao.DESCONECTADO
        self._sock: Optional[socket.socket] = None
        self._pendentes: dict[str, _Pendente] = {}
        self._abertura: Optional[Future] = None
        self._tentativas = 0
        self._credenciais: Optional[tuple[str, str]] = None  # somente em memoria
        self._motivo_final: Optional[str] = None
        self._ativa = False
        self._parar = threading.Event()

    def __repr__(self) -> str:  # nunca expoe chave nem credenciais
        return f"<Conexao {self._host}:{self._porta} estado={self._estado}>"

    # ----- consulta ----------------------------------------------------------

    @property
    def estado(self) -> str:
        return self._estado

    @property
    def tentativas(self) -> int:
        """Tentativas consecutivas de reconexao ja realizadas."""
        return self._tentativas

    @property
    def aberta(self) -> bool:
        return self._sock is not None

    # ----- API publica -------------------------------------------------------

    def conectar(self) -> Future:
        """Abre o socket em segundo plano. O futuro conclui quando aberto e
        falha com ``ErroRequisicao`` se a abertura falhar."""
        with self._lock:
            if self._sock is not None:
                futuro: Future = Future()
                futuro.set_result(None)
                return futuro
            if self._ativa:
                if self._abertura is not None and not self._abertura.done():
                    return self._abertura
                return _futuro_falho(ErroRequisicao("SEM_CONEXAO", "Reconexao em andamento"))
            abertura: Future = Future()
            self._abertura = abertura
            self._definir_estado(EstadoConexao.CONECTANDO)
            self._iniciar_thread(abertura, automatico=False, atrasar_primeira=False)
            return abertura

    def registrar(self, campos: dict) -> Future:
        """Cadastro; ``dados`` e a cifra (Transito) do JSON de ``campos``
        (``login``, ``apelido``, ``senha``, ``avatar``, ``cor``). Abre a
        conexao se preciso. O futuro traz a resposta ``ok:true``."""
        return self._em_segundo_plano(self._registrar_sync, campos)

    def login(self, login: str, senha: str) -> Future:
        """Login; ``dados`` e a cifra (Transito) do JSON ``{login, senha}``.
        Em caso de sucesso, guarda login e senha somente em memoria para o
        relogin automatico. O futuro traz ``{"sessao", "perfil"}``."""
        return self._em_segundo_plano(self._login_sync, login, senha)

    def enviar_mensagem(self, tipo: str, conteudo: str) -> Future:
        """``send_message`` com ``conteudo`` cifrado (texto ou id de figurinha).
        O futuro traz ``{"mid", "ts"}``."""
        id_ = novo_id("msg")
        try:
            quadro = protocolo.montar_send_message(id_, tipo, cifrar(self._chave, conteudo))
        except (TypeError, ValueError):
            return _futuro_falho(ErroRequisicao(CodigoErro.CAMPO_INVALIDO, "Conteudo invalido"))
        return _mapear(
            self._requisitar(id_, quadro),
            lambda r: {"mid": r.get("mid"), "ts": r.get("ts")},
        )

    def solicitar_historico(self, desde: Optional[str] = None) -> Future:
        """``history``. Os lotes chegam como ``EventoHistorico``; o futuro
        conclui ao receber o ultimo lote."""
        id_ = novo_id("his")
        return self._requisitar(id_, protocolo.montar_history(id_, desde))

    def enviar_error_report(self, eventos: list) -> Future:
        """``error_report`` (nao exige login). O futuro traz a lista ``aceitos``."""
        id_ = novo_id("err")
        return _mapear(
            self._requisitar(id_, protocolo.montar_error_report(id_, eventos)),
            lambda r: list(r.get("aceitos") or []),
        )

    def logout(self) -> Future:
        """Avisa o Middleware, apaga as credenciais da memoria e nao reconecta."""
        return self._em_segundo_plano(self._logout_sync)

    def fechar(self) -> None:
        """Fecha a conexao sem reconectar e apaga as credenciais da memoria."""
        with self._lock_envio:
            with self._lock:
                self._parar.set()
                self._credenciais = None
                self._tentativas = 0
                self._ativa = False
                sock, self._sock = self._sock, None
                abertura, self._abertura = self._abertura, None
        if sock is not None:
            self._derrubar(sock)
        if abertura is not None and not abertura.done():
            _completar(abertura, erro=ErroRequisicao("CONEXAO_PERDIDA", "Conexao encerrada"))
        self._rejeitar_pendentes(ErroRequisicao("CONEXAO_PERDIDA", "Conexao encerrada"))
        with self._lock:
            self._definir_estado(EstadoConexao.DESCONECTADO, {"motivo": "encerrado"})

    def tentar_novamente(self) -> bool:
        """Depois de esgotar as tentativas (estado "desconectado" com
        credenciais em memoria), zera o contador e reconecta de novo.
        Devolve ``False`` se nao havia o que retomar."""
        with self._lock:
            if self._credenciais is None or self._ativa:
                return False
            self._tentativas = 0
            self._iniciar_thread(None, automatico=True, atrasar_primeira=True)
            return True

    # ----- operacoes compostas (rodam fora da thread da interface) -----------

    def _em_segundo_plano(self, funcao: Callable[..., Any], *args: Any) -> Future:
        futuro: Future = Future()

        def alvo() -> None:
            try:
                _completar(futuro, funcao(*args))
            except Exception as e:  # noqa: BLE001
                _completar(futuro, erro=e)

        threading.Thread(target=alvo, name="comunicador-op", daemon=True).start()
        return futuro

    def _garantir_conexao(self) -> None:
        if self.aberta:
            return
        try:
            self.conectar().result(timeout=self._tempo_limite_conexao + 5.0)
        except TimeoutError:
            raise ErroRequisicao("TEMPO_ESGOTADO", "Tempo esgotado ao conectar") from None

    def _registrar_sync(self, campos: dict) -> dict:
        self._garantir_conexao()
        id_ = novo_id("reg")
        dados = cifrar(self._chave, json.dumps(campos, ensure_ascii=False, separators=(",", ":")))
        return self._requisitar(id_, protocolo.montar_register(id_, dados)).result()

    def _login_sync(self, login: str, senha: str) -> dict:
        self._garantir_conexao()
        resposta = self._enviar_login(login, senha).result()
        with self._lock:
            self._credenciais = (login, senha)
            self._tentativas = 0
        if not self.aberta:  # caiu entre a resposta e o registro das credenciais
            with self._lock:
                self._credenciais = None
            raise ErroRequisicao("CONEXAO_PERDIDA", "Conexao perdida")
        with self._lock:
            self._definir_estado(EstadoConexao.AUTENTICADO)
        return {"sessao": resposta.get("sessao"), "perfil": resposta.get("perfil")}

    def _logout_sync(self) -> None:
        falha: Optional[ErroRequisicao] = None
        try:
            if self.aberta:
                id_ = novo_id("out")
                try:
                    self._requisitar(id_, protocolo.montar_logout(id_)).result()
                except ErroRequisicao as e:
                    falha = e
        finally:
            self.fechar()
        if falha is not None and falha.codigo != "CONEXAO_PERDIDA":
            raise falha

    def _enviar_login(self, login: str, senha: str) -> Future:
        id_ = novo_id("log")
        dados = cifrar(self._chave, json.dumps({"login": login, "senha": senha}, ensure_ascii=False, separators=(",", ":")))
        quadro = protocolo.montar_login(
            id_, dados, self._dispositivo, self._tipo_cliente, self._versao_cliente
        )
        return self._requisitar(id_, quadro)

    # ----- requisicoes -------------------------------------------------------

    def _requisitar(self, id_: str, quadro: dict) -> Future:
        futuro: Future = Future()
        try:
            dados = protocolo.delimitar(quadro)
        except (TypeError, ValueError):
            return _futuro_falho(ErroRequisicao(CodigoErro.CAMPO_INVALIDO, "Requisicao invalida"))
        pendente = _Pendente(futuro, time.monotonic() + self._tempo_limite_requisicao)
        erro = self._transmitir(dados, id_, pendente)
        if erro is not None:
            _completar(futuro, erro=erro)
        return futuro

    def _transmitir(self, dados: bytes, id_: Optional[str], pendente: Optional[_Pendente]) -> Optional[ErroRequisicao]:
        """Envia ``dados`` pelo socket atual. Devolve o erro, ou ``None`` se enviou."""
        with self._lock_envio:
            sock = self._sock
            if sock is None:
                return ErroRequisicao("SEM_CONEXAO", "Sem conexao com o servidor")
            if id_ is not None and pendente is not None:
                with self._lock:
                    self._pendentes[id_] = pendente
            try:
                sock.sendall(dados)
                return None
            except OSError:
                if id_ is not None:
                    with self._lock:
                        self._pendentes.pop(id_, None)
        # Falha no meio do envio pode deixar o fluxo corrompido: derruba a conexao.
        self._derrubar(sock)
        return ErroRequisicao("SEM_CONEXAO", "Falha ao enviar")

    def _concluir(self, id_: str, ok: bool, valor: Any) -> bool:
        with self._lock:
            pendente = self._pendentes.pop(id_, None)
        if pendente is None:
            return False
        if ok:
            _completar(pendente.futuro, valor)
        else:
            _completar(pendente.futuro, erro=valor)
        return True

    def _rejeitar_pendentes(self, erro: ErroRequisicao) -> None:
        with self._lock:
            pendentes, self._pendentes = self._pendentes, {}
        for p in pendentes.values():
            _completar(p.futuro, erro=erro)

    def _expirar(self, agora: float) -> None:
        with self._lock:
            vencidos = [i for i, p in self._pendentes.items() if p.prazo <= agora]
            itens = [self._pendentes.pop(i) for i in vencidos]
        for p in itens:
            _completar(p.futuro, erro=ErroRequisicao("TEMPO_ESGOTADO", "O servidor nao respondeu a tempo"))

    # ----- thread de rede ----------------------------------------------------

    def _iniciar_thread(self, abertura: Optional[Future], *, automatico: bool, atrasar_primeira: bool) -> None:
        """Chamar com ``_lock`` tomado."""
        parar = threading.Event()
        self._parar = parar
        self._ativa = True
        self._motivo_final = None
        threading.Thread(
            target=self._executar,
            args=(parar, abertura, automatico, atrasar_primeira),
            name="comunicador-rede",
            daemon=True,
        ).start()

    def _executar(self, parar: threading.Event, abertura: Optional[Future], automatico: bool, atrasar_primeira: bool) -> None:
        try:
            if atrasar_primeira and not self._aguardar_backoff(parar):
                return
            while not parar.is_set():
                sock = self._abrir()
                if sock is not None:
                    abertura = None
                    if not self._publicar(sock, parar, automatico):
                        self._fechar_socket(sock)
                        return
                    try:
                        self._ler(sock, parar)
                    finally:
                        self._liberar(sock)
                elif abertura is not None:
                    _completar(abertura, erro=ErroRequisicao("SEM_CONEXAO", "Nao foi possivel abrir a conexao"))
                    abertura = None
                if parar.is_set():
                    return
                with self._lock:
                    tem_credenciais = self._credenciais is not None
                    motivo = self._motivo_final
                if not tem_credenciais:
                    if motivo is None:
                        motivo = "conexao_perdida" if sock is not None else "falha_ao_abrir"
                    self._finalizar(parar, motivo)
                    return
                automatico = True
                if not self._aguardar_backoff(parar):
                    return
        except Exception as e:  # noqa: BLE001 - a thread de rede nunca morre em silencio
            self._erro(e, "rede")
            if abertura is not None:
                _completar(abertura, erro=ErroRequisicao("SEM_CONEXAO", "Falha na conexao"))
            self._finalizar(parar, "erro_interno")

    def _abrir(self) -> Optional[socket.socket]:
        try:
            return self._criar_socket(self._host, self._porta, self._tempo_limite_conexao)
        except OSError:
            return None

    def _publicar(self, sock: socket.socket, parar: threading.Event, automatico: bool) -> bool:
        """Torna ``sock`` o socket atual. Devolve ``False`` se ``fechar()`` ocorreu antes."""
        with self._lock_envio:
            with self._lock:
                if parar.is_set():
                    return False
                self._sock = sock
                abertura, self._abertura = self._abertura, None
                relogin = automatico and self._credenciais is not None
                if self._credenciais is None:
                    self._tentativas = 0
                if self._estado != EstadoConexao.RECONECTANDO:
                    self._definir_estado(EstadoConexao.CONECTADO)
        if abertura is not None:
            _completar(abertura, None)
        if relogin:
            self._iniciar_relogin(sock)
        return True

    def _liberar(self, sock: socket.socket) -> None:
        with self._lock_envio:
            with self._lock:
                if self._sock is sock:
                    self._sock = None
        self._fechar_socket(sock)
        self._rejeitar_pendentes(ErroRequisicao("CONEXAO_PERDIDA", "Conexao perdida"))

    def _ler(self, sock: socket.socket, parar: threading.Event) -> None:
        leitor = LeitorDeLinhas()
        proximo_ping = time.monotonic() + self._intervalo_ping
        while not parar.is_set():
            try:
                prontos, _, _ = select.select([sock], [], [], _SONDA_S)
                if prontos:
                    dados = sock.recv(65536)
                    if not dados:
                        return
                    for linha in leitor.alimentar(dados):
                        self._tratar_linha(linha)
            except (OSError, ValueError):
                return
            agora = time.monotonic()
            self._expirar(agora)
            if agora >= proximo_ping:
                proximo_ping = agora + self._intervalo_ping
                self._transmitir(protocolo.delimitar(protocolo.montar_ping(novo_id("p"))), None, None)
            if self._sock is not sock:
                return

    def _aguardar_backoff(self, parar: threading.Event) -> bool:
        """Espera o atraso da proxima tentativa. ``False`` se deve encerrar a thread."""
        with self._lock:
            n = self._tentativas
            esgotado = not pode_tentar_novamente(n)
            if not esgotado:
                self._tentativas = n + 1
        if esgotado:
            self._finalizar(parar, "tentativas_esgotadas")
            return False
        atraso_ms = float(self._atraso_fn(n))
        with self._lock:
            if parar.is_set():
                return False
            self._definir_estado(
                EstadoConexao.RECONECTANDO,
                {"tentativa": n + 1, "maximo": MAXIMO_TENTATIVAS, "atraso_ms": atraso_ms},
            )
        return not parar.wait(max(0.0, atraso_ms) / 1000.0)

    def _finalizar(self, parar: threading.Event, motivo: str) -> None:
        with self._lock:
            if self._parar is not parar or parar.is_set():
                return
            self._ativa = False
            self._definir_estado(EstadoConexao.DESCONECTADO, {"motivo": motivo})

    # ----- relogin automatico ------------------------------------------------

    def _iniciar_relogin(self, sock: socket.socket) -> None:
        with self._lock:
            credenciais = self._credenciais
        if credenciais is None:
            return
        futuro = self._enviar_login(*credenciais)
        futuro.add_done_callback(lambda f: self._fim_relogin(sock, f))

    def _fim_relogin(self, sock: socket.socket, futuro: Future) -> None:
        erro = futuro.exception()
        if erro is None:
            resposta = futuro.result()
            with self._lock:
                if self._sock is not sock:
                    return
                self._tentativas = 0
                self._definir_estado(EstadoConexao.AUTENTICADO)
            self.fila.put(EventoReautenticado(resposta.get("perfil")))
            return
        if isinstance(erro, ErroRequisicao):
            if erro.codigo == CodigoErro.CREDENCIAIS_INVALIDAS:
                # Senha ou conta mudou: reconectar nao adianta.
                with self._lock:
                    self._credenciais = None
                    self._motivo_final = "credenciais_invalidas"
                self._derrubar(sock)
                return
            if erro.codigo == "CONEXAO_PERDIDA":
                return  # a thread de rede ja trata a queda
        # Outro erro (por exemplo SERVICO_INDISPONIVEL): derruba o socket e segue o backoff.
        self._erro(erro, "relogin")
        self._derrubar(sock)

    # ----- recepcao ----------------------------------------------------------

    def _tratar_linha(self, linha: protocolo.Linha) -> None:
        try:
            if linha.muito_grande:
                self._erro(ErroDeProtocolo(CodigoErro.MENSAGEM_MUITO_GRANDE), "quadro_invalido")
                return
            if not linha.conteudo.strip():
                return
            try:
                quadro = protocolo.interpretar(linha.conteudo)
            except ErroDeProtocolo as e:
                self._erro(e, "quadro_invalido")
                return
            op = quadro["op"]
            if op == protocolo.OP_MESSAGE:
                self.fila.put(EventoMensagem(self._decifrar_mensagem(quadro.get("mensagem"))))
            elif op == protocolo.OP_HISTORY:
                self._ao_historico(quadro)
            elif op == protocolo.OP_ERRO:
                self._erro(self._erro_de(quadro), "erro_geral")
            else:
                self._ao_resposta(quadro)
        except Exception as e:  # noqa: BLE001
            self._erro(e, "tratar_quadro")

    def _ao_resposta(self, quadro: dict) -> None:
        id_ = quadro.get("id")
        if not isinstance(id_, str):
            return
        if protocolo.eh_resposta_ok(quadro):
            self._concluir(id_, True, quadro)
        else:
            self._concluir(id_, False, self._erro_de(quadro))

    def _ao_historico(self, quadro: dict) -> None:
        id_ = quadro.get("id")
        if not protocolo.eh_resposta_ok(quadro):
            if isinstance(id_, str):
                self._concluir(id_, False, self._erro_de(quadro))
            return
        brutas = quadro.get("mensagens")
        if not isinstance(brutas, list):
            raise ErroDeProtocolo(CodigoErro.FORMATO_INVALIDO)
        lote = quadro.get("lote")
        ultimo = quadro.get("ultimo") is True
        mensagens = [self._decifrar_mensagem(m) for m in brutas]
        self.fila.put(EventoHistorico(lote if isinstance(lote, int) else 0, ultimo, mensagens))
        if not isinstance(id_, str):
            return
        if ultimo:
            self._concluir(id_, True, quadro)
        else:
            with self._lock:  # ha mais lotes a caminho: renova o prazo
                pendente = self._pendentes.get(id_)
                if pendente is not None:
                    pendente.prazo = time.monotonic() + self._tempo_limite_requisicao

    def _decifrar_mensagem(self, bruta: Any) -> MensagemRecebida:
        if not isinstance(bruta, dict):
            raise ErroDeProtocolo(CodigoErro.FORMATO_INVALIDO)
        conteudo: Optional[str] = None
        ilegivel = False
        try:
            cifrado = bruta.get("conteudo")
            if not isinstance(cifrado, str):
                raise DecifragemFalhou("conteudo ausente")
            conteudo = decifrar(self._chave, cifrado)
        except DecifragemFalhou:
            ilegivel = True
            self._erro(
                ErroRequisicao(CodigoErro.DECIFRAGEM_FALHOU, "Mensagem recebida nao pode ser decifrada"),
                "decifrar",
            )
        return MensagemRecebida(
            mid=bruta.get("mid"),
            remetente=bruta.get("remetente"),
            ts=bruta.get("ts"),
            tipo=bruta.get("tipo"),
            conteudo=conteudo,
            ilegivel=ilegivel,
        )

    @staticmethod
    def _erro_de(quadro: dict) -> ErroRequisicao:
        erro = quadro.get("erro")
        codigo = CodigoErro.ERRO_INTERNO
        mensagem = protocolo.MENSAGENS_DE_ERRO[CodigoErro.ERRO_INTERNO]
        campo = None
        if isinstance(erro, dict):
            if isinstance(erro.get("codigo"), str):
                codigo = erro["codigo"][:64]
                mensagem = protocolo.MENSAGENS_DE_ERRO.get(codigo, mensagem)
            if isinstance(erro.get("campo"), str):
                campo = erro["campo"][:64]
        return ErroRequisicao(codigo, mensagem, campo)

    # ----- utilitarios -------------------------------------------------------

    def _definir_estado(self, estado: str, detalhe: Optional[dict] = None) -> None:
        """Atualiza o estado e publica o evento (chamar com ``_lock`` tomado,
        para que a ordem dos eventos siga a ordem das mudancas)."""
        self._estado = estado
        self.fila.put(EventoEstado(estado, detalhe or {}))

    def _erro(self, erro: Exception, contexto: str) -> None:
        try:
            self.fila.put(EventoErro(erro, contexto))
        except Exception:  # noqa: BLE001 - falha ao reportar nunca se propaga
            pass

    @staticmethod
    def _derrubar(sock: socket.socket) -> None:
        """Interrompe o fluxo; quem le o socket o fecha ao perceber."""
        try:
            sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass

    @staticmethod
    def _fechar_socket(sock: socket.socket) -> None:
        try:
            sock.close()
        except OSError:
            pass
