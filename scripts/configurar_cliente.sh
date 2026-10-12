#!/usr/bin/env bash
# Configura e inicia um Cliente (maquinas B e C) seguindo o README, secoes 8 a 10.
#
# Passos: verifica pre-requisitos do Cliente escolhido, grava a Chave_Transito em
# .comunicador/chave_transito.txt, testa a conectividade com o Middleware, compila (Desktop)
# e SEMPRE inicia o Cliente.
#
# Uso: scripts/configurar_cliente.sh <desktop|python> [--host=IP_SERVIDOR] [--porta=9000] [--chave=64HEX]
#   desktop         Cliente_Desktop (Swing; precisa de JDK 21 e Maven)
#   python          Cliente_Terceiro (Tkinter; precisa de Python 3.11+ com Tkinter)
#   --host=H        endereco do Middleware (padrao: COMUNICADOR_HOST; senao pergunta)
#   --porta=P       porta TCP do Middleware (padrao: COMUNICADOR_PORTA ou 9000)
#   --chave=K       chave.transito do servidor, 64 hexadecimais (padrao: CHAVE_TRANSITO; senao
#                   pergunta. Em branco, mantem a de .comunicador/chave_transito.txt se valida)
#   -h, --ajuda     mostra esta ajuda
#
# Variaveis: MVN (comando do Maven, padrao mvn), PYTHON (padrao python3).
# O Cliente_Web nao precisa de script: abra http://<IP_SERVIDOR>:8080 no navegador (README, secao 11).
set -euo pipefail

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$RAIZ"

MVN="${MVN:-mvn}"
PYTHON="${PYTHON:-python3}"
ARQ_CHAVE=".comunicador/chave_transito.txt"

CLIENTE=""
HOST="${COMUNICADOR_HOST:-}"
PORTA="${COMUNICADOR_PORTA:-9000}"
CHAVE="${CHAVE_TRANSITO:-}"

passo() { printf '\n==> %s\n' "$*"; }
info()  { printf '    %s\n' "$*"; }
falha() { printf 'ERRO: %s\n' "$*" >&2; exit 1; }

for arg in "$@"; do
  case "$arg" in
    desktop|python) CLIENTE="$arg" ;;
    --host=*) HOST="${arg#--host=}" ;;
    --porta=*) PORTA="${arg#--porta=}" ;;
    --chave=*) CHAVE="${arg#--chave=}" ;;
    -h|--ajuda) sed -n '2,18p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Argumento desconhecido: $arg (use --ajuda)" >&2; exit 2 ;;
  esac
done
[ -n "$CLIENTE" ] || { echo "Informe o Cliente: desktop ou python (use --ajuda)" >&2; exit 2; }

# ---------------------------------------------------------------- Parametros
passo "Parametros de conexao"

if [ -z "$HOST" ]; then
  [ -t 0 ] || falha "Informe --host=IP_SERVIDOR (entrada nao interativa)."
  read -r -p "    IP ou nome do servidor (Middleware): " HOST
fi
[ -n "$HOST" ] || falha "Host vazio."
[[ "$PORTA" =~ ^[0-9]+$ ]] || falha "Porta invalida: $PORTA"
info "Middleware: $HOST:$PORTA"

if [ -z "$CHAVE" ]; then
  [ -t 0 ] || falha "Informe --chave=<64 hex> (entrada nao interativa)."
  read -r -p "    chave.transito (64 hex; Enter mantem a atual): " CHAVE
  if [ -z "$CHAVE" ]; then
    [ -f "$ARQ_CHAVE" ] && CHAVE="$(tr -d '[:space:]' < "$ARQ_CHAVE")"
  fi
fi
CHAVE="$(printf '%s' "$CHAVE" | tr -d '[:space:]')"
[[ "$CHAVE" =~ ^[0-9a-fA-F]{64}$ ]] || falha "A chave.transito deve ter exatamente 64 caracteres hexadecimais."

# ---------------------------------------------------------------- Pre-requisitos
passo "Verificando pre-requisitos ($CLIENTE)"

if [ "$CLIENTE" = "desktop" ]; then
  command -v java >/dev/null 2>&1 || falha "java nao encontrado. Instale o JDK 21."
  VERSAO_JAVA="$(java -version 2>&1 | head -n 1 | sed -E 's/.*"([0-9]+)[."].*/\1/')"
  [ "${VERSAO_JAVA:-0}" -ge 21 ] 2>/dev/null || falha "JDK 21 ou superior necessario (encontrado: ${VERSAO_JAVA:-desconhecido})."
  info "java $VERSAO_JAVA"
  command -v "$MVN" >/dev/null 2>&1 || falha "Maven nao encontrado. Instale-o ou defina MVN=/caminho/para/mvn."
  info "$("$MVN" -version 2>&1 | head -n 1)"
else
  command -v "$PYTHON" >/dev/null 2>&1 || falha "$PYTHON nao encontrado. Instale o Python 3.11+."
  "$PYTHON" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 11) else 1)' \
    || falha "Python 3.11 ou superior necessario (encontrado: $("$PYTHON" --version 2>&1))."
  "$PYTHON" -c 'import tkinter' 2>/dev/null \
    || falha "Tkinter ausente. Debian/Ubuntu: sudo apt install python3-tk. macOS: brew install python-tk."
  info "$("$PYTHON" --version 2>&1) com Tkinter"
fi

[ -d recursos ] || falha "Pasta recursos/ nao encontrada na raiz do projeto ($RAIZ)."
info "recursos/ encontrada"

# ---------------------------------------------------------------- 8. Chave_Transito
passo "Chave_Transito"

mkdir -p .comunicador
printf '%s\n' "$CHAVE" > "$ARQ_CHAVE"
chmod 600 "$ARQ_CHAVE"
info "gravada em $ARQ_CHAVE"

# ---------------------------------------------------------------- 4.3 Conectividade
passo "Conectividade com $HOST:$PORTA"

if command -v nc >/dev/null 2>&1; then
  nc -z -w 3 "$HOST" "$PORTA" >/dev/null 2>&1 && ok=1 || ok=0
else
  (exec 3<>"/dev/tcp/$HOST/$PORTA") >/dev/null 2>&1 && ok=1 || ok=0
fi
if [ "$ok" = "1" ]; then
  info "porta alcancavel"
else
  info "AVISO: $HOST:$PORTA inacessivel agora. Confira se o Middleware esta no ar, o IP e o firewall (README, secao 12)."
  info "O Cliente sera iniciado mesmo assim e tentara reconectar."
fi

# ---------------------------------------------------------------- 9 / 10. Iniciar
case "$CLIENTE" in
  desktop)
    passo "Compilando o Cliente_Desktop"
    "$MVN" -q -pl cliente-desktop -am -DskipTests package \
      org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies \
      -DoutputDirectory=target/lib -DincludeScope=runtime
    JAR="$(ls cliente-desktop/target/cliente-desktop-*.jar | grep -v -- '-sources' | head -n 1)"
    [ -n "$JAR" ] || falha "Jar do Cliente_Desktop nao encontrado em cliente-desktop/target."

    passo "Iniciando o Cliente_Desktop"
    exec java -cp "$JAR:cliente-desktop/target/lib/*" br.cefet.sd.cliente.desktop.ClienteDesktop \
      "--host=$HOST" "--porta=$PORTA"
    ;;
  python)
    passo "Iniciando o Cliente_Python"
    cd cliente-python
    exec "$PYTHON" -m comunicador --host "$HOST" --porta "$PORTA"
    ;;
esac
