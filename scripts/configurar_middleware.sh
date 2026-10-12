#!/usr/bin/env bash
# Configura e inicia o Middleware (maquina A) seguindo o README, secoes 4 a 8.
#
# Passos: verifica pre-requisitos, instala/inicia o PostgreSQL 16, cria usuario/banco/esquema,
# gera SEMPRE novas chaves, cria config/servidor.properties, libera o firewall (ufw), compila e
# SEMPRE inicia o Middleware.
#
# Uso: scripts/configurar_middleware.sh [opcoes]
#   --senha-db=SENHA    senha do usuario "comunicador" no PostgreSQL (padrao: DB_SENHA; senao a do
#                       servidor.properties; senao pergunta)
#   --ip=ENDERECO       IP desta maquina usado em ws.origens_permitidas (padrao: detectado)
#   -h, --ajuda         mostra esta ajuda
#
# Variaveis:
#   DB_SENHA   equivale a --senha-db (o parametro tem precedencia)
#   MVN        comando do Maven (padrao: mvn)
#   PYTHON     interpretador Python (padrao: python3)
#
# Usuario, banco e esquema nao sao recriados se ja existirem. As chaves sao regeradas a cada
# execucao: Clientes que usavam a chave anterior precisam receber a nova.
set -euo pipefail

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$RAIZ"

MVN="${MVN:-mvn}"
PYTHON="${PYTHON:-python3}"
DB_USUARIO="comunicador"
DB_NOME="comunicador"
ARQ_SERVIDOR="config/servidor.properties"
ARQ_CHAVES="config/chaves.properties"

IP_SERVIDOR=""
SENHA_DB="${DB_SENHA:-}"

for arg in "$@"; do
  case "$arg" in
    --ip=*) IP_SERVIDOR="${arg#--ip=}" ;;
    --senha-db=*) SENHA_DB="${arg#--senha-db=}" ;;
    -h|--ajuda) sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Opcao desconhecida: $arg (use --ajuda)" >&2; exit 2 ;;
  esac
done

SO="$(uname -s)"
passo() { printf '\n==> %s\n' "$*"; }
info()  { printf '    %s\n' "$*"; }
falha() { printf 'ERRO: %s\n' "$*" >&2; exit 1; }

# Substitui (ou acrescenta) CHAVE=VALOR em um .properties, sem interpretar o valor.
set_prop() {
  local arq="$1" chave="$2" valor="$3" tmp
  tmp="$(mktemp)"
  CHAVE="$chave" VALOR="$valor" awk '
    BEGIN { k = ENVIRON["CHAVE"]; v = ENVIRON["VALOR"]; feito = 0 }
    index($0, k "=") == 1 { print k "=" v; feito = 1; next }
    { print }
    END { if (!feito) print k "=" v }' "$arq" > "$tmp"
  cat "$tmp" > "$arq"
  rm -f "$tmp"
}

get_prop() {
  local arq="$1" chave="$2"
  grep -E "^${chave}=" "$arq" 2>/dev/null | head -n 1 | cut -d= -f2- || true
}

# ---------------------------------------------------------------- 2. Pre-requisitos
passo "Verificando pre-requisitos (JDK 21, Maven, Python 3)"

command -v java >/dev/null 2>&1 || falha "java nao encontrado. Instale o JDK 21."
VERSAO_JAVA="$(java -version 2>&1 | head -n 1 | sed -E 's/.*"([0-9]+)[."].*/\1/')"
[ "${VERSAO_JAVA:-0}" -ge 21 ] 2>/dev/null || falha "JDK 21 ou superior necessario (encontrado: ${VERSAO_JAVA:-desconhecido})."
info "java $VERSAO_JAVA"

command -v "$MVN" >/dev/null 2>&1 || falha "Maven nao encontrado. Instale-o ou defina MVN=/caminho/para/mvn."
info "$("$MVN" -version 2>&1 | head -n 1)"

command -v "$PYTHON" >/dev/null 2>&1 || falha "$PYTHON nao encontrado (necessario para gerar as chaves)."
info "$("$PYTHON" --version 2>&1)"

# ---------------------------------------------------------------- 5.1 PostgreSQL
passo "PostgreSQL 16"

for d in /opt/homebrew/opt/postgresql@16/bin /usr/local/opt/postgresql@16/bin /usr/lib/postgresql/16/bin; do
  [ -d "$d" ] && PATH="$d:$PATH"
done
export PATH

if ! command -v psql >/dev/null 2>&1; then
  info "psql nao encontrado; instalando o PostgreSQL 16..."
  case "$SO" in
    Darwin)
      command -v brew >/dev/null 2>&1 || falha "Homebrew nao encontrado. Instale o PostgreSQL 16 manualmente."
      brew install postgresql@16
      PATH="$(brew --prefix postgresql@16)/bin:$PATH"
      export PATH
      ;;
    Linux)
      command -v apt >/dev/null 2>&1 || falha "Instalador automatico so para Debian/Ubuntu (apt). Instale o PostgreSQL manualmente."
      sudo apt update
      sudo apt install -y postgresql postgresql-client
      ;;
    *) falha "Sistema nao suportado por este script. No Windows use scripts/configurar_middleware.ps1." ;;
  esac
fi

if ! pg_isready -q 2>/dev/null; then
  info "PostgreSQL parado; iniciando..."
  case "$SO" in
    Darwin) brew services start postgresql@16 >/dev/null ;;
    Linux)  sudo systemctl start postgresql ;;
  esac
  for _ in $(seq 1 30); do
    pg_isready -q 2>/dev/null && break
    sleep 1
  done
fi
pg_isready >/dev/null 2>&1 || falha "PostgreSQL nao respondeu em localhost:5432."
info "$(pg_isready)"

# Superusuario conforme o sistema (README, secao 5.2).
psql_admin() {
  case "$SO" in
    Darwin) psql -v ON_ERROR_STOP=1 -d postgres "$@" ;;
    *)      sudo -u postgres psql -v ON_ERROR_STOP=1 "$@" ;;
  esac
}

# ---------------------------------------------------------------- 5.2 Usuario e banco
passo "Usuario e banco de dados"

if [ ! -f "$ARQ_SERVIDOR" ]; then
  [ -f config/servidor.properties.example ] || falha "config/servidor.properties.example nao encontrado."
  cp config/servidor.properties.example "$ARQ_SERVIDOR"
  info "criado $ARQ_SERVIDOR a partir do exemplo"
fi

if [ -z "$SENHA_DB" ]; then
  atual="$(get_prop "$ARQ_SERVIDOR" db.senha)"
  if [ -n "$atual" ] && [ "$atual" != "<definir>" ]; then
    SENHA_DB="$atual"
    info "usando db.senha ja presente em $ARQ_SERVIDOR"
  fi
fi
if [ -z "$SENHA_DB" ]; then
  [ -t 0 ] || falha "Informe --senha-db=SENHA (entrada nao interativa)."
  read -r -s -p "    Escolha a senha do usuario '$DB_USUARIO' no banco: " SENHA_DB
  echo
  [ -n "$SENHA_DB" ] || falha "Senha vazia."
fi
case "$SENHA_DB" in
  *\\*) falha "A senha nao pode conter '\\' (e caractere de escape em .properties)." ;;
esac
SENHA_SQL="${SENHA_DB//\'/\'\'}"   # escapa aspas simples para o literal SQL

if [ "$(psql_admin -tAc "SELECT 1 FROM pg_roles WHERE rolname='$DB_USUARIO'")" = "1" ]; then
  psql_admin -q -c "ALTER USER $DB_USUARIO WITH PASSWORD '$SENHA_SQL';"
  info "usuario '$DB_USUARIO' ja existia; senha sincronizada"
else
  psql_admin -q -c "CREATE USER $DB_USUARIO WITH PASSWORD '$SENHA_SQL';"
  info "usuario '$DB_USUARIO' criado"
fi

if [ "$(psql_admin -tAc "SELECT 1 FROM pg_database WHERE datname='$DB_NOME'")" = "1" ]; then
  info "banco '$DB_NOME' ja existe"
else
  psql_admin -q -c "CREATE DATABASE $DB_NOME OWNER $DB_USUARIO;"
  info "banco '$DB_NOME' criado"
fi

# ---------------------------------------------------------------- 5.3 Esquema
passo "Esquema (sql/01_schema.sql)"

export PGPASSWORD="$SENHA_DB"
if [ -n "$(psql -h localhost -U "$DB_USUARIO" -d "$DB_NOME" -tAc "SELECT to_regclass('public.contas')")" ]; then
  info "tabela 'contas' ja existe; esquema mantido"
else
  psql -v ON_ERROR_STOP=1 -q -h localhost -U "$DB_USUARIO" -d "$DB_NOME" -f sql/01_schema.sql
  info "esquema aplicado"
fi
psql -h localhost -U "$DB_USUARIO" -d "$DB_NOME" -c '\dt'
unset PGPASSWORD

# ---------------------------------------------------------------- 6.1 Chaves
passo "Chaves do servidor ($ARQ_CHAVES)"

"$PYTHON" scripts/gerar_chave.py --par > "$ARQ_CHAVES"
chmod 600 "$ARQ_CHAVES"
info "novas chaves geradas"
info "AVISO: Clientes que tinham a chave anterior precisam receber a nova (secao 8 do README)"

# ---------------------------------------------------------------- 4.1 IP e 6.2 servidor.properties
passo "IP da maquina e $ARQ_SERVIDOR"

if [ -z "$IP_SERVIDOR" ]; then
  case "$SO" in
    Darwin) IP_SERVIDOR="$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || true)" ;;
    *)      IP_SERVIDOR="$(hostname -I 2>/dev/null | awk '{print $1}' || true)" ;;
  esac
fi

PORTA_TCP="$(get_prop "$ARQ_SERVIDOR" porta.tcp)";   PORTA_TCP="${PORTA_TCP:-9000}"
PORTA_WS="$(get_prop "$ARQ_SERVIDOR" porta.ws)";     PORTA_WS="${PORTA_WS:-9001}"
PORTA_HTTP="$(get_prop "$ARQ_SERVIDOR" porta.http)"; PORTA_HTTP="${PORTA_HTTP:-8080}"

ORIGENS="http://localhost:$PORTA_HTTP"
if [ -n "$IP_SERVIDOR" ]; then
  ORIGENS="$ORIGENS,http://$IP_SERVIDOR:$PORTA_HTTP"
  info "IP detectado/informado: $IP_SERVIDOR"
else
  info "AVISO: IP nao detectado; use --ip=ENDERECO para que o Cliente_Web funcione em outras maquinas"
fi

set_prop "$ARQ_SERVIDOR" db.senha "$SENHA_DB"
set_prop "$ARQ_SERVIDOR" ws.origens_permitidas "$ORIGENS"
chmod 600 "$ARQ_SERVIDOR"
info "db.senha atualizada"
info "ws.origens_permitidas=$ORIGENS"

# ---------------------------------------------------------------- 4.2 Firewall
passo "Firewall (portas $PORTA_TCP, $PORTA_WS, $PORTA_HTTP)"

case "$SO" in
  Linux)
    if command -v ufw >/dev/null 2>&1 && sudo ufw status 2>/dev/null | grep -q "Status: active"; then
      for p in "$PORTA_TCP" "$PORTA_WS" "$PORTA_HTTP"; do sudo ufw allow "$p/tcp" >/dev/null; done
      info "portas liberadas no ufw (a 5432 permanece fechada)"
    else
      info "ufw inativo ou ausente; nada a fazer"
    fi
    ;;
  Darwin)
    info "macOS: se o firewall estiver ligado, aceite o aviso 'Permitir conexoes de entrada para java' na partida"
    ;;
esac

# ---------------------------------------------------------------- 8. Chave_Transito local
passo "Chave_Transito para Clientes nesta maquina"

CHAVE_TRANSITO="$(get_prop "$ARQ_CHAVES" chave.transito)"
mkdir -p .comunicador
printf '%s\n' "$CHAVE_TRANSITO" > .comunicador/chave_transito.txt
chmod 600 .comunicador/chave_transito.txt
info "gravada em .comunicador/chave_transito.txt (Clientes Desktop/Python locais)"
info "Para outras maquinas, entregue por fora do socket esta chave (64 hex):"
info "  $CHAVE_TRANSITO"
info "Cliente_Web: http://${IP_SERVIDOR:-<IP_SERVIDOR>}:$PORTA_HTTP/#chave=$CHAVE_TRANSITO"

# ---------------------------------------------------------------- 7. Compilar e iniciar
passo "Compilando o Middleware"
"$MVN" -q -pl middleware -am -DskipTests package
info "compilacao concluida"

passo "Iniciando o Middleware (Ctrl+C para encerrar)"
export DB_SENHA="$SENHA_DB"
JAR="$(ls middleware/target/middleware-*.jar | grep -v -- '-sources' | head -n 1)"
[ -n "$JAR" ] || falha "Jar do Middleware nao encontrado em middleware/target."
exec java -jar "$JAR" "$ARQ_SERVIDOR" "$ARQ_CHAVES"
