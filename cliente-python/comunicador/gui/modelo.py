"""Logica da interface do Cliente_Terceiro que nao depende de Tkinter.

Tudo aqui e testavel sem display, rede ou relogio fixo (o fuso e injetavel):

- validacao de login e cadastro (mesmas regras do Cliente_Web e do Middleware);
- mensagens de erro para o usuario a partir dos codigos do Protocolo;
- representacao textual alternativa de avatar, figurinha, cor e emoji, usada
  quando a imagem nao carrega, quando o Tk nao desenha o caractere ou no modo
  texto: ``[figurinha: fig_03]``, ``[avatar: av_03]``,
  ``(cor #1E88E5)``, ``[emoji: U+1F600]``;
- ``descrever_mensagem``: o que a sala mostra de cada mensagem (apelido,
  avatar, cor do apelido, data/hora, conteudo), ja saneado;
- ``ListaMensagens``: ordem ``(ts, mid)`` e ausencia de duplicatas na tela;
- ``apresentar_estado``: como cada estado da conexao aparece na interface;
- conversao entre indice do Tk e posicao em pontos de codigo, usada para
  inserir emoji na posicao do cursor (``envio.inserir_emoji``).

"""
from __future__ import annotations

import bisect
import re
from dataclasses import dataclass
from datetime import datetime, timezone, tzinfo
from functools import lru_cache
from typing import Any, Iterable, Mapping, Optional

from comunicador.nucleo.conexao import ErroRequisicao, EstadoConexao
from comunicador.nucleo.envio import eh_id_figurinha
from comunicador.nucleo.protocolo import CodigoErro

# ---------------------------------------------------------------------------
# Validacao de login e cadastro
# ---------------------------------------------------------------------------

REGEX_LOGIN = re.compile(r"[a-z0-9_]{3,20}")
REGEX_COR = re.compile(r"#[0-9A-Fa-f]{6}")
REGEX_AVATAR = re.compile(r"av_[0-9]{2}")

COR_PADRAO = "#1E88E5"
MAXIMO_APELIDO_EXIBIDO = 60
MAXIMO_TEXTO_ERRO = 120


def eh_cor_valida(cor: Any) -> bool:
    """``True`` se ``cor`` e ``#RRGGBB`` (hexadecimal)."""
    return isinstance(cor, str) and REGEX_COR.fullmatch(cor) is not None


def eh_id_avatar(id_: Any) -> bool:
    """``True`` se ``id_`` tem o formato ``av_NN``."""
    return isinstance(id_, str) and REGEX_AVATAR.fullmatch(id_) is not None


def eh_login_valido(login: Any) -> bool:
    return isinstance(login, str) and REGEX_LOGIN.fullmatch(login) is not None


def validar_credenciais(login: str, senha: str) -> Optional[str]:
    """Mensagem de problema para o formulario de login, ou ``None`` se ok.

    Um login fora do formato nunca existe no servidor, entao a mesma mensagem
    de credenciais invalidas e usada sem consultar a rede.
    """
    if not eh_login_valido(login) or not isinstance(senha, str) or senha == "":
        return mensagem_de_codigo(CodigoErro.CREDENCIAIS_INVALIDAS)
    return None


def validar_cadastro(campos: Mapping[str, Any]) -> Optional[str]:
    """Mensagem do primeiro problema nos campos do cadastro, ou ``None`` se ok.

    Mesma ordem e regras do Middleware: login ``^[a-z0-9_]{3,20}$``, apelido de
    1 a 30 pontos de codigo, senha de 6 a 72 caracteres, avatar ``av_NN`` e
    cor ``#RRGGBB``.
    """
    if not eh_login_valido(campos.get("login")):
        return "Login: 3 a 20 caracteres entre letras minúsculas, números e _."
    apelido = campos.get("apelido")
    if not isinstance(apelido, str) or not 1 <= len(apelido) <= 30:
        return "Apelido: de 1 a 30 caracteres."
    senha = campos.get("senha")
    if not isinstance(senha, str) or not 6 <= len(senha) <= 72:
        return "Senha: de 6 a 72 caracteres."
    if not eh_id_avatar(campos.get("avatar")):
        return "Escolha um avatar."
    if not eh_cor_valida(campos.get("cor")):
        return "Escolha uma cor válida (formato #RRGGBB)."
    return None


# ---------------------------------------------------------------------------
# Mensagens de erro para o usuario
# ---------------------------------------------------------------------------

_MENSAGENS_ERRO = {
    CodigoErro.CREDENCIAIS_INVALIDAS: "Login ou senha inválidos.",
    CodigoErro.LOGIN_DUPLICADO: "Esse login já está cadastrado.",
    CodigoErro.CAMPO_INVALIDO: "Algum campo tem valor inválido.",
    CodigoErro.JA_AUTENTICADO: "Esta conexão já está autenticada.",
    CodigoErro.DECIFRAGEM_FALHOU: "Não foi possível decifrar os dados. Confira a Chave_Transito.",
    CodigoErro.LIMITE_EXCEDIDO: "Muitas tentativas. Aguarde um instante e tente de novo.",
    CodigoErro.SERVICO_INDISPONIVEL: "Serviço indisponível no momento. Tente de novo.",
    CodigoErro.MENSAGEM_MUITO_GRANDE: "A mensagem é grande demais.",
    CodigoErro.CONTEUDO_INVALIDO: "Conteúdo inválido.",
    "SEM_CONEXAO": "Sem conexão com o servidor. Verifique se o Middleware está em execução.",
    "CONEXAO_PERDIDA": "A conexão foi perdida. Tente de novo.",
    "TEMPO_ESGOTADO": "O servidor não respondeu a tempo.",
    "SEM_CHAVE": "Chave_Transito ausente ou inválida.",
}

MENSAGEM_ERRO_GENERICA = "Não foi possível concluir a operação."


def mensagem_de_codigo(codigo: Any, campo: Optional[str] = None) -> str:
    """Texto para o usuario de um codigo de erro do Protocolo ou da conexao."""
    if codigo == CodigoErro.CAMPO_AUSENTE:
        return f"Campo obrigatório ausente: {limitar(campo, 30)}." if campo else "Campo obrigatório ausente."
    if isinstance(codigo, str):
        return _MENSAGENS_ERRO.get(codigo, MENSAGEM_ERRO_GENERICA)
    return MENSAGEM_ERRO_GENERICA


def mensagem_de_erro(erro: BaseException) -> str:
    """Texto para o usuario de uma excecao de requisicao. Nunca inclui dados
    recebidos alem do nome do campo (limitado)."""
    if isinstance(erro, ErroRequisicao):
        return mensagem_de_codigo(erro.codigo, erro.campo)
    return MENSAGEM_ERRO_GENERICA


# ---------------------------------------------------------------------------
# Texto seguro e representacao textual alternativa
# ---------------------------------------------------------------------------

_RE_CONTROLE = re.compile(r"[\x00-\x08\x0b-\x1f\x7f-\x9f]")


def limitar(texto: Any, maximo: int) -> str:
    """Texto limitado a ``maximo`` pontos de codigo, sem caracteres de controle."""
    if not isinstance(texto, str):
        return ""
    limpo = _RE_CONTROLE.sub("", texto)
    return limpo if len(limpo) <= maximo else limpo[:maximo]


def limpar_controle(texto: str) -> str:
    """Remove caracteres de controle, preservando quebra de linha e tabulacao."""
    return _RE_CONTROLE.sub("", texto)


def texto_avatar(id_: Any) -> str:
    """``[avatar: av_03]`` (ou ``[avatar: desconhecido]`` se o id for invalido)."""
    return f"[avatar: {id_ if eh_id_avatar(id_) else 'desconhecido'}]"


def texto_figurinha(id_: Any) -> str:
    """``[figurinha: fig_03]`` (ou ``[figurinha: desconhecida]``)."""
    return f"[figurinha: {id_ if eh_id_figurinha(id_) else 'desconhecida'}]"


def texto_cor(cor: Any) -> str:
    """``(cor #1E88E5)`` (ou ``(cor inválida)``)."""
    return f"(cor {cor.upper() if eh_cor_valida(cor) else 'inválida'})"


def codigo_emoji(emoji: str) -> str:
    """Pontos de codigo de ``emoji``: ``U+1F600`` ou ``U+2764+U+FE0F``."""
    return "+".join(f"U+{ord(c):04X}" for c in emoji)


def texto_emoji(emoji: str) -> str:
    """``[emoji: U+1F600]``."""
    return f"[emoji: {codigo_emoji(emoji)}]"


@lru_cache(maxsize=8)
def _regex_emojis(emojis: tuple[str, ...]) -> re.Pattern:
    conhecidos = "|".join(re.escape(e) for e in sorted({e for e in emojis if e}, key=lambda e: -len(e)))
    generico = r"[\U0001F000-\U0001FAFF\u2600-\u27BF\u2B00-\u2BFF]\uFE0F?"
    return re.compile(f"(?:{conhecidos})\\uFE0F?|{generico}" if conhecidos else generico)


def substituir_emojis(texto: str, emojis: Iterable[str] = ()) -> str:
    """Troca cada emoji de ``texto`` por ``[emoji: U+XXXX]``.

    Reconhece os ``emojis`` informados (a lista do Seletor_de_Emojis e os da
    Tabela_de_Conversao) e os blocos Unicode de emoji e simbolos comuns.
    """
    return _regex_emojis(tuple(emojis)).sub(lambda m: texto_emoji(m.group(0)), texto)


# ---------------------------------------------------------------------------
# Data e hora
# ---------------------------------------------------------------------------

FORMATO_DATA_HORA = "%d/%m/%Y %H:%M:%S"


def formatar_data_hora(ts: Any, fuso: Optional[tzinfo] = None) -> str:
    """Data/hora local de um ``ts`` ISO-8601 (por exemplo ``2025-03-14T18:22:05.431Z``).

    ``fuso`` troca o fuso local (testes). Um ``ts`` ilegivel vira o proprio
    texto, limitado.
    """
    if not isinstance(ts, str):
        return ""
    try:
        momento = datetime.fromisoformat(ts)
    except ValueError:
        return limitar(ts, 30)
    if momento.tzinfo is None:
        momento = momento.replace(tzinfo=timezone.utc)
    return momento.astimezone(fuso).strftime(FORMATO_DATA_HORA)


# ---------------------------------------------------------------------------
# Mensagens
# ---------------------------------------------------------------------------

TEXTO_ILEGIVEL = "[mensagem ilegível]"


def normalizar_mensagem(m: Any) -> Optional[dict]:
    """Converte ``MensagemRecebida`` ou ``dict`` (do Historico_Local) em um
    ``dict`` com ``mid``, ``remetente``, ``ts``, ``tipo``, ``conteudo`` e
    ``ilegivel``. Devolve ``None`` se nao ha ``mid`` (nao da para evitar
    duplicatas sem ele)."""
    if hasattr(m, "_asdict"):
        m = m._asdict()
    if not isinstance(m, Mapping):
        return None
    mid = m.get("mid")
    if not isinstance(mid, str) or mid == "":
        return None
    r = m.get("remetente")
    r = r if isinstance(r, Mapping) else {}
    ts = m.get("ts")
    conteudo = m.get("conteudo")
    return {
        "mid": mid,
        "remetente": {
            "login": r.get("login"),
            "apelido": r.get("apelido"),
            "avatar": r.get("avatar"),
            "cor": r.get("cor"),
        },
        "ts": ts if isinstance(ts, str) else "",
        "tipo": m.get("tipo"),
        "conteudo": conteudo if isinstance(conteudo, str) else None,
        "ilegivel": bool(m.get("ilegivel")) or not isinstance(conteudo, str),
    }


@dataclass(frozen=True)
class LinhaMensagem:
    """O que a sala exibe de uma mensagem.

    ``cor`` e ``avatar_id`` so vem preenchidos quando validos; ``texto_cor`` e
    ``texto_avatar`` sao as representacoes alternativas. Para figurinha,
    ``texto`` traz a representacao textual e ``figurinha_id`` o id valido.
    ``texto_alternativo`` e ``texto`` com os emojis trocados por ``[emoji: ...]``.
    """

    mid: str
    apelido: str
    cor: Optional[str]
    texto_cor: str
    avatar_id: Optional[str]
    texto_avatar: str
    data_hora: str
    tipo: str
    texto: str
    texto_alternativo: str
    figurinha_id: Optional[str]
    ilegivel: bool


def descrever_mensagem(
    m: Mapping[str, Any],
    *,
    emojis: Iterable[str] = (),
    fuso: Optional[tzinfo] = None,
) -> LinhaMensagem:
    """Monta a ``LinhaMensagem`` de uma mensagem ja normalizada."""
    r = m.get("remetente") or {}
    apelido = limitar(r.get("apelido"), MAXIMO_APELIDO_EXIBIDO) or limitar(r.get("login"), 20) or "?"
    cor = r.get("cor") if eh_cor_valida(r.get("cor")) else None
    avatar = r.get("avatar") if eh_id_avatar(r.get("avatar")) else None

    tipo = m.get("tipo") if m.get("tipo") in ("texto", "figurinha") else "texto"
    conteudo = m.get("conteudo")
    figurinha: Optional[str] = None
    ilegivel = bool(m.get("ilegivel"))
    if ilegivel or not isinstance(conteudo, str):
        ilegivel = True
        texto = TEXTO_ILEGIVEL
    elif tipo == "figurinha":
        figurinha = conteudo if eh_id_figurinha(conteudo) else None
        texto = texto_figurinha(conteudo)
    else:
        texto = limpar_controle(conteudo)
    return LinhaMensagem(
        mid=m["mid"],
        apelido=apelido,
        cor=cor.upper() if cor else None,
        texto_cor=texto_cor(r.get("cor")),
        avatar_id=avatar,
        texto_avatar=texto_avatar(r.get("avatar")),
        data_hora=formatar_data_hora(m.get("ts"), fuso),
        tipo=tipo,
        texto=texto,
        texto_alternativo=substituir_emojis(texto, emojis) if tipo == "texto" and not ilegivel else texto,
        figurinha_id=figurinha,
        ilegivel=ilegivel,
    )


@dataclass(frozen=True)
class ResultadoExibicao:
    """Resultado de adicionar mensagens a ``ListaMensagens``.

    ``novas`` sao as mensagens que ainda nao estavam na lista. ``redesenhar``
    e verdadeiro quando alguma caiu antes do fim (ordem ``(ts, mid)``): a
    interface deve redesenhar tudo em vez de apenas anexar ``novas``.
    """

    novas: tuple[dict, ...]
    redesenhar: bool


class ListaMensagens:
    """Mensagens exibidas: sem duplicatas por ``mid`` e ordenadas por ``(ts, mid)``."""

    def __init__(self) -> None:
        self._chaves: list[tuple[str, str]] = []
        self._itens: list[dict] = []
        self._mids: set[str] = set()

    def __len__(self) -> int:
        return len(self._itens)

    def __contains__(self, mid: object) -> bool:
        return mid in self._mids

    @property
    def itens(self) -> list[dict]:
        """Copia das mensagens, em ordem de exibicao."""
        return list(self._itens)

    def limpar(self) -> None:
        self._chaves.clear()
        self._itens.clear()
        self._mids.clear()

    def adicionar_lote(self, mensagens: Iterable[Any]) -> ResultadoExibicao:
        novas: list[dict] = []
        redesenhar = False
        for bruta in mensagens:
            m = normalizar_mensagem(bruta)
            if m is None or m["mid"] in self._mids:
                continue
            chave = (m["ts"], m["mid"])
            posicao = bisect.bisect_right(self._chaves, chave)
            if posicao < len(self._chaves):
                redesenhar = True
            self._chaves.insert(posicao, chave)
            self._itens.insert(posicao, m)
            self._mids.add(m["mid"])
            novas.append(m)
        return ResultadoExibicao(tuple(novas), redesenhar)


# ---------------------------------------------------------------------------
# Estado da conexao na interface
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class ApresentacaoEstado:
    """Como a interface reflete um ``EventoEstado``."""

    texto: str = ""
    desconectado: bool = False
    permitir_tentar_novamente: bool = False
    enviar_habilitado: bool = False
    encerrar_sessao: bool = False
    aviso: str = ""


def apresentar_estado(estado: str, detalhe: Optional[Mapping[str, Any]] = None) -> ApresentacaoEstado:
    """Traduz o estado da conexao para a interface."""
    detalhe = detalhe or {}
    if estado == EstadoConexao.AUTENTICADO:
        return ApresentacaoEstado(enviar_habilitado=True)
    if estado == EstadoConexao.CONECTANDO:
        return ApresentacaoEstado(texto="Conectando…")
    if estado == EstadoConexao.CONECTADO:
        return ApresentacaoEstado(texto="Conectado. Autenticando…")
    if estado == EstadoConexao.RECONECTANDO:
        tentativa = detalhe.get("tentativa")
        maximo = detalhe.get("maximo")
        return ApresentacaoEstado(
            texto=f"Reconectando (tentativa {tentativa if isinstance(tentativa, int) else '?'}"
            f" de {maximo if isinstance(maximo, int) else '?'})"
        )
    if estado == EstadoConexao.DESCONECTADO:
        motivo = detalhe.get("motivo")
        if motivo == "credenciais_invalidas":
            return ApresentacaoEstado(
                texto="Desconectado",
                desconectado=True,
                encerrar_sessao=True,
                aviso="Sua sessão terminou: login ou senha inválidos.",
            )
        if motivo == "encerrado":
            return ApresentacaoEstado()
        return ApresentacaoEstado(texto="Desconectado", desconectado=True, permitir_tentar_novamente=True)
    return ApresentacaoEstado()


# ---------------------------------------------------------------------------
# Indices do Tk e posicao em pontos de codigo
# ---------------------------------------------------------------------------

def _unidades(c: str, astral_duplo: bool) -> int:
    return 2 if astral_duplo and ord(c) > 0xFFFF else 1


def indice_tk_para_posicao(texto: str, indice: int, astral_duplo: bool = False) -> int:
    """Converte o indice de caractere do Tk (``Entry.index(INSERT)``) em
    posicao em pontos de codigo, limitada a ``[0, len(texto)]``.

    No Tk 8.6 compilado com ``TCL_UTF_MAX=3`` um caractere fora do plano basico
    ocupa 2 indices (``astral_duplo=True``); no Tk 9 ocupa 1.
    """
    if indice <= 0:
        return 0
    usados = 0
    for posicao, c in enumerate(texto):
        if usados >= indice:
            return posicao
        usados += _unidades(c, astral_duplo)
    return len(texto)


def posicao_para_indice_tk(texto: str, posicao: int, astral_duplo: bool = False) -> int:
    """Inverso de ``indice_tk_para_posicao``."""
    posicao = max(0, min(posicao, len(texto)))
    return sum(_unidades(c, astral_duplo) for c in texto[:posicao])


def reducao_para_caber(largura: int, altura: int, maximo: int) -> int:
    """Fator inteiro (``subsample``) para a maior dimensao ficar em ate ``maximo``."""
    maior = max(largura, altura, 1)
    return max(1, -(-maior // max(1, maximo)))
