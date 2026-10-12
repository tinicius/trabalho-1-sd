#!/usr/bin/env python3
"""Gera registros antigos e recentes de teste nos repositorios de arquivo.

Serve para demonstrar a Limpeza do Middleware sem aguardar 7 dias
ou 12 meses. Escreve, no diretorio de dados do Middleware, quatro arquivos com
um objeto JSON por linha (o quinto repositorio, Contas, fica no PostgreSQL e
nao e tocado por este script):

    conversas.txt       retencao 7 dias       (Repositorio_Conversas)
    logs/envios.txt     retencao 12 meses     (Log_Envios)
    logs/erros.txt      retencao 90 dias      (Log_Erros, retencao.logs.dias)
    logs/acessos.txt    retencao 90 dias      (Log_Acessos, retencao.logs.dias)

Para cada arquivo sao gravados registros VENCIDOS (a Limpeza deve remove-los)
e registros RECENTES (a Limpeza deve preserva-los):

    conversas   vencidas: 10 e 30 dias      recentes: 1 hora, 2 e 6 dias
    envios      vencidos: 13 e 18 meses     recentes: agora, 1, 6 e 11 meses
    erros       vencidos: 100, 120, 200 dias   recentes: 1, 30 e 80 dias
    acessos     vencidos: 100, 120, 200 dias   recentes: 1, 30 e 80 dias

O formato de cada linha segue as classes Java do Middleware
(RepositorioConversasArquivo, LogEnviosArquivo, LogErrosArquivo e
LogAcessosArquivo): mesmos campos, mesma ordem, JSON compacto em uma linha e
`ts` em ISO-8601 UTC com milissegundos e `Z`. O `conteudo` das conversas e
cifrado de verdade com a Chave_Repositorio, usando a Cifra_Manual do projeto
(cliente-python/comunicador/nucleo/cifra.py). O script usa so a biblioteca
padrao e essa cifra.

IMPORTANTE: execute com o Middleware PARADO. O script apenas ANEXA linhas aos
arquivos (cria-os se faltarem), sem o lock do Middleware. Os registros saem em
ordem cronologica em cada arquivo. Ao subir, o Middleware executa a Limpeza
antes de abrir os listeners, e os registros vencidos desaparecem.

A Chave_Repositorio vem de `chave.repositorio` em config/chaves.properties.
Se esse arquivo nao existir (ou para sobrepor o valor dele), use
--chave-repositorio com 64 caracteres hexadecimais. A chave nunca e impressa.

Uso:
    python3 scripts/gerar_registros_antigos.py
    python3 scripts/gerar_registros_antigos.py --dados-dir dados
    python3 scripts/gerar_registros_antigos.py --chave-repositorio <64 hex>
    python3 scripts/gerar_registros_antigos.py --dados-dir /tmp/d --substituir
"""

import argparse
import calendar
import json
import sys
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

RAIZ = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(RAIZ / "cliente-python"))

from comunicador.nucleo import cifra  # noqa: E402  (depende do sys.path acima)

CAMINHO_CHAVES_PADRAO = RAIZ / "config" / "chaves.properties"
DADOS_DIR_PADRAO = RAIZ / "dados"

ARQ_CONVERSAS = "conversas.txt"
ARQ_ENVIOS = "logs/envios.txt"
ARQ_ERROS = "logs/erros.txt"
ARQ_ACESSOS = "logs/acessos.txt"

LOGINS = ["ana", "bruno", "carla", "diego_92", "eduarda", "unicode"]
CLIENTES = ["desktop", "web", "python"]
ENDERECOS = ["192.168.0.12", "192.168.0.27", "10.0.0.5", "127.0.0.1"]

# (dias atras, vencido?) por arquivo. "Vencido" e so documentacao da saida: a
# Limpeza decide pelos limites configurados.
CONVERSAS_DIAS = [(30, True), (10, True), (6, False), (2, False)]
CONVERSAS_HORAS_RECENTES = [1]
LOGS_DIAS = [(200, True), (120, True), (100, True), (80, False), (30, False), (1, False)]
ENVIOS_MESES = [(18, True), (13, True), (11, False), (6, False), (1, False)]

TEXTOS = [
    "Bom dia, pessoal!",
    "Alguem viu o enunciado do trabalho?",
    "Atenção: reunião às 15h, coração é ñ ü ç",
    "Bora! \U0001F600\U0001F389 café",
    "Entrega na sexta, nao esquecam.",
]
FIGURINHAS = ["fig_01", "fig_03", "fig_07", "fig_12"]

ERROS_MODELO = [
    # origem, modulo, tipo, descricao
    ("middleware", "ProcessadorProtocolo", "NullPointerException", "Falha ao processar operacao"),
    ("middleware", "Distribuidor", "IOException", "Falha ao escrever na sessao"),
    ("cliente", "conexao.js", "TypeError", "Cannot read properties of undefined"),
    ("cliente", "conexao.py", "ConnectionResetError", "Conexao encerrada pelo servidor"),
]
ACESSOS_MOTIVOS = ["logout", "fechada_pelo_cliente", "timeout"]


# ---------------------------------------------------------------------------
# Tempo
# ---------------------------------------------------------------------------

def ts_texto(instante: datetime) -> str:
    """ISO-8601 UTC com milissegundos e Z (igual a RegistroJson.tsParaTexto)."""
    instante = instante.astimezone(timezone.utc)
    return instante.strftime("%Y-%m-%dT%H:%M:%S.") + f"{instante.microsecond // 1000:03d}Z"


def subtrair_meses(instante: datetime, meses: int) -> datetime:
    """Subtrai meses de calendario, ajustando o dia ao fim do mes de destino."""
    total = instante.year * 12 + (instante.month - 1) - meses
    ano, mes = divmod(total, 12)
    mes += 1
    dia = min(instante.day, calendar.monthrange(ano, mes)[1])
    return instante.replace(year=ano, month=mes, day=dia)


def interpretar_agora(texto):
    if texto is None:
        agora = datetime.now(timezone.utc)
    else:
        agora = datetime.fromisoformat(texto.replace("Z", "+00:00"))
        if agora.tzinfo is None:
            agora = agora.replace(tzinfo=timezone.utc)
    return agora.astimezone(timezone.utc).replace(microsecond=(agora.microsecond // 1000) * 1000)


# ---------------------------------------------------------------------------
# Chave_Repositorio
# ---------------------------------------------------------------------------

def ler_propriedades(caminho: Path) -> dict:
    props = {}
    for linha in caminho.read_text(encoding="utf-8").splitlines():
        linha = linha.strip()
        if not linha or linha[0] in "#!" or "=" not in linha:
            continue
        nome, _, valor = linha.partition("=")
        props[nome.strip()] = valor.strip()
    return props


def chave_de_hex(hexa: str) -> bytes:
    hexa = hexa.strip()
    if len(hexa) != 64:
        raise ValueError("a Chave_Repositorio deve ter 64 caracteres hexadecimais")
    try:
        chave = bytes.fromhex(hexa)
    except ValueError:
        raise ValueError("a Chave_Repositorio deve ser hexadecimal") from None
    return chave


def obter_chave_repositorio(chave_arg, caminho_chaves: Path) -> bytes:
    if chave_arg:
        return chave_de_hex(chave_arg)
    if not caminho_chaves.is_file():
        raise ValueError(
            f"{caminho_chaves} nao existe; informe --chave-repositorio (64 hex) "
            "ou crie o arquivo (veja config/chaves.properties.example)"
        )
    valor = ler_propriedades(caminho_chaves).get("chave.repositorio")
    if not valor:
        raise ValueError(f"'chave.repositorio' ausente em {caminho_chaves}")
    return chave_de_hex(valor)


# ---------------------------------------------------------------------------
# Linhas (mesmos campos e ordem das classes Java)
# ---------------------------------------------------------------------------

def linha_json(campos: dict) -> str:
    """JSON compacto de uma linha, nao ASCII (como o Jackson)."""
    return json.dumps(campos, ensure_ascii=False, separators=(",", ":"))


def linha_conversa(chave: bytes, indice: int, ts: datetime) -> str:
    """mid, remetente, ts, tipo, conteudo (cifrado com a Chave_Repositorio)."""
    if indice % 4 == 3:
        tipo, claro = "figurinha", FIGURINHAS[indice % len(FIGURINHAS)]
    else:
        tipo, claro = "texto", TEXTOS[indice % len(TEXTOS)]
    return linha_json({
        "mid": str(uuid.uuid4()),
        "remetente": LOGINS[indice % len(LOGINS)],
        "ts": ts_texto(ts),
        "tipo": tipo,
        "conteudo": cifra.cifrar(chave, claro),
    })


def linha_envio(indice: int, ts: datetime) -> str:
    """Exatamente ts e login."""
    return linha_json({"ts": ts_texto(ts), "login": LOGINS[indice % len(LOGINS)]})


def linha_erro(indice: int, ts: datetime) -> str:
    """ts, origem, modulo, tipo, descricao, pilha, sessao, dispositivo, cliente,
    evid, descartados."""
    origem, modulo, tipo, descricao = ERROS_MODELO[indice % len(ERROS_MODELO)]
    de_cliente = origem == "cliente"
    return linha_json({
        "ts": ts_texto(ts),
        "origem": origem,
        "modulo": modulo,
        "tipo": tipo,
        "descricao": descricao,
        "pilha": f"{tipo}: {descricao}\n\tat {modulo}.executar({modulo}:{10 + indice})",
        "sessao": str(uuid.uuid4()) if indice % 2 == 0 else None,
        "dispositivo": str(uuid.uuid4()) if de_cliente else None,
        "cliente": CLIENTES[indice % len(CLIENTES)] if de_cliente else None,
        "evid": str(uuid.uuid4()) if de_cliente else None,
        "descartados": 0 if indice % 3 else 2,
    })


def linhas_acesso(indice: int, ts: datetime) -> list:
    """Uma sessao completa: conexao, login_ok e desconexao; e um login_recusado.

    Campos: ts, evento, login, sessao, dispositivo, cliente, endereco e, so em
    desconexao, motivo. Os eventos de uma sessao saem com ts crescentes.
    """
    login = LOGINS[indice % len(LOGINS)]
    cliente = CLIENTES[indice % len(CLIENTES)]
    endereco = ENDERECOS[indice % len(ENDERECOS)]
    sessao = str(uuid.uuid4())
    dispositivo = str(uuid.uuid4())

    def evento(nome, quando, **extra):
        campos = {
            "ts": ts_texto(quando),
            "evento": nome,
            "login": extra.get("login"),
            "sessao": extra.get("sessao"),
            "dispositivo": dispositivo,
            "cliente": cliente,
            "endereco": endereco,
        }
        if extra.get("motivo") is not None:
            campos["motivo"] = extra["motivo"]
        return linha_json(campos)

    if indice % 3 == 2:  # tentativa recusada (login so se casar com o padrao)
        return [evento("login_recusado", ts, login=login)]
    return [
        evento("conexao", ts),
        evento("login_ok", ts + timedelta(milliseconds=120), login=login, sessao=sessao),
        evento(
            "desconexao",
            ts + timedelta(minutes=5),
            login=login,
            sessao=sessao,
            motivo=ACESSOS_MOTIVOS[indice % len(ACESSOS_MOTIVOS)],
        ),
    ]


# ---------------------------------------------------------------------------
# Montagem dos conjuntos (lista de (ts, linha), ordenada por ts)
# ---------------------------------------------------------------------------

def gerar_conversas(chave: bytes, agora: datetime) -> list:
    instantes = [agora - timedelta(days=d, minutes=3 * i) for i, (d, _) in enumerate(CONVERSAS_DIAS)]
    instantes += [agora - timedelta(hours=h) for h in CONVERSAS_HORAS_RECENTES]
    itens = []
    for base in instantes:
        for k in range(3):  # 3 mensagens por instante-base, 1 min entre elas
            itens.append(base + timedelta(minutes=k))
    itens.sort()
    return [(ts, linha_conversa(chave, i, ts)) for i, ts in enumerate(itens)]


def gerar_envios(agora: datetime) -> list:
    itens = []
    for meses, _ in ENVIOS_MESES:
        base = subtrair_meses(agora, meses)
        itens += [base + timedelta(minutes=k) for k in range(3)]
    itens += [agora - timedelta(seconds=30 * k) for k in range(3)]  # recentes: agora
    itens.sort()
    return [(ts, linha_envio(i, ts)) for i, ts in enumerate(itens)]


def gerar_erros(agora: datetime) -> list:
    itens = []
    for dias, _ in LOGS_DIAS:
        base = agora - timedelta(days=dias)
        itens += [base + timedelta(minutes=2 * k) for k in range(2)]
    itens.sort()
    return [(ts, linha_erro(i, ts)) for i, ts in enumerate(itens)]


def gerar_acessos(agora: datetime) -> list:
    bases = []
    for dias, _ in LOGS_DIAS:
        base = agora - timedelta(days=dias)
        bases += [base + timedelta(minutes=10 * k) for k in range(3)]
    bases.sort()
    itens = []
    for i, base in enumerate(bases):
        for linha in linhas_acesso(i, base):
            itens.append((json.loads(linha)["ts"], linha))
    itens.sort(key=lambda par: par[0])  # ts ISO com ms e Z ordena como texto
    return itens


# ---------------------------------------------------------------------------
# Gravacao
# ---------------------------------------------------------------------------

def gravar(caminho: Path, itens: list, substituir: bool) -> None:
    caminho.parent.mkdir(parents=True, exist_ok=True)
    modo = "w" if substituir else "a"
    with open(caminho, modo, encoding="utf-8", newline="\n") as saida:
        for _, linha in itens:
            saida.write(linha + "\n")


def _ts_dt(valor) -> datetime:
    if isinstance(valor, datetime):
        return valor
    return datetime.fromisoformat(valor.replace("Z", "+00:00"))


def resumo(nome: str, itens: list, limite: datetime) -> str:
    vencidos = sum(1 for ts, _ in itens if _ts_dt(ts) < limite)
    return (
        f"  {nome:<18} {len(itens):>3} registros: "
        f"{vencidos} vencidos, {len(itens) - vencidos} recentes "
        f"(limite {ts_texto(limite)})"
    )


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="Grava registros antigos e recentes nos repositorios de arquivo "
        "para demonstrar a Limpeza (execute com o Middleware parado)."
    )
    parser.add_argument("--dados-dir", type=Path, default=DADOS_DIR_PADRAO,
                        help="diretorio de dados do Middleware (padrao: dados/ na raiz do projeto)")
    parser.add_argument("--chaves", type=Path, default=CAMINHO_CHAVES_PADRAO,
                        help="arquivo de chaves (padrao: config/chaves.properties)")
    parser.add_argument("--chave-repositorio", metavar="HEX64",
                        help="Chave_Repositorio em 64 caracteres hexadecimais "
                        "(sobrepoe o arquivo de chaves)")
    parser.add_argument("--agora", metavar="ISO8601",
                        help="instante de referencia (padrao: agora, em UTC); para testes")
    parser.add_argument("--substituir", action="store_true",
                        help="substitui o conteudo dos arquivos em vez de anexar")
    args = parser.parse_args(argv)

    try:
        chave = obter_chave_repositorio(args.chave_repositorio, args.chaves)
        agora = interpretar_agora(args.agora)
    except ValueError as erro:
        print(f"erro: {erro}", file=sys.stderr)
        return 2

    conjuntos = [
        (ARQ_CONVERSAS, gerar_conversas(chave, agora), agora - timedelta(days=7)),
        (ARQ_ENVIOS, gerar_envios(agora), subtrair_meses(agora, 12)),
        (ARQ_ERROS, gerar_erros(agora), agora - timedelta(days=90)),
        (ARQ_ACESSOS, gerar_acessos(agora), agora - timedelta(days=90)),
    ]

    print(f"Diretorio de dados: {args.dados_dir}")
    print("(vencidos/recentes calculados com retencoes padrao: 7 dias, 12 meses, 90 dias)")
    for nome, itens, limite in conjuntos:
        gravar(args.dados_dir / nome, itens, args.substituir)
        print(resumo(nome, itens, limite))
    print("Pronto. Suba o Middleware: a Limpeza inicial removera os registros vencidos.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
