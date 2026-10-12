"""Controlador de envio e insercao de emoji do Cliente_Terceiro (sem Tkinter).

Mesmo comportamento do Cliente_Web (cliente-web/js/envio.js) e do
Cliente_Desktop.

- O botao Enviar e a tecla Enter chamam a MESMA funcao (``acionar_enviar``).
- Texto vazio ou so de espacos em branco Unicode: nao envia e nao altera
  nenhum estado (nem o campo de texto).
- Texto valido: converte emoticons (Tabela_de_Conversao), entrega a
  ``enviar("texto", conteudo)`` (``Conexao.enviar_mensagem``, que cifra o
  ``conteudo`` com a Chave_Transito) e limpa o campo.
- Figurinha: envia ``enviar("figurinha", "fig_NN")`` sem tocar no campo.
- ``inserir_emoji`` e funcao pura: antes + emoji + depois.

Nada aqui depende de Tkinter, rede ou relogio: o campo e a funcao de envio sao
injetados, o que permite testar o controlador sem interface.

``enviar`` pode devolver um ``concurrent.futures.Future`` (como
``Conexao.enviar_mensagem``) ou o resultado direto. Com ``Future``, a falha
chega depois, na thread de rede; por isso o texto so e restaurado pelo
callback do futuro, via ``despachar`` (na interface, ``lambda f: raiz.after(0, f)``).
"""

import re
from concurrent.futures import Future
from dataclasses import dataclass
from typing import Any, Callable, Optional, Protocol, Tuple

from .emoticons import TabelaConversao, converter, eh_espaco

# Tamanho maximo do texto, em pontos de codigo (limite do Middleware).
MAXIMO_PONTOS_DE_CODIGO = 2000

# Formato aceito para o identificador de uma figurinha.
PADRAO_FIGURINHA = re.compile(r"fig_[0-9]{2}")


class MotivoNaoEnviado:
    """Motivos pelos quais um acionamento nao resultou em envio."""

    EM_BRANCO = "em_branco"
    MUITO_LONGO = "muito_longo"
    FIGURINHA_INVALIDA = "figurinha_invalida"
    FALHA = "falha"


# ---------------------------------------------------------------------------
# Funcoes puras
# ---------------------------------------------------------------------------

def eh_em_branco(texto: str) -> bool:
    """``True`` se ``texto`` e vazio ou composto somente de espacos em branco
    Unicode (propriedade White_Space, a mesma usada na conversao de emoticons)."""
    if not isinstance(texto, str):
        raise TypeError("O texto deve ser uma string")
    return all(eh_espaco(c) for c in texto)


def contar_pontos_de_codigo(texto: str) -> int:
    """Quantidade de pontos de codigo Unicode de ``texto``."""
    return len(texto)


def inserir_emoji(texto: str, posicao: int, emoji: str) -> str:
    """Insere ``emoji`` em ``texto`` na ``posicao`` do cursor.

    ``posicao`` e um indice de ponto de codigo em [0, len(texto)]. Resultado:
    ``texto[:posicao] + emoji + texto[posicao:]``.
    """
    if not isinstance(texto, str) or not isinstance(emoji, str):
        raise TypeError("Texto e emoji devem ser strings")
    if isinstance(posicao, bool) or not isinstance(posicao, int):
        raise TypeError("A posicao deve ser um inteiro")
    if posicao < 0 or posicao > len(texto):
        raise ValueError("Posicao do cursor fora do texto")
    return texto[:posicao] + emoji + texto[posicao:]


def inserir_emoji_com_cursor(texto: str, posicao: int, emoji: str) -> Tuple[str, int]:
    """Como ``inserir_emoji``, mas tambem devolve a nova posicao do cursor
    (logo apos o emoji), para a interface reposicionar o cursor."""
    return inserir_emoji(texto, posicao, emoji), posicao + len(emoji)


def eh_id_figurinha(id_: Any) -> bool:
    """``True`` se ``id_`` tem o formato ``fig_NN``."""
    return isinstance(id_, str) and PADRAO_FIGURINHA.fullmatch(id_) is not None


# ---------------------------------------------------------------------------
# Controlador
# ---------------------------------------------------------------------------

class CampoDeTexto(Protocol):
    def ler(self) -> str:
        """Devolve o texto atual do campo."""

    def escrever(self, texto: str) -> None:
        """Substitui o texto do campo."""


@dataclass(frozen=True)
class ResultadoEnvio:
    """Resultado de um acionamento.

    ``enviado`` e verdadeiro quando o envio foi aceito. Se ``enviar`` devolveu
    um ``Future``, ele vem em ``futuro`` e ``resposta`` fica ``None``; o
    desfecho final chega por ele. Quando ``enviado`` e falso, ``motivo`` e um
    valor de ``MotivoNaoEnviado`` e ``erro`` traz a excecao de ``FALHA``.
    """

    enviado: bool
    motivo: Optional[str] = None
    resposta: Any = None
    erro: Optional[BaseException] = None
    futuro: Optional[Future] = None


_NAO_ENVIADO_EM_BRANCO = ResultadoEnvio(False, MotivoNaoEnviado.EM_BRANCO)
_NAO_ENVIADO_LONGO = ResultadoEnvio(False, MotivoNaoEnviado.MUITO_LONGO)
_NAO_ENVIADO_FIGURINHA = ResultadoEnvio(False, MotivoNaoEnviado.FIGURINHA_INVALIDA)


def _executar_agora(f: Callable[[], None]) -> None:
    f()


class ControladorEnvio:
    """Controlador de envio.

    :param tabela: Tabela_de_Conversao (``emoticons.TabelaConversao``)
    :param enviar: ``(tipo, conteudo) -> Future | resultado``; normalmente
        ``conexao.enviar_mensagem``, que cifra ``conteudo``
    :param campo: objeto com ``ler()`` e ``escrever(texto)``
    :param despachar: executa um callable na thread da interface; usado para
        restaurar o campo quando o ``Future`` falha na thread de rede
    """

    def __init__(
        self,
        tabela: TabelaConversao,
        enviar: Callable[[str, str], Any],
        campo: CampoDeTexto,
        despachar: Callable[[Callable[[], None]], None] = _executar_agora,
    ) -> None:
        if tabela is None or not callable(enviar) or campo is None or not callable(despachar):
            raise TypeError("tabela, enviar e campo sao obrigatorios")
        self._tabela = tabela
        self._enviar = enviar
        self._campo = campo
        self._despachar = despachar

    def acionar_enviar(self) -> ResultadoEnvio:
        """Acao unica do botao Enviar e da tecla Enter.

        Texto em branco: devolve ``enviado=False, motivo="em_branco"`` sem
        chamar ``enviar`` e sem escrever no campo.
        """
        original = self._campo.ler()
        if eh_em_branco(original):
            return _NAO_ENVIADO_EM_BRANCO

        conteudo = converter(original, self._tabela)
        if contar_pontos_de_codigo(conteudo) > MAXIMO_PONTOS_DE_CODIGO:
            # Mantem o campo intacto para o usuario encurtar o texto.
            return _NAO_ENVIADO_LONGO

        # Limpa antes de aguardar a resposta: um segundo Enter nao reenvia o mesmo texto.
        self._campo.escrever("")
        try:
            resposta = self._enviar("texto", conteudo)
        except Exception as erro:  # noqa: BLE001 - qualquer falha devolve o texto
            self._restaurar(original)
            return ResultadoEnvio(False, MotivoNaoEnviado.FALHA, erro=erro)

        if isinstance(resposta, Future):
            resposta.add_done_callback(lambda f: self._ao_concluir(f, original))
            return ResultadoEnvio(True, futuro=resposta)
        return ResultadoEnvio(True, resposta=resposta)

    def ao_teclar(self, tecla: str, compondo: bool = False) -> Optional[ResultadoEnvio]:
        """Manipulador de teclado do campo de texto: Enter (fora de composicao
        IME) aciona o mesmo envio do botao. Devolve ``None`` se a tecla nao e tratada."""
        if tecla not in ("Return", "Enter", "KP_Enter") or compondo:
            return None
        return self.acionar_enviar()

    def enviar_figurinha(self, id_: str) -> ResultadoEnvio:
        """Envia uma figurinha pelo identificador (``fig_NN``), sem alterar o
        campo de texto."""
        if not eh_id_figurinha(id_):
            return _NAO_ENVIADO_FIGURINHA
        try:
            resposta = self._enviar("figurinha", id_)
        except Exception as erro:  # noqa: BLE001
            return ResultadoEnvio(False, MotivoNaoEnviado.FALHA, erro=erro)
        if isinstance(resposta, Future):
            return ResultadoEnvio(True, futuro=resposta)
        return ResultadoEnvio(True, resposta=resposta)

    def inserir_emoji_no_campo(self, emoji: str, posicao: int) -> int:
        """Insere ``emoji`` no campo na posicao do cursor (Seletor_de_Emojis)
        e devolve a nova posicao do cursor. Independe da conversao
        de emoticons."""
        texto, nova = inserir_emoji_com_cursor(self._campo.ler(), posicao, emoji)
        self._campo.escrever(texto)
        return nova

    # ----- internos ----------------------------------------------------------

    def _ao_concluir(self, futuro: Future, original: str) -> None:
        if futuro.cancelled() or futuro.exception() is not None:
            self._despachar(lambda: self._restaurar(original))

    def _restaurar(self, original: str) -> None:
        """Devolve o texto digitado, a menos que o usuario ja tenha digitado outra coisa."""
        if self._campo.ler() == "":
            self._campo.escrever(original)
