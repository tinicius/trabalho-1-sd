"""Processamento dos eventos da fila da conexao, sem Tkinter.

A thread de rede (``nucleo.conexao.Conexao``) publica eventos em uma
``queue.Queue``; a interface a consulta com ``after(100, ...)`` e entrega cada
evento a ``ProcessadorEventos``. Aqui acontece tudo o que nao e desenho:

- mensagens e lotes de historico sao incorporados ao Historico_Local (de forma
  idempotente por ``mid``) e entregues a tela sem duplicatas;
- mudancas de estado viram ``ApresentacaoEstado``;
- erros da conexao e falhas do proprio processamento sao entregues a ``erro``
  (na interface, o ``ReportadorErros``).

As saidas sao funcoes injetadas, o que permite testar o fluxo sem display.

"""
from __future__ import annotations

import queue
from dataclasses import dataclass
from typing import Any, Callable, Optional

from comunicador.gui.modelo import (
    ApresentacaoEstado,
    ListaMensagens,
    ResultadoExibicao,
    apresentar_estado,
    normalizar_mensagem,
)
from comunicador.nucleo.conexao import (
    EventoErro,
    EventoEstado,
    EventoHistorico,
    EventoMensagem,
    EventoReautenticado,
)

AVISO_HISTORICO_GRAVAR = "Não foi possível gravar no histórico local."
AVISO_HISTORICO_LER = "Não foi possível ler o histórico local."


@dataclass(frozen=True)
class Saidas:
    """Destinos do processamento (todos chamados na thread da interface)."""

    exibir: Callable[[ResultadoExibicao, ListaMensagens], None]
    estado: Callable[[ApresentacaoEstado], None]
    reautenticado: Callable[[Any], None]
    aviso: Callable[[str], None]
    erro: Callable[[BaseException, str], None]


class ProcessadorEventos:
    """Trata os eventos da conexao de uma sessao.

    ``historico`` e um ``HistoricoLocal`` (ou ``None`` se nao abriu: as
    mensagens aparecem na tela mas nao sao guardadas).
    """

    def __init__(self, historico: Optional[Any], lista: ListaMensagens, saidas: Saidas) -> None:
        self._historico = historico
        self._lista = lista
        self._saidas = saidas

    @property
    def lista(self) -> ListaMensagens:
        return self._lista

    def carregar_historico_local(self) -> None:
        """Exibe o que o Historico_Local ja guarda (ao abrir a sala)."""
        if self._historico is None:
            return
        try:
            guardadas = self._historico.listar()
        except Exception as e:  # noqa: BLE001
            self._saidas.aviso(AVISO_HISTORICO_LER)
            self._saidas.erro(e, "historico.py")
            return
        self._exibir(guardadas)

    def drenar(self, fila: "queue.Queue[Any]", maximo: int = 200) -> int:
        """Processa ate ``maximo`` eventos ja presentes na fila, sem bloquear.
        Devolve quantos processou. Falha em um evento nao interrompe os demais."""
        processados = 0
        while processados < maximo:
            try:
                evento = fila.get_nowait()
            except queue.Empty:
                break
            processados += 1
            try:
                self.processar(evento)
            except Exception as e:  # noqa: BLE001
                self._saidas.erro(e, "processador.py")
        return processados

    def processar(self, evento: Any) -> None:
        if isinstance(evento, EventoMensagem):
            self._tratar_mensagens([evento.mensagem])
        elif isinstance(evento, EventoHistorico):
            self._tratar_mensagens(evento.mensagens)
        elif isinstance(evento, EventoEstado):
            self._saidas.estado(apresentar_estado(evento.estado, evento.detalhe))
        elif isinstance(evento, EventoReautenticado):
            self._saidas.reautenticado(evento.perfil)
        elif isinstance(evento, EventoErro):
            self._saidas.erro(evento.erro, f"conexao.py:{evento.contexto}")

    # ----- internos ----------------------------------------------------------

    def _tratar_mensagens(self, mensagens: list) -> None:
        normalizadas = [m for m in (normalizar_mensagem(x) for x in mensagens) if m is not None]
        if self._historico is not None and normalizadas:
            try:
                self._historico.incorporar(normalizadas)
            except Exception as e:  # noqa: BLE001
                self._saidas.aviso(AVISO_HISTORICO_GRAVAR)
                self._saidas.erro(e, "historico.py")
        self._exibir(normalizadas)

    def _exibir(self, mensagens: list) -> None:
        resultado = self._lista.adicionar_lote(mensagens)
        if resultado.novas:
            self._saidas.exibir(resultado, self._lista)
