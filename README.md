# Comunicador Distribuído de Grupo: instruções de execução em máquinas distintas

Sistema de bate-papo em grupo (uma única sala) do Trabalho 1 de Sistemas Distribuídos (CEFET), com um Middleware em Java e
três Clientes: Desktop (Swing), Web (HTML/JS) e Python (Tkinter).

Este documento é o passo a passo para preparar o ambiente, compilar, configurar e iniciar **todos os módulos**, cada um em
sua própria máquina.

## Sumário

0. [Execução simplificada (scripts)](#0-execução-simplificada-scripts)
1. [Topologia](#1-topologia)
2. [Pré-requisitos por máquina](#2-pré-requisitos-por-máquina)
3. [Obter o código](#3-obter-o-código)
4. [Rede: IP, firewall e conectividade](#4-rede-ip-firewall-e-conectividade)
5. [Máquina A: banco de dados](#5-máquina-a-banco-de-dados)
6. [Máquina A: chaves e configuração do Middleware](#6-máquina-a-chaves-e-configuração-do-middleware)
7. [Máquina A: compilar e iniciar o Middleware](#7-máquina-a-compilar-e-iniciar-o-middleware)
8. [Entregar a Chave_Transito aos Clientes](#8-entregar-a-chave_transito-aos-clientes)
9. [Máquina B: Cliente_Desktop](#9-máquina-b-cliente_desktop)
10. [Máquina C: Cliente_Terceiro (Python)](#10-máquina-c-cliente_terceiro-python)
11. [Máquina D: Cliente_Web](#11-máquina-d-cliente_web)
12. [Problemas comuns](#12-problemas-comuns)

## 0. Execução simplificada (scripts)

Esta seção é uma forma rápida de executar o sistema: os scripts em `scripts/` fazem a configuração das seções 4 a 10 com um
único comando por módulo. Os detalhes de cada passo (pré-requisitos, rede, banco, chaves e configuração) estão nas próximas
seções, e valem também se algum script falhar. `<SENHA_DB>` é um valor que você define: é a senha que o usuário `comunicador`
terá no banco de dados, e pode ser qualquer uma (sem `\`). `<CHAVE_TRANSITO>` é a chave que o script do Middleware imprime ao final.

### 0.1 Máquina A: Middleware

Linux/macOS:

```bash
scripts/configurar_middleware.sh --senha-db='<SENHA_DB>' --ip=<IP_SERVIDOR>
```
Windows:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\configurar_middleware.ps1 -SenhaDb '<SENHA_DB>' -Ip <IP_SERVIDOR>
```

### 0.2 Máquinas B e C: Clientes Desktop e Python

Desktop, Linux/macOS:

```bash
scripts/configurar_cliente.sh desktop --host=<IP_SERVIDOR> --chave=<CHAVE_TRANSITO>
```

Desktop, Windows:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\configurar_cliente.ps1 desktop -Servidor <IP_SERVIDOR> -Chave <CHAVE_TRANSITO>
```

Python, Linux/macOS:

```bash
scripts/configurar_cliente.sh python --host=<IP_SERVIDOR> --chave=<CHAVE_TRANSITO>
```

Python, Windows:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\configurar_cliente.ps1 python -Servidor <IP_SERVIDOR> -Chave <CHAVE_TRANSITO>
```

### 0.3 Máquina D: Cliente_Web

```
http://<IP_SERVIDOR>:8080/#chave=<CHAVE_TRANSITO>
```

## 1. Topologia

O Middleware é o único módulo que acessa o banco, os repositórios e os logs. Os Clientes só falam com ele, pela rede.

| Máquina | Papel | O que roda | Portas (entrada) |
|---|---|---|---|
| **A** (servidor) | Servidor | PostgreSQL + Middleware | 9000/TCP, 9001 (WebSocket), 8080/HTTP. A 5432 só localmente |
| **B** | Cliente_Desktop | Aplicação Swing | nenhuma |
| **C** | Cliente_Terceiro | Aplicação Python/Tkinter | nenhuma |
| **D** | Cliente_Web | Navegador | nenhuma |

```
 B  Cliente_Desktop ──TCP 9000──────┐
 C  Cliente_Python  ──TCP 9000──────┤
 D  Navegador ──HTTP 8080 (página)──┼──►  A  Middleware ──JDBC 5432──► PostgreSQL (A)
    Navegador ──WebSocket 9001──────┘            └──► dados/ (conversas e logs, em TXT)
```

As quatro funções podem ser reunidas em menos máquinas (por exemplo, B e D na mesma). O que não muda: a Chave_Transito
precisa chegar a cada Cliente por fora do socket (seção 8). Nas instruções abaixo, `<IP_SERVIDOR>` é o endereço da
máquina A (exemplo: `192.168.0.10`).

## 2. Pré-requisitos por máquina

| Item | Versão | A | B | C | D |
|---|---|:-:|:-:|:-:|:-:|
| JDK | 21 | sim | sim | | |
| Maven | 3.x | sim | sim | | |
| PostgreSQL | 16 | sim | | | |
| Python | 3.11+ **com Tkinter** | sim (script de geração de chaves) | | sim | |
| Navegador moderno (Chrome, Edge, Firefox) | | | | | sim |
| Acesso à internet na primeira compilação | | sim | sim | | |

- O Cliente_Python não tem dependências além da biblioteca padrão. Em Debian/Ubuntu, o Tkinter vem no pacote `python3-tk`.
- Os Clientes Desktop e Python precisam de ambiente gráfico.
- O Maven baixa as dependências (Jackson, Java-WebSocket, driver PostgreSQL) do repositório Maven na primeira compilação.

Confira em cada máquina:

```bash
java -version          # deve indicar 21
mvn -version           # Maven 3.x usando o JDK 21
python3 --version      # 3.11 ou superior
python3 -c "import tkinter; print('Tkinter OK')"
```

Se o Maven não estiver no `PATH`, aponte-o para os scripts: `export MVN=/caminho/para/apache-maven/bin/mvn`.

## 3. Obter o código

```bash
git clone <URL-do-repositorio> trabalho_1
```

## 4. Rede: IP, firewall e conectividade

O Middleware escuta em **todas as interfaces** de rede, não só em `localhost`. As máquinas precisam estar na mesma rede
(ou ter rota entre si).

### 4.1 Descobrir o IP da máquina A

| Sistema | Comando |
|---|---|
| macOS | `ipconfig getifaddr en0` (Wi-Fi. Use `en1` ou outra interface se vier vazio) |
| Linux | `hostname -I` |
| Windows | `ipconfig` (campo "Endereço IPv4") |

Anote o resultado como `<IP_SERVIDOR>`.

### 4.2 Liberar as portas no firewall da máquina A

Libere a entrada TCP das portas **9000**, **9001** e **8080**. **Não** libere a 5432: só o Middleware usa o banco.

- **Windows** (PowerShell como administrador):
  ```powershell
  New-NetFirewallRule -DisplayName "Comunicador" -Direction Inbound -Protocol TCP -LocalPort 9000,9001,8080 -Action Allow
  ```
- **Ubuntu/Debian** (se o `ufw` estiver ativo):
  ```bash
  sudo ufw allow 9000/tcp
  sudo ufw allow 9001/tcp
  sudo ufw allow 8080/tcp
  ```
- **macOS:** se o firewall estiver ligado, aceite o aviso "Permitir conexões de entrada para java" na primeira partida do
  Middleware (ou adicione o `java` em Ajustes do Sistema > Rede > Firewall).

### 4.3 Testar a conectividade (depois de iniciar o Middleware, seção 7)

Nas máquinas B, C e D:

```bash
nc -vz <IP_SERVIDOR> 9000     # Desktop e Python
nc -vz <IP_SERVIDOR> 9001     # Web (WebSocket)
nc -vz <IP_SERVIDOR> 8080     # Web (página)
```

No Windows (PowerShell): `Test-NetConnection <IP_SERVIDOR> -Port 9000`. Se algum teste falhar, veja a
[seção 12](#12-problemas-comuns).

## 5. Máquina A: banco de dados

O banco guarda **apenas as contas**. Conversas e logs ficam em arquivos TXT no servidor.

### 5.1 Instalar e iniciar o PostgreSQL 16

- **macOS (Homebrew):**
  ```bash
  brew install postgresql@16
  brew services start postgresql@16
  echo 'export PATH="/opt/homebrew/opt/postgresql@16/bin:$PATH"' >> ~/.zshrc
  source ~/.zshrc
  ```
- **Ubuntu/Debian:**
  ```bash
  sudo apt update && sudo apt install postgresql postgresql-client
  sudo systemctl start postgresql
  ```
- **Windows:** instale a versão 16 em <https://www.postgresql.org/download/windows/> (porta 5432, componentes padrão), anote
  a senha do usuário `postgres` e adicione `C:\Program Files\PostgreSQL\16\bin` ao `PATH`.

Confirme:

```bash
pg_isready          # deve responder "accepting connections"
```

### 5.2 Criar o usuário e o banco

Escolha uma senha (`<SENHA_DB>`), que irá em `db.senha` na seção 6. O superusuário depende do sistema:

| Sistema | Prefixo do comando |
|---|---|
| macOS (Homebrew) | `psql -d postgres -c ...` |
| Ubuntu/Debian | `sudo -u postgres psql -c ...` |
| Windows | `psql -h localhost -U postgres -c ...` |

Exemplo para macOS (troque o prefixo conforme a tabela):

```bash
psql -d postgres -c "CREATE USER comunicador WITH PASSWORD '<SENHA_DB>';"
psql -d postgres -c "CREATE DATABASE comunicador OWNER comunicador;"
```

### 5.3 Criar o esquema

```bash
psql -h localhost -U comunicador -d comunicador -f sql/01_schema.sql
psql -h localhost -U comunicador -d comunicador -c '\dt'      # deve listar a tabela "contas"
```

Para não digitar a senha a cada comando: `export PGPASSWORD='<SENHA_DB>'` (bash/zsh) ou `$env:PGPASSWORD='<SENHA_DB>'`
(PowerShell).

As contas são criadas pela tela de cadastro de qualquer Cliente.

## 6. Máquina A: chaves e configuração do Middleware

### 6.1 Gerar as chaves do servidor

```bash
python3 scripts/gerar_chave.py --par > config/chaves.properties
chmod 600 config/chaves.properties
```

O arquivo contém `chave.transito` e `chave.repositorio` (64 caracteres hexadecimais cada). O Middleware recusa iniciar se
forem iguais ou malformadas. Nunca versione esse arquivo.

### 6.2 Criar o `servidor.properties`

```bash
cp config/servidor.properties.example config/servidor.properties
```

Edite `config/servidor.properties` e ajuste **dois pontos** em relação ao exemplo:

```properties
db.senha=<SENHA_DB>
ws.origens_permitidas=http://localhost:8080,http://<IP_SERVIDOR>:8080
```

- `db.senha`: a senha escolhida na seção 5.2. Em vez de gravá-la no arquivo, pode-se usar a variável de ambiente
  `DB_SENHA` ao iniciar.
- `ws.origens_permitidas`: o handshake WebSocket confere o cabeçalho `Origin` do navegador. Se a página for aberta em outra
  máquina por `http://<IP_SERVIDOR>:8080`, essa origem **precisa** constar aqui, com o mesmo esquema, endereço e porta que
  aparecem na barra do navegador. Se for usado um nome de host (por exemplo `http://servidor.local:8080`), inclua-o também.
  Separe várias origens por vírgula.

As demais propriedades podem ficar nos padrões:

| Propriedade | Padrão | Significado |
|---|---|---|
| `db.url` | `jdbc:postgresql://localhost:5432/comunicador` | URL JDBC do banco |
| `db.usuario` | `comunicador` | Usuário do banco |
| `porta.tcp` / `porta.ws` / `porta.http` | `9000` / `9001` / `8080` | Portas dos listeners |
| `web.raiz` / `recursos.raiz` / `dados.dir` | `cliente-web` / `recursos` / `dados` | Pastas (relativas à raiz do repositório) |
| `retencao.logs.dias` / `retencao.envios.meses` | `90` / `12` | Retenção dos logs |
| `limpeza.intervalo.minutos` | `60` | Intervalo da Limpeza |

Se mudar `porta.*`, ajuste também o firewall e os comandos dos Clientes. A porta do WebSocket do Cliente_Web é fixa em
**9001** no código do navegador (`PORTA_WEBSOCKET`). Portanto, mantenha `porta.ws=9001` se for usar o Cliente_Web.

## 7. Máquina A: compilar e iniciar o Middleware

### 7.1 Compilar

```bash
mvn -q -DskipTests package                # compila comum, middleware e cliente-desktop
```

Só o Middleware (e o módulo `comum`, de que ele depende):

```bash
mvn -q -pl middleware -am -DskipTests package
```

### 7.2 Iniciar

Em qualquer sistema:

```bash
java -jar middleware/target/middleware-1.0.0-SNAPSHOT.jar config/servidor.properties config/chaves.properties
```

Para informar a senha do banco só na sessão (em vez de `db.senha` no arquivo), defina `DB_SENHA` antes:

```bash
DB_SENHA='<SENHA_DB>' java -jar middleware/target/middleware-1.0.0-SNAPSHOT.jar config/servidor.properties config/chaves.properties
```

No PowerShell: `$env:DB_SENHA='<SENHA_DB>'` e, em seguida, o mesmo `java -jar`.

Execute **a partir da raiz do repositório**, para que `cliente-web/`, `recursos/` e `dados/` (caminhos relativos) sejam
encontrados. O jar depende de `middleware/target/lib/`, então não o mova sozinho.

### 7.3 Confirmar que subiu

O terminal deve imprimir:

```
Middleware iniciado: TCP 9000, WebSocket 9001, HTTP 8080, dados em ...
```

Códigos de saída em caso de falha: **2** para configuração inválida (chaves iguais ou malformadas, propriedade inválida) e
**1** para porta ocupada. O terminal fica ocupado pelo Middleware, então abra outro terminal na máquina A para os próximos passos.

Teste local:

```bash
curl -I http://localhost:8080          # deve responder HTTP 200
```

## 8. Entregar a Chave_Transito aos Clientes

Os Clientes só conseguem entrar se tiverem **exatamente** a `chave.transito` do servidor. Entregue-a por um meio **fora do
socket** (pendrive, gerenciador de senhas, mensagem segura). Não há TLS, então quem tem essa chave lê o tráfego capturado.

Na máquina A, obtenha os 64 caracteres:

```bash
grep '^chave.transito=' config/chaves.properties | cut -d= -f2
```

Nas máquinas B e C, grave a chave em `.comunicador/chave_transito.txt`, **na raiz do projeto** (só os 64 caracteres
hexadecimais). Na máquina C, a "raiz" é a pasta que contém `cliente-python/` e `recursos/`:

```bash
mkdir -p .comunicador
echo '<64 caracteres hex>' > .comunicador/chave_transito.txt
chmod 600 .comunicador/chave_transito.txt
```

No Windows (PowerShell):

```powershell
New-Item -ItemType Directory -Force ".comunicador" | Out-Null
Set-Content -Path ".comunicador\chave_transito.txt" -Value '<64 caracteres hex>'
```

Na máquina D (Web), a chave é informada no navegador (seção 11). Sem o arquivo, os Clientes Desktop e Python abrem, mas
mostram uma orientação em vez de permitir o login.

## 9. Máquina B: Cliente_Desktop

### 9.1 Preparar

Tenha o repositório inteiro (seção 3) e a Chave_Transito gravada (seção 8).

### 9.2 Compilar e executar

Linux/macOS, na raiz do repositório. O primeiro comando compila e copia as dependências para `cliente-desktop/target/lib`:

```bash
mvn -q -pl cliente-desktop -am -DskipTests package \
  org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies \
  -DoutputDirectory=target/lib -DincludeScope=runtime
java -cp "cliente-desktop/target/cliente-desktop-1.0.0-SNAPSHOT.jar:cliente-desktop/target/lib/*" \
  br.cefet.sd.cliente.desktop.ClienteDesktop --host=<IP_SERVIDOR> --porta=9000
```

Em vez de `--host` e `--porta`, o Cliente aceita as variáveis de ambiente `COMUNICADOR_HOST` e `COMUNICADOR_PORTA` (a linha
de comando tem precedência).

No Windows, o separador do classpath muda (veja o exemplo) e os caminhos usam `\`:

```powershell
java -cp "cliente-desktop\target\cliente-desktop-1.0.0-SNAPSHOT.jar;cliente-desktop\target\lib\*" br.cefet.sd.cliente.desktop.ClienteDesktop --host=<IP_SERVIDOR> --porta=9000
```

Execute a partir da raiz do repositório, para que `recursos/` seja localizada, ou defina `COMUNICADOR_RECURSOS` com o
caminho dela.

## 10. Máquina C: Cliente_Terceiro (Python)

### 10.1 Preparar

Copie `cliente-python/` e `recursos/` para a mesma pasta pai (ou use `--recursos`, abaixo) e grave a Chave_Transito
(seção 8). Nenhuma instalação de pacotes é necessária.

### 10.2 Executar

```bash
cd cliente-python
python3 -m comunicador --host <IP_SERVIDOR> --porta 9000
```

Opções (veja `python3 -m comunicador --help`):

| Opção | Efeito |
|---|---|
| `--host` | Endereço do Middleware (padrão `localhost`). Este Cliente **não** lê `COMUNICADOR_HOST` |
| `--porta` | Porta TCP (padrão `9000`) |
| `--recursos` | Pasta `recursos/`, se não estiver ao lado de `cliente-python/` (também aceita a variável `COMUNICADOR_RECURSOS`) |
| `--modo-texto` | Sem imagens: avatar, cor, emoji e figurinha aparecem em texto |

No Windows, use `python` ou `py` no lugar de `python3`. Dados locais ficam em `.comunicador/python/`, na raiz do projeto.

## 11. Máquina D: Cliente_Web

Não há instalação nem compilação: o próprio Middleware serve a pasta `cliente-web/`.

1. Confirme que `ws.origens_permitidas` (seção 6.2) inclui `http://<IP_SERVIDOR>:8080` e que o Middleware foi reiniciado
   depois da alteração.
2. No navegador da máquina D, abra a página **pelo IP do servidor**, e não por `localhost`:
   ```
   http://<IP_SERVIDOR>:8080
   ```
   Para já preencher a chave (o trecho após `#` não é enviado ao servidor e some da URL):
   ```
   http://<IP_SERVIDOR>:8080/#chave=<64 caracteres hex da chave.transito>
   ```
3. Na tela de login, informe login, senha e, se o campo "Chave de transito" estiver vazio, cole a `chave.transito`. Ela fica
   salva no `localStorage` do navegador.

O navegador conecta ao WebSocket em `ws://<host da página>:9001`. Por isso a porta 9001 também precisa estar liberada e
alcançável a partir da máquina D.

## 12. Problemas comuns

| Sintoma | Causa provável | O que fazer |
|---|---|---|
| Middleware sai com código **2** | `chave.transito` e `chave.repositorio` iguais ou malformadas, ou propriedade inválida | Regere com `python3 scripts/gerar_chave.py --par` e leia as linhas impressas após "Partida recusada" |
| Middleware sai com código **1** | Porta ocupada | Encerre o processo que usa a porta ou mude `porta.*` (e o firewall) |
| `nc -vz <IP_SERVIDOR> 9000` falha | Firewall, IP errado, máquinas em redes diferentes | Refaça a seção 4, confirme o IP e confirme que o Middleware está em execução |
| Desktop/Python: "Chave de transito nao encontrada" | Falta `.comunicador/chave_transito.txt` na raiz do projeto, na máquina do Cliente | Seção 8 |
| Login recusado com senha correta | Chave_Transito do Cliente difere da do servidor | Copie de novo os 64 caracteres, sem espaços |
| Cadastro/login falham no servidor, portas abertas | PostgreSQL parado, `db.senha`/`db.url` errados ou esquema não aplicado | Rode `pg_isready`, confira `db.*` e aplique `sql/01_schema.sql`. O Middleware sobe mesmo sem o banco, mas cadastro e login dependem dele |
| Web abre a página, mas não conecta | Origem fora de `ws.origens_permitidas` ou porta 9001 bloqueada | Inclua `http://<IP_SERVIDOR>:8080` (igual ao da barra do navegador), reinicie o Middleware, libere a 9001 |
| Web funciona em `localhost` no servidor, mas não em outra máquina | Página aberta por `localhost` na máquina D, ou origem não cadastrada | Abra por `http://<IP_SERVIDOR>:8080` |
| Desktop/Python sem imagens ou erro de recursos | Pasta `recursos/` não encontrada | Execute da raiz do repositório ou defina `COMUNICADOR_RECURSOS` / `--recursos` |
| `mvn: command not found` | Maven fora do `PATH` | `export MVN=/caminho/para/mvn` |
| Erro de compilação por versão do Java | JDK diferente de 21 | Instale o JDK 21 e confira `java -version` e `mvn -version` |
| Tkinter ausente | Python sem Tk | Debian/Ubuntu: `sudo apt install python3-tk` |

