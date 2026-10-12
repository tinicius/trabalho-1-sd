"""Protocolo do Comunicador Distribuido de Grupo (lado Python).

Monta e interpreta os quadros JSON trocados com o Middleware e fornece o
``LeitorDeLinhas``, que separa o fluxo TCP em linhas (uma mensagem JSON por
linha, terminada em ``\\n``). O comportamento acompanha o ``LeitorDeLinhas``
Java do modulo ``comum``.

Este modulo nao conhece chaves nem a Cifra_Manual: os campos cifrados
(``dados`` e ``conteudo``) chegam aqui como strings Base64 ja prontas.
"""

from __future__ import annotations

import json
from typing import Any, Iterable, NamedTuple

VERSAO = 1
"""Versao atual do Protocolo."""

LIMITE_LINHA = 1_048_576
"""Tamanho maximo de uma linha, em bytes (1 MiB), sem contar o ``\\n``."""

DELIMITADOR = b"\n"

# Operacoes de requisicao.
OP_REGISTER = "register"
OP_LOGIN = "login"
OP_SEND_MESSAGE = "send_message"
OP_HISTORY = "history"
OP_ERROR_REPORT = "error_report"
OP_PING = "ping"
OP_LOGOUT = "logout"

# Eventos enviados pelo Middleware sem requisicao e quadro de erro sem correlacao.
OP_MESSAGE = "message"
OP_ERRO = "erro"

OPERACOES_REQUISICAO = frozenset(
    {OP_REGISTER, OP_LOGIN, OP_SEND_MESSAGE, OP_HISTORY, OP_ERROR_REPORT, OP_PING, OP_LOGOUT}
)
OPERACOES_CONHECIDAS = OPERACOES_REQUISICAO | {OP_MESSAGE, OP_ERRO}

TIPO_TEXTO = "texto"
TIPO_FIGURINHA = "figurinha"

TAMANHO_MAXIMO_ID = 64


class CodigoErro:
    """Codigos de erro do Protocolo."""

    FORMATO_INVALIDO = "FORMATO_INVALIDO"
    MENSAGEM_MUITO_GRANDE = "MENSAGEM_MUITO_GRANDE"
    VERSAO_NAO_SUPORTADA = "VERSAO_NAO_SUPORTADA"
    OPERACAO_DESCONHECIDA = "OPERACAO_DESCONHECIDA"
    NAO_AUTENTICADO = "NAO_AUTENTICADO"
    JA_AUTENTICADO = "JA_AUTENTICADO"
    CREDENCIAIS_INVALIDAS = "CREDENCIAIS_INVALIDAS"
    LOGIN_DUPLICADO = "LOGIN_DUPLICADO"
    CAMPO_AUSENTE = "CAMPO_AUSENTE"
    CAMPO_INVALIDO = "CAMPO_INVALIDO"
    DECIFRAGEM_FALHOU = "DECIFRAGEM_FALHOU"
    CONTEUDO_INVALIDO = "CONTEUDO_INVALIDO"
    LIMITE_EXCEDIDO = "LIMITE_EXCEDIDO"
    SERVICO_INDISPONIVEL = "SERVICO_INDISPONIVEL"
    ERRO_INTERNO = "ERRO_INTERNO"


MENSAGENS_DE_ERRO: dict[str, str] = {
    CodigoErro.FORMATO_INVALIDO: "Formato de mensagem invalido",
    CodigoErro.MENSAGEM_MUITO_GRANDE: "Mensagem acima do tamanho maximo",
    CodigoErro.VERSAO_NAO_SUPORTADA: "Versao do protocolo nao suportada",
    CodigoErro.OPERACAO_DESCONHECIDA: "Operacao desconhecida",
    CodigoErro.NAO_AUTENTICADO: "Autenticacao necessaria",
    CodigoErro.JA_AUTENTICADO: "Sessao ja autenticada",
    CodigoErro.CREDENCIAIS_INVALIDAS: "Login ou senha invalidos",
    CodigoErro.LOGIN_DUPLICADO: "Login ja cadastrado",
    CodigoErro.CAMPO_AUSENTE: "Campo obrigatorio ausente",
    CodigoErro.CAMPO_INVALIDO: "Campo com valor invalido",
    CodigoErro.DECIFRAGEM_FALHOU: "Nao foi possivel decifrar os dados",
    CodigoErro.CONTEUDO_INVALIDO: "Conteudo invalido",
    CodigoErro.LIMITE_EXCEDIDO: "Limite de requisicoes excedido",
    CodigoErro.SERVICO_INDISPONIVEL: "Servico indisponivel",
    CodigoErro.ERRO_INTERNO: "Erro interno",
}
"""Texto fixo de ``erro.mensagem`` por codigo (nunca ecoa dados recebidos)."""


class ErroDeProtocolo(Exception):
    """Quadro recebido fora do Protocolo.

    ``codigo`` e um dos valores de ``CodigoErro`` que o Middleware devolveria
    para o mesmo quadro.
    """

    def __init__(self, codigo: str) -> None:
        super().__init__(codigo)
        self.codigo = codigo


# ---------------------------------------------------------------------------
# Montagem de quadros
# ---------------------------------------------------------------------------


def serializar(quadro: dict[str, Any]) -> str:
    """Serializa um objeto em JSON compacto, sem quebras de linha reais.

    Quebras de linha dentro de strings saem escapadas (``\\n``), logo o texto
    resultante nunca contem ``\\n`` literal e o Delimitador e inequivoco.
    """
    return json.dumps(quadro, ensure_ascii=False, separators=(",", ":"), allow_nan=False)


def delimitar(quadro: dict[str, Any]) -> bytes:
    """Serializa o quadro em UTF-8 e acrescenta o Delimitador ``\\n`` (TCP)."""
    return serializar(quadro).encode("utf-8") + DELIMITADOR


def requisicao(op: str, id: str | None = None, **campos: Any) -> dict[str, Any]:
    """Monta o envelope de uma requisicao: ``v``, ``op``, ``id`` e os campos.

    ``id`` e omitido quando e ``None``. Campos com valor ``None`` tambem sao
    omitidos.
    """
    if id is not None and len(id) > TAMANHO_MAXIMO_ID:
        raise ValueError("id acima de 64 caracteres")
    quadro: dict[str, Any] = {"v": VERSAO, "op": op}
    if id is not None:
        quadro["id"] = id
    for nome, valor in campos.items():
        if valor is not None:
            quadro[nome] = valor
    return quadro


def montar_register(id: str | None, dados: str) -> dict[str, Any]:
    """``register``: ``dados`` e a cifra (Transito) do JSON do cadastro."""
    return requisicao(OP_REGISTER, id, dados=dados)


def montar_login(
    id: str | None,
    dados: str,
    dispositivo: str,
    tipo_cliente: str = "python",
    versao_cliente: str = "1",
) -> dict[str, Any]:
    """``login``: ``dados`` cifrado, UUID do ``dispositivo`` e ``cliente``."""
    return requisicao(
        OP_LOGIN,
        id,
        dados=dados,
        dispositivo=dispositivo,
        cliente={"tipo": tipo_cliente, "versao": versao_cliente},
    )


def montar_send_message(id: str | None, tipo: str, conteudo: str) -> dict[str, Any]:
    """``send_message``: ``tipo`` (texto ou figurinha) e ``conteudo`` cifrado."""
    return requisicao(OP_SEND_MESSAGE, id, tipo=tipo, conteudo=conteudo)


def montar_history(id: str | None, desde: str | None = None) -> dict[str, Any]:
    """``history``: ``desde`` opcional (ISO-8601 UTC)."""
    return requisicao(OP_HISTORY, id, desde=desde)


def montar_error_report(id: str | None, eventos: list[dict[str, Any]]) -> dict[str, Any]:
    """``error_report``: lista de eventos de erro (o Middleware aceita ate 20)."""
    return requisicao(OP_ERROR_REPORT, id, eventos=eventos)


def montar_ping(id: str | None = None) -> dict[str, Any]:
    return requisicao(OP_PING, id)


def montar_logout(id: str | None = None) -> dict[str, Any]:
    return requisicao(OP_LOGOUT, id)


def montar_resposta_ok(op: str, id: str | None, **campos: Any) -> dict[str, Any]:
    """Resposta de sucesso: repete ``v``, ``op``, ``id`` e acrescenta ``ok:true``."""
    quadro: dict[str, Any] = {"v": VERSAO, "op": op, "id": id, "ok": True}
    quadro.update(campos)
    return quadro


def montar_resposta_erro(
    op: str, id: str | None, codigo: str, campo: str | None = None
) -> dict[str, Any]:
    """Resposta de erro com ``erro.codigo``, ``erro.mensagem`` fixa e ``erro.campo``.

    ``op`` e ``"erro"`` e ``id`` e ``None`` quando o envelope nao pode ser
    interpretado.
    """
    erro: dict[str, Any] = {
        "codigo": codigo,
        "mensagem": MENSAGENS_DE_ERRO.get(codigo, MENSAGENS_DE_ERRO[CodigoErro.ERRO_INTERNO]),
    }
    if campo is not None:
        erro["campo"] = campo
    return {"v": VERSAO, "op": op, "id": id, "ok": False, "erro": erro}


def montar_evento_message(mensagem: dict[str, Any]) -> dict[str, Any]:
    """Evento ``message`` (sem ``id`` nem ``ok``)."""
    return {"v": VERSAO, "op": OP_MESSAGE, "mensagem": mensagem}


def montar_evento_history(
    mensagens: list[dict[str, Any]], lote: int, ultimo: bool, id: str | None = None
) -> dict[str, Any]:
    """Quadro ``history`` (``id`` nulo no envio automatico apos o login)."""
    return {
        "v": VERSAO,
        "op": OP_HISTORY,
        "id": id,
        "ok": True,
        "lote": lote,
        "ultimo": ultimo,
        "mensagens": mensagens,
    }


# ---------------------------------------------------------------------------
# Interpretacao de quadros
# ---------------------------------------------------------------------------


def _rejeitar_constante(_nome: str) -> Any:
    # NaN e Infinity nao existem em JSON; json.loads os aceita por padrao.
    raise ValueError("constante numerica fora do JSON")


def interpretar(
    linha: str | bytes | bytearray,
    *,
    aceitar_operacao_desconhecida: bool = True,
) -> dict[str, Any]:
    """Interpreta uma linha (ou frame de texto) como quadro do Protocolo.

    Um ``\\n`` ou ``\\r\\n`` final e tolerado e ignorado. Campos desconhecidos
    sao preservados no dicionario e ignorados por quem os consome.

    Levanta ``ErroDeProtocolo`` com:

    - ``FORMATO_INVALIDO``: bytes que nao sao UTF-8, JSON invalido, valor que
      nao e objeto, ``v`` ou ``op`` ausentes ou com tipo errado;
    - ``VERSAO_NAO_SUPORTADA``: ``v`` inteiro diferente de 1;
    - ``OPERACAO_DESCONHECIDA``: ``op`` fora de ``OPERACOES_CONHECIDAS``, somente
      quando ``aceitar_operacao_desconhecida`` e falso.
    """
    if isinstance(linha, (bytes, bytearray, memoryview)):
        try:
            texto = bytes(linha).decode("utf-8")
        except UnicodeDecodeError:
            raise ErroDeProtocolo(CodigoErro.FORMATO_INVALIDO) from None
    elif isinstance(linha, str):
        texto = linha
    else:
        raise ErroDeProtocolo(CodigoErro.FORMATO_INVALIDO)

    if texto.endswith("\n"):
        texto = texto[:-1]
    if texto.endswith("\r"):
        texto = texto[:-1]

    try:
        quadro = json.loads(texto, parse_constant=_rejeitar_constante)
    except (ValueError, RecursionError):
        raise ErroDeProtocolo(CodigoErro.FORMATO_INVALIDO) from None

    if not isinstance(quadro, dict):
        raise ErroDeProtocolo(CodigoErro.FORMATO_INVALIDO)

    versao = quadro.get("v")
    operacao = quadro.get("op")
    # bool e subclasse de int em Python; "v": true nao e uma versao valida.
    if isinstance(versao, bool) or not isinstance(versao, int):
        raise ErroDeProtocolo(CodigoErro.FORMATO_INVALIDO)
    if not isinstance(operacao, str):
        raise ErroDeProtocolo(CodigoErro.FORMATO_INVALIDO)
    if "id" in quadro and quadro["id"] is not None and not isinstance(quadro["id"], str):
        raise ErroDeProtocolo(CodigoErro.FORMATO_INVALIDO)
    if versao != VERSAO:
        raise ErroDeProtocolo(CodigoErro.VERSAO_NAO_SUPORTADA)
    if not aceitar_operacao_desconhecida and operacao not in OPERACOES_CONHECIDAS:
        raise ErroDeProtocolo(CodigoErro.OPERACAO_DESCONHECIDA)

    return quadro


def eh_resposta_ok(quadro: dict[str, Any]) -> bool:
    """Verdadeiro se o quadro e uma resposta com ``ok`` igual a ``true``."""
    return quadro.get("ok") is True


def codigo_de_erro(quadro: dict[str, Any]) -> str | None:
    """Devolve ``erro.codigo`` de uma resposta ``ok:false``, ou ``None``."""
    erro = quadro.get("erro")
    if quadro.get("ok") is False and isinstance(erro, dict):
        codigo = erro.get("codigo")
        if isinstance(codigo, str):
            return codigo
    return None


# ---------------------------------------------------------------------------
# LeitorDeLinhas
# ---------------------------------------------------------------------------


class Linha(NamedTuple):
    """Item produzido pelo ``LeitorDeLinhas``.

    - Linha normal: ``muito_grande`` falso e ``conteudo`` com os bytes da linha,
      sem o ``\\n`` e sem o ``\\r`` final (pode ser vazio).
    - Linha acima do limite: ``muito_grande`` verdadeiro e ``conteudo`` vazio.
      O Middleware responde ``MENSAGEM_MUITO_GRANDE``; o leitor ja descartou os
      bytes e segue na linha seguinte.
    """

    conteudo: bytes
    muito_grande: bool = False

    @property
    def texto(self) -> str:
        """Conteudo decodificado em UTF-8 (``UnicodeDecodeError`` se invalido)."""
        return self.conteudo.decode("utf-8")


class LeitorDeLinhas:
    """Separa um fluxo de bytes em linhas terminadas em ``\\n``.

    - Aceita particao arbitraria do fluxo (inclusive 1 byte por chamada).
    - Remove um ``\\r`` final da linha.
    - Linhas vazias sao entregues como ``Linha(b"")``.
    - Se uma linha passa de ``limite`` bytes (padrao 1 MiB, contados antes do
      ``\\n``), emite uma unica ``Linha(b"", muito_grande=True)`` assim que o
      excesso e detectado, descarta os bytes ate o proximo ``\\n`` e continua
      normalmente a partir da linha seguinte.
    - O resultado nao depende de como os bytes foram particionados.
    """

    def __init__(self, limite: int = LIMITE_LINHA) -> None:
        if limite <= 0:
            raise ValueError("limite deve ser positivo")
        self._limite = limite
        self._buffer = bytearray()
        self._descartando = False

    @property
    def limite(self) -> int:
        return self._limite

    @property
    def bytes_pendentes(self) -> int:
        """Bytes de uma linha ainda incompleta mantidos em memoria."""
        return len(self._buffer)

    def alimentar(self, dados: bytes | bytearray | memoryview) -> list[Linha]:
        """Consome ``dados`` e devolve as linhas completadas, em ordem."""
        dados = bytes(dados)
        saida: list[Linha] = []
        pos = 0
        total = len(dados)
        while pos < total:
            indice = dados.find(DELIMITADOR, pos)
            fim = total if indice == -1 else indice

            if not self._descartando:
                trecho = dados[pos:fim]
                if len(self._buffer) + len(trecho) > self._limite:
                    saida.append(Linha(b"", True))
                    self._buffer.clear()
                    self._descartando = True
                else:
                    self._buffer += trecho

            if indice == -1:
                break

            if self._descartando:
                self._descartando = False
            else:
                linha = bytes(self._buffer)
                self._buffer.clear()
                if linha.endswith(b"\r"):
                    linha = linha[:-1]
                saida.append(Linha(linha))
            pos = indice + 1
        return saida

    def separar(self, dados: Iterable[bytes]) -> list[Linha]:
        """Atalho: alimenta varios pedacos em sequencia e junta o resultado."""
        saida: list[Linha] = []
        for pedaco in dados:
            saida.extend(self.alimentar(pedaco))
        return saida
