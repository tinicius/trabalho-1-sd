"""Aplicacao Tkinter do Cliente_Terceiro: orquestra telas, rede, historico e erros.

Fluxo:

1. ``Aplicacao`` cria o ``ReportadorErros`` e liga a captura global
   (``sys.excepthook``, ``threading.excepthook`` e ``Tk.report_callback_exception``
   da raiz).
2. No login, le a Chave_Transito e o identificador do dispositivo, cria a
   ``Conexao`` e chama ``login`` (em segundo plano). O resultado e consultado
   pelo laco de ``after(100, ...)``, sem bloquear a interface.
3. Com o login confirmado, abre o Historico_Local do usuario, exibe o que ele ja
   guarda, mostra a sala e chama ``reportador.apos_login()``. Depois disso, as
   mensagens e lotes de historico chegam pela ``queue.Queue`` da conexao e sao
   tratados por ``ProcessadorEventos`` (sem duplicatas).
4. Apos reconexao com relogin automatico, ``apos_login()`` e chamado de novo.

Nada aqui e chamado fora da thread da interface: a thread de rede so escreve
na fila e conclui ``Future``; callbacks que vem de outras threads passam por
``self._tarefas`` e rodam no laco.
"""
from __future__ import annotations

import argparse
import queue
import sys
import tkinter as tk
from concurrent.futures import Future
from dataclasses import dataclass
from pathlib import Path
from tkinter import ttk
from typing import Any, Callable, Mapping, Optional, Sequence, Union

from comunicador.gui.imagens import CacheImagens
from comunicador.gui.modelo import (
    ApresentacaoEstado,
    ListaMensagens,
    ResultadoExibicao,
    apresentar_estado,
    eh_login_valido,
    mensagem_de_erro,
    validar_cadastro,
    validar_credenciais,
)
from comunicador.gui.processador import ProcessadorEventos, Saidas
from comunicador.gui.telas import AcoesSala, TelaCadastro, TelaLogin, TelaSala
from comunicador.nucleo import config
from comunicador.nucleo.conexao import PORTA_TCP, Conexao, ErroRequisicao, EstadoConexao, EventoErro
from comunicador.nucleo.config import ErroDeConfiguracao
from comunicador.nucleo.emoticons import TabelaConversao
from comunicador.nucleo.envio import ControladorEnvio, MotivoNaoEnviado, ResultadoEnvio
from comunicador.nucleo.erros import ReportadorErros, criar_reportador_erros, instalar_captura_global
from comunicador.nucleo.historico import ErroHistorico, abrir_historico

INTERVALO_FILA_MS = 100
"""Periodo com que a interface consulta a fila de eventos da rede."""

TITULO = "Comunicador Distribuído"

AVISO_ENVIO_LONGO = "A mensagem passa de 2000 caracteres. Encurte o texto para enviar."


@dataclass
class Sessao:
    """O que existe enquanto o usuario esta na sala."""

    perfil: Mapping[str, Any]
    historico: Optional[Any]
    lista: ListaMensagens
    processador: ProcessadorEventos
    controlador: ControladorEnvio


class Aplicacao:
    """Janela principal do Cliente_Terceiro.

    ``raiz`` e um ``tk.Tk`` (pode estar oculto com ``withdraw``). ``reportador``
    e ``fabrica_conexao`` existem para teste; por padrao usam o buffer em
    ``<raiz>/.comunicador/python/`` e a ``Conexao`` TCP.
    """

    def __init__(
        self,
        raiz: tk.Tk,
        *,
        host: str = "localhost",
        porta: int = PORTA_TCP,
        recursos: Optional[Union[str, Path]] = None,
        modo_texto: bool = False,
        reportador: Optional[ReportadorErros] = None,
        fabrica_conexao: Optional[Callable[[bytes, str], Conexao]] = None,
    ) -> None:
        self.raiz = raiz
        self._host = host
        self._porta = porta
        self._recursos = recursos
        self._fabrica_conexao = fabrica_conexao or self._criar_conexao_tcp

        self._dispositivo: Optional[str] = None
        self._chave: Optional[bytes] = None
        self._conexao: Optional[Conexao] = None
        self._sessao: Optional[Sessao] = None
        self._login_em_andamento = False
        self._saindo = False
        self._encerrada = False
        self._id_laco: Optional[str] = None
        self._futuros: list[tuple[Future, Callable[[Future], None]]] = []
        self._tarefas: "queue.Queue[Callable[[], None]]" = queue.Queue()

        raiz.title(TITULO)
        raiz.geometry("680x560")
        raiz.minsize(520, 420)
        raiz.protocol("WM_DELETE_WINDOW", self.fechar)

        # Erros: reportador e captura global ligados a esta raiz.
        self.reportador = reportador if reportador is not None else criar_reportador_erros(
            dispositivo=lambda: self._dispositivo
        )
        self._desfazer_captura = instalar_captura_global(self.reportador, raiz)
        self.reportador.iniciar()

        avisos = self._carregar_recursos()
        self.imagens = CacheImagens(raiz, recursos, modo_texto=modo_texto)

        quadro = ttk.Frame(raiz)
        quadro.pack(fill="both", expand=True)
        quadro.columnconfigure(0, weight=1)
        quadro.rowconfigure(0, weight=1)
        self.tela_login = TelaLogin(quadro, self.entrar, self.abrir_cadastro)
        self.tela_cadastro = TelaCadastro(
            quadro, self.imagens, self._avatares, self.cadastrar, self.abrir_login
        )
        self.tela_sala = TelaSala(
            quadro,
            self.imagens,
            AcoesSala(
                enviar=self.enviar,
                tecla=self.ao_tecla,
                emoji=self.inserir_emoji,
                figurinha=self.enviar_figurinha,
                sair=self.sair,
                tentar_novamente=self.tentar_novamente,
            ),
            emojis=self._emojis,
            emojis_conhecidos=self._emojis_conhecidos,
            figurinhas=self._figurinhas,
        )
        for tela in (self.tela_login, self.tela_cadastro, self.tela_sala):
            tela.grid(row=0, column=0, sticky="nsew")
        self.abrir_login()
        if avisos:
            self.tela_login.mostrar_erro(" ".join(avisos))
        self._id_laco = raiz.after(INTERVALO_FILA_MS, self._laco)

    # ----- recursos e conexao --------------------------------------------------

    def _carregar_recursos(self) -> list[str]:
        """Carrega tabela, emojis, figurinhas e avatares. Uma falha nao impede
        a janela de abrir: o recurso fica vazio, o erro e reportado e um aviso
        aparece na tela de login."""
        avisos: list[str] = []

        def carregar(nome: str, funcao: Callable[[], Any], padrao: Any) -> Any:
            try:
                return funcao()
            except ErroDeConfiguracao as e:
                self.reportador.reportar(e, modulo="config.py")
                avisos.append(f"{nome}: {e}.")
                return padrao

        self._tabela: TabelaConversao = carregar(
            "Tabela de emoticons", lambda: config.carregar_tabela_emoticons(self._recursos), TabelaConversao([])
        )
        self._emojis: tuple[str, ...] = carregar(
            "Emojis", lambda: config.carregar_emojis(self._recursos), ()
        )
        self._figurinhas: tuple[str, ...] = carregar(
            "Figurinhas", lambda: config.listar_figurinhas(self._recursos), ()
        )
        self._avatares: tuple[str, ...] = carregar(
            "Avatares", lambda: config.listar_avatares(self._recursos), ()
        )
        self._emojis_conhecidos = tuple(self._emojis) + tuple(p.emoji for p in self._tabela.pares)
        return avisos

    def _criar_conexao_tcp(self, chave: bytes, dispositivo: str) -> Conexao:
        return Conexao(chave, dispositivo, host=self._host, porta=self._porta)

    def _obter_conexao(self) -> Conexao:
        """Cria a conexao na primeira vez que for preciso. Levanta
        ``ErroDeConfiguracao`` se a Chave_Transito ou o dispositivo falharem."""
        if self._conexao is None:
            chave = config.ler_chave_transito()
            dispositivo = config.obter_id_dispositivo()
            self._chave = chave
            self._dispositivo = dispositivo
            self._conexao = self._fabrica_conexao(chave, dispositivo)
            self.reportador.definir_conexao(self._conexao)
        return self._conexao

    # ----- laco de eventos -----------------------------------------------------

    def _laco(self) -> None:
        """Roda a cada 100 ms: tarefas de outras threads, futuros concluidos e
        eventos da fila de rede. Nunca deixa de se reagendar."""
        try:
            self._id_laco = None
            self._executar_tarefas()
            self._verificar_futuros()
            self._processar_fila()
        except Exception as e:  # noqa: BLE001
            self.reportador.reportar(e, modulo="app.py")
        finally:
            if not self._encerrada:
                self._id_laco = self.raiz.after(INTERVALO_FILA_MS, self._laco)

    def _executar_tarefas(self) -> None:
        for _ in range(100):
            try:
                tarefa = self._tarefas.get_nowait()
            except queue.Empty:
                return
            try:
                tarefa()
            except Exception as e:  # noqa: BLE001
                self.reportador.reportar(e, modulo="app.py")

    def _verificar_futuros(self) -> None:
        prontos = [item for item in self._futuros if item[0].done()]
        if not prontos:
            return
        self._futuros = [item for item in self._futuros if not item[0].done()]
        for futuro, ao_concluir in prontos:
            try:
                ao_concluir(futuro)
            except Exception as e:  # noqa: BLE001
                self.reportador.reportar(e, modulo="app.py")

    def _processar_fila(self) -> None:
        conexao = self._conexao
        if conexao is None:
            return
        if self._sessao is not None:
            self._sessao.processador.drenar(conexao.fila)
        elif not self._login_em_andamento:
            # Fora da sala so interessam os erros; o resto e descartado.
            self._descartar_fila(conexao)

    def _descartar_fila(self, conexao: Conexao) -> None:
        for _ in range(200):
            try:
                evento = conexao.fila.get_nowait()
            except queue.Empty:
                return
            if isinstance(evento, EventoErro):
                self._erro_interno(evento.erro, f"conexao.py:{evento.contexto}")

    def aguardar(self, futuro: Future, ao_concluir: Callable[[Future], None]) -> None:
        """Chama ``ao_concluir(futuro)`` na thread da interface quando ``futuro`` terminar."""
        self._futuros.append((futuro, ao_concluir))

    # ----- navegacao -----------------------------------------------------------

    def abrir_login(self) -> None:
        self.tela_login.tkraise()
        self.tela_login.focar()

    def abrir_cadastro(self) -> None:
        self.tela_cadastro.mostrar_erro("")
        self.tela_cadastro.tkraise()
        self.tela_cadastro.campo_login.focus_set()

    def _mostrar_sala(self) -> None:
        self.tela_sala.tkraise()
        self.tela_sala.focar_campo()

    # ----- login e cadastro ----------------------------------------------------

    def entrar(self, login: str, senha: str) -> None:
        if self._login_em_andamento or self._saindo or self._sessao is not None:
            return
        login = login.strip().lower()
        problema = validar_credenciais(login, senha)
        if problema:
            self.tela_login.mostrar_erro(problema)
            return
        try:
            conexao = self._obter_conexao()
        except ErroDeConfiguracao as e:
            self.tela_login.mostrar_erro(str(e))
            return
        self._login_em_andamento = True
        self.tela_login.definir_ocupado(True)
        self.tela_login.mostrar_info("Entrando…")
        self.aguardar(conexao.login(login, senha), self._fim_login)

    def _fim_login(self, futuro: Future) -> None:
        self._login_em_andamento = False
        self.tela_login.definir_ocupado(self._saindo)
        erro = futuro.exception()
        if erro is not None:
            if not isinstance(erro, ErroRequisicao):
                self.reportador.reportar(erro, modulo="conexao.py:login")
            self.tela_login.mostrar_erro(mensagem_de_erro(erro))
            return
        self.tela_login.limpar_senha()
        self.tela_login.mostrar_erro("")
        self._iniciar_sessao(futuro.result().get("perfil"))

    def cadastrar(self, campos: Mapping[str, str]) -> None:
        problema = validar_cadastro(campos)
        if problema:
            self.tela_cadastro.mostrar_erro(problema)
            return
        try:
            conexao = self._obter_conexao()
        except ErroDeConfiguracao as e:
            self.tela_cadastro.mostrar_erro(str(e))
            return
        self.tela_cadastro.definir_ocupado(True)
        self.tela_cadastro.mostrar_erro("")
        self.aguardar(conexao.registrar(dict(campos)), lambda f: self._fim_cadastro(f, campos["login"]))

    def _fim_cadastro(self, futuro: Future, login: str) -> None:
        self.tela_cadastro.definir_ocupado(False)
        erro = futuro.exception()
        if erro is not None:
            if not isinstance(erro, ErroRequisicao):
                self.reportador.reportar(erro, modulo="conexao.py:register")
            self.tela_cadastro.mostrar_erro(mensagem_de_erro(erro))
            return
        self.tela_cadastro.limpar()
        self.tela_login.definir_login(login)
        self.tela_login.limpar_senha()
        self.tela_login.mostrar_info("Conta criada. Entre com seu login e sua senha.")
        self.abrir_login()

    # ----- sessao --------------------------------------------------------------

    def _iniciar_sessao(self, perfil: Any) -> None:
        conexao = self._conexao
        if conexao is None or not isinstance(perfil, Mapping) or not eh_login_valido(perfil.get("login")):
            self.tela_login.mostrar_erro("Resposta de login inválida.")
            return
        historico = None
        aviso = ""
        try:
            historico = abrir_historico(perfil["login"], chave_transito=self._chave)
        except ErroHistorico as e:
            aviso = "Histórico local indisponível. As mensagens não serão guardadas."
            self.reportador.reportar(e, modulo="historico.py")

        sala = self.tela_sala
        sala.limpar()
        controlador = ControladorEnvio(
            self._tabela, conexao.enviar_mensagem, sala.campo, despachar=self._tarefas.put
        )
        lista = ListaMensagens()
        processador = ProcessadorEventos(historico, lista, self._saidas())
        self._sessao = Sessao(perfil, historico, lista, processador, controlador)

        sala.definir_perfil(perfil)
        sala.definir_estado(apresentar_estado(EstadoConexao.AUTENTICADO))
        if aviso:
            sala.mostrar_aviso(aviso)
        processador.carregar_historico_local()
        self._mostrar_sala()
        self.reportador.apos_login()

    def _saidas(self) -> Saidas:
        return Saidas(
            exibir=self._exibir,
            estado=self._ao_estado,
            reautenticado=self._ao_reautenticado,
            aviso=lambda texto: self.tela_sala.mostrar_aviso(texto),
            erro=self._erro_interno,
        )

    def _exibir(self, resultado: ResultadoExibicao, lista: ListaMensagens) -> None:
        self.tela_sala.exibir(resultado, lista)

    def _ao_estado(self, ap: ApresentacaoEstado) -> None:
        self.tela_sala.definir_estado(ap)
        if ap.encerrar_sessao:
            self._encerrar_sessao(ap.aviso)

    def _ao_reautenticado(self, perfil: Any) -> None:
        if self._sessao is not None and isinstance(perfil, Mapping):
            self._sessao.perfil = perfil
            self.tela_sala.definir_perfil(perfil)
        self.tela_sala.mostrar_aviso("")
        self.reportador.apos_login()

    def _erro_interno(self, erro: BaseException, modulo: str) -> None:
        self.reportador.reportar(erro, modulo=modulo)

    def _encerrar_sessao(self, aviso: str = "") -> None:
        sessao, self._sessao = self._sessao, None
        if sessao is not None and sessao.historico is not None:
            try:
                sessao.historico.fechar()
            except Exception as e:  # noqa: BLE001
                self.reportador.reportar(e, modulo="historico.py")
        self.tela_sala.limpar()
        self.tela_login.limpar_senha()
        if aviso:
            self.tela_login.mostrar_erro(aviso)
        else:
            self.tela_login.mostrar_erro("")
        self.abrir_login()

    def sair(self) -> None:
        """Logout: volta ao login e espera o Middleware responder antes de
        liberar um novo login."""
        if self._sessao is None or self._conexao is None:
            return
        conexao = self._conexao
        self._encerrar_sessao()
        self._saindo = True
        self.tela_login.definir_ocupado(True)
        self.tela_login.mostrar_info("Saindo…")
        self.aguardar(conexao.logout(), self._fim_logout)

    def _fim_logout(self, futuro: Future) -> None:
        self._saindo = False
        self.tela_login.definir_ocupado(self._login_em_andamento)
        self.tela_login.mostrar_info("")
        erro = futuro.exception()
        if erro is not None and not isinstance(erro, ErroRequisicao):
            self.reportador.reportar(erro, modulo="conexao.py:logout")

    def tentar_novamente(self) -> None:
        """Botao exibido depois de esgotar as tentativas de reconexao."""
        if self._conexao is None or self._sessao is None:
            return
        if not self._conexao.tentar_novamente():
            self._encerrar_sessao("A conexão foi encerrada. Entre novamente.")

    # ----- envio ---------------------------------------------------------------

    def ao_tecla(self, tecla: str) -> None:
        sessao = self._sessao
        if sessao is None:
            return
        resultado = sessao.controlador.ao_teclar(tecla)
        if resultado is not None:
            self._tratar_resultado(resultado)

    def enviar(self) -> None:
        """Botao Enviar: mesma acao da tecla Enter."""
        if self._sessao is not None:
            self._tratar_resultado(self._sessao.controlador.acionar_enviar())

    def enviar_figurinha(self, id_: str) -> None:
        if self._sessao is None:
            return
        self._tratar_resultado(self._sessao.controlador.enviar_figurinha(id_))
        self.tela_sala.fechar_seletor_figurinhas()

    def inserir_emoji(self, emoji: str) -> None:
        """Seletor_de_Emojis: insere o emoji na posicao do cursor, sem enviar."""
        if self._sessao is None:
            return
        sala = self.tela_sala
        nova = self._sessao.controlador.inserir_emoji_no_campo(emoji, sala.posicao_cursor())
        sala.focar_campo()
        sala.definir_cursor(nova)

    def _tratar_resultado(self, resultado: ResultadoEnvio) -> None:
        sala = self.tela_sala
        if resultado.enviado:
            sala.mostrar_aviso("")
            if resultado.futuro is not None:
                self.aguardar(resultado.futuro, self._fim_envio)
        elif resultado.motivo == MotivoNaoEnviado.MUITO_LONGO:
            sala.mostrar_aviso(AVISO_ENVIO_LONGO)
        elif resultado.motivo == MotivoNaoEnviado.FALHA and resultado.erro is not None:
            self._avisar_falha_de_envio(resultado.erro)
        # EM_BRANCO: mantem o estado atual, sem aviso

    def _fim_envio(self, futuro: Future) -> None:
        erro = futuro.exception()
        if erro is not None:
            self._avisar_falha_de_envio(erro)

    def _avisar_falha_de_envio(self, erro: BaseException) -> None:
        if not isinstance(erro, ErroRequisicao):
            self.reportador.reportar(erro, modulo="envio.py")
        self.tela_sala.mostrar_aviso("Não foi possível enviar: " + mensagem_de_erro(erro))

    # ----- encerramento --------------------------------------------------------

    def fechar(self) -> None:
        """Fecha a janela: para o reportador, desfaz a captura global e fecha a conexao."""
        if self._encerrada:
            return
        self._encerrada = True
        if self._id_laco is not None:
            try:
                self.raiz.after_cancel(self._id_laco)
            except tk.TclError:
                pass
        self.reportador.parar()
        self._desfazer_captura()
        sessao, self._sessao = self._sessao, None
        if sessao is not None and sessao.historico is not None:
            sessao.historico.fechar()
        if self._conexao is not None:
            self._conexao.fechar()
        try:
            self.raiz.destroy()
        except tk.TclError:
            pass


# ---------------------------------------------------------------------------
# Ponto de entrada
# ---------------------------------------------------------------------------

def _argumentos(argv: Optional[Sequence[str]]) -> argparse.Namespace:
    p = argparse.ArgumentParser(
        prog="python -m comunicador",
        description="Cliente_Terceiro (Python + Tkinter) do Comunicador Distribuído de Grupo.",
    )
    p.add_argument("--host", default="localhost", help="endereço do Middleware (padrão: localhost)")
    p.add_argument("--porta", type=int, default=PORTA_TCP, help=f"porta TCP do Middleware (padrão: {PORTA_TCP})")
    p.add_argument("--recursos", default=None, help="pasta recursos/ (padrão: a do repositório)")
    p.add_argument(
        "--modo-texto",
        action="store_true",
        help="não carrega imagens: avatar, cor, emoji e figurinha aparecem por texto",
    )
    return p.parse_args(argv)


def principal(argv: Optional[Sequence[str]] = None) -> int:
    """Abre a janela e roda o laco do Tk. Devolve o codigo de saida."""
    args = _argumentos(argv)
    try:
        raiz = tk.Tk()
    except tk.TclError as e:
        print(f"Não foi possível abrir a interface gráfica (Tk/display): {e}", file=sys.stderr)
        return 1
    Aplicacao(
        raiz,
        host=args.host,
        porta=args.porta,
        recursos=args.recursos,
        modo_texto=args.modo_texto,
    )
    raiz.mainloop()
    return 0
