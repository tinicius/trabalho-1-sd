<#
.SYNOPSIS
Configura e inicia o Middleware (maquina A) seguindo o README, secoes 4 a 8, no Windows.

.DESCRIPTION
Verifica pre-requisitos (JDK 21, Maven, Python, PostgreSQL 16), cria usuario/banco/esquema,
gera SEMPRE novas chaves, cria config\servidor.properties, libera o firewall, compila e SEMPRE
inicia o Middleware. Usuario, banco e esquema nao sao recriados se ja existirem. Como as chaves
sao regeradas a cada execucao, Clientes que usavam a chave anterior precisam receber a nova.

Pre-requisito manual: instalar o PostgreSQL 16 (porta 5432), JDK 21, Maven e Python 3.
Para liberar o firewall, execute o PowerShell como administrador (sem isso o passo e apenas avisado).

Variaveis de ambiente:
  DB_SENHA         equivale a -SenhaDb (o parametro tem precedencia)
  POSTGRES_SENHA   senha do superusuario "postgres" (senao pergunta)
  MVN              comando do Maven (padrao: mvn)

.PARAMETER SenhaDb
Senha do usuario "comunicador" no PostgreSQL (padrao: DB_SENHA; senao a do servidor.properties;
senao pergunta).

.PARAMETER Ip
IP desta maquina usado em ws.origens_permitidas (padrao: detectado).

.EXAMPLE
powershell -ExecutionPolicy Bypass -File scripts\configurar_middleware.ps1
.EXAMPLE
powershell -ExecutionPolicy Bypass -File scripts\configurar_middleware.ps1 -Ip 192.168.0.10
#>
[CmdletBinding()]
param(
    [string]$SenhaDb = $env:DB_SENHA,
    [string]$Ip = ""
)

$ErrorActionPreference = "Stop"

$Raiz = Split-Path -Parent $PSScriptRoot
Set-Location $Raiz
[Environment]::CurrentDirectory = $Raiz   # APIs .NET resolvem caminhos relativos a partir daqui

$DbUsuario   = "comunicador"
$DbNome      = "comunicador"
$ArqServidor = Join-Path $Raiz "config\servidor.properties"
$ArqChaves   = Join-Path $Raiz "config\chaves.properties"
$Mvn         = if ($env:MVN) { $env:MVN } else { "mvn" }
$Utf8SemBom  = New-Object System.Text.UTF8Encoding($false)

function Passo($Texto)  { Write-Host ""; Write-Host "==> $Texto" -ForegroundColor Cyan }
function Info($Texto)   { Write-Host "    $Texto" }
function Falha($Texto)  { Write-Host "ERRO: $Texto" -ForegroundColor Red; exit 1 }

# Executa um programa nativo e falha se o codigo de saida for diferente de 0.
function Executar([string]$Exe, [string[]]$Argumentos) {
    & $Exe @Argumentos
    if ($LASTEXITCODE -ne 0) { throw "Falha ao executar: $Exe $($Argumentos -join ' ') (codigo $LASTEXITCODE)" }
}

# Executa um programa nativo e devolve a saida (stdout + stderr) como texto.
function Capturar([string]$Exe, [string[]]$Argumentos) {
    $anterior = $ErrorActionPreference
    $ErrorActionPreference = "Continue"   # PS 5.1 trata stderr de programas nativos como erro
    try { $saida = & $Exe @Argumentos 2>&1 | ForEach-Object { "$_" } }
    finally { $ErrorActionPreference = $anterior }
    return ($saida -join "`n").Trim()
}

function Get-Prop([string]$Arquivo, [string]$Chave) {
    if (-not (Test-Path $Arquivo)) { return "" }
    foreach ($linha in [IO.File]::ReadAllLines($Arquivo)) {
        if ($linha.StartsWith("$Chave=")) { return $linha.Substring($Chave.Length + 1) }
    }
    return ""
}

# Substitui (ou acrescenta) CHAVE=VALOR em um .properties, em UTF-8 sem BOM.
function Set-Prop([string]$Arquivo, [string]$Chave, [string]$Valor) {
    $linhas = New-Object System.Collections.Generic.List[string]
    $feito = $false
    foreach ($linha in [IO.File]::ReadAllLines($Arquivo)) {
        if (-not $feito -and $linha.StartsWith("$Chave=")) { $linhas.Add("$Chave=$Valor"); $feito = $true }
        else { $linhas.Add($linha) }
    }
    if (-not $feito) { $linhas.Add("$Chave=$Valor") }
    [IO.File]::WriteAllText($Arquivo, (($linhas -join "`n") + "`n"), $Utf8SemBom)
}

function Ler-Senha([string]$Pergunta) {
    $segura = Read-Host -Prompt $Pergunta -AsSecureString
    $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($segura)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
}

# ---------------------------------------------------------------- 2. Pre-requisitos
Passo "Verificando pre-requisitos (JDK 21, Maven, Python 3)"

if (-not (Get-Command java -ErrorAction SilentlyContinue)) { Falha "java nao encontrado. Instale o JDK 21." }
$saidaJava = Capturar "java" @("-version")
if ($saidaJava -notmatch 'version "(\d+)') { Falha "Nao foi possivel ler a versao do Java." }
$versaoJava = [int]$Matches[1]
if ($versaoJava -lt 21) { Falha "JDK 21 ou superior necessario (encontrado: $versaoJava)." }
Info "java $versaoJava"

if (-not (Get-Command $Mvn -ErrorAction SilentlyContinue)) { Falha "Maven nao encontrado. Instale-o ou defina `$env:MVN." }
Info ((Capturar $Mvn @("-version")) -split "`n")[0]

$Python = $null
foreach ($candidato in @("python", "py")) {
    if (Get-Command $candidato -ErrorAction SilentlyContinue) { $Python = $candidato; break }
}
if (-not $Python) { Falha "Python 3 nao encontrado (necessario para gerar as chaves)." }
Info (Capturar $Python @("--version"))

# ---------------------------------------------------------------- 5.1 PostgreSQL
Passo "PostgreSQL 16"

if (-not (Get-Command psql -ErrorAction SilentlyContinue)) {
    $binPg = Get-ChildItem "C:\Program Files\PostgreSQL\*\bin\psql.exe" -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending | Select-Object -First 1
    if ($binPg) { $env:Path = "$($binPg.DirectoryName);$env:Path" }
}
if (-not (Get-Command psql -ErrorAction SilentlyContinue)) {
    Falha "psql nao encontrado. Instale o PostgreSQL 16 (https://www.postgresql.org/download/windows/) e adicione C:\Program Files\PostgreSQL\16\bin ao PATH."
}

$servicoPg = Get-Service -Name "postgresql*" -ErrorAction SilentlyContinue | Select-Object -First 1
if ($servicoPg -and $servicoPg.Status -ne "Running") {
    Info "PostgreSQL parado; iniciando o servico $($servicoPg.Name)..."
    try { Start-Service $servicoPg.Name } catch { Info "AVISO: nao foi possivel iniciar o servico (precisa de administrador)." }
}
$pronto = $false
for ($i = 0; $i -lt 30; $i++) {
    if ((Capturar "pg_isready" @("-h", "localhost")) -match "accepting connections") { $pronto = $true; break }
    Start-Sleep -Seconds 1
}
if (-not $pronto) { Falha "PostgreSQL nao respondeu em localhost:5432." }
Info "accepting connections"

# ---------------------------------------------------------------- 5.2 Usuario e banco
Passo "Usuario e banco de dados"

if (-not (Test-Path $ArqServidor)) {
    $exemplo = Join-Path $Raiz "config\servidor.properties.example"
    if (-not (Test-Path $exemplo)) { Falha "config\servidor.properties.example nao encontrado." }
    Copy-Item $exemplo $ArqServidor
    Info "criado config\servidor.properties a partir do exemplo"
}

if (-not $SenhaDb) {
    $atual = Get-Prop $ArqServidor "db.senha"
    if ($atual -and $atual -ne "<definir>") {
        $SenhaDb = $atual
        Info "usando db.senha ja presente em config\servidor.properties"
    }
}
if (-not $SenhaDb) { $SenhaDb = Ler-Senha "    Escolha a senha do usuario '$DbUsuario' no banco" }
if (-not $SenhaDb) { Falha "Senha vazia." }
if ($SenhaDb.Contains("\")) { Falha "A senha nao pode conter '\' (e caractere de escape em .properties)." }
$SenhaSql = $SenhaDb.Replace("'", "''")   # escapa aspas simples para o literal SQL

$SenhaAdmin = $env:POSTGRES_SENHA
if (-not $SenhaAdmin) { $SenhaAdmin = Ler-Senha "    Senha do superusuario 'postgres' (definida na instalacao)" }

$env:PGPASSWORD = $SenhaAdmin
try {
    function Admin([string]$Sql) {
        Executar "psql" @("-v", "ON_ERROR_STOP=1", "-q", "-h", "localhost", "-U", "postgres", "-d", "postgres", "-c", $Sql)
    }
    function Admin-Consulta([string]$Sql) {
        $r = Capturar "psql" @("-v", "ON_ERROR_STOP=1", "-tA", "-h", "localhost", "-U", "postgres", "-d", "postgres", "-c", $Sql)
        if ($LASTEXITCODE -ne 0) { throw "Falha na consulta ao PostgreSQL: $r" }
        return $r
    }

    if ((Admin-Consulta "SELECT 1 FROM pg_roles WHERE rolname='$DbUsuario'") -eq "1") {
        Admin "ALTER USER $DbUsuario WITH PASSWORD '$SenhaSql';"
        Info "usuario '$DbUsuario' ja existia; senha sincronizada"
    } else {
        Admin "CREATE USER $DbUsuario WITH PASSWORD '$SenhaSql';"
        Info "usuario '$DbUsuario' criado"
    }

    if ((Admin-Consulta "SELECT 1 FROM pg_database WHERE datname='$DbNome'") -eq "1") {
        Info "banco '$DbNome' ja existe"
    } else {
        Admin "CREATE DATABASE $DbNome OWNER $DbUsuario;"
        Info "banco '$DbNome' criado"
    }
} finally {
    Remove-Item Env:\PGPASSWORD -ErrorAction SilentlyContinue
}

# ---------------------------------------------------------------- 5.3 Esquema
Passo "Esquema (sql\01_schema.sql)"

$env:PGPASSWORD = $SenhaDb
try {
    $existe = Capturar "psql" @("-tA", "-h", "localhost", "-U", $DbUsuario, "-d", $DbNome, "-c", "SELECT to_regclass('public.contas')")
    if ($existe -and $existe -notmatch "ERROR|FATAL") {
        Info "tabela 'contas' ja existe; esquema mantido"
    } else {
        Executar "psql" @("-v", "ON_ERROR_STOP=1", "-q", "-h", "localhost", "-U", $DbUsuario, "-d", $DbNome, "-f", "sql\01_schema.sql")
        Info "esquema aplicado"
    }
    Executar "psql" @("-h", "localhost", "-U", $DbUsuario, "-d", $DbNome, "-c", '\dt')
} finally {
    Remove-Item Env:\PGPASSWORD -ErrorAction SilentlyContinue
}

# ---------------------------------------------------------------- 6.1 Chaves
Passo "Chaves do servidor (config\chaves.properties)"

$par = Capturar $Python @("scripts\gerar_chave.py", "--par")
if ($par -notmatch "chave\.transito=") { Falha "Falha ao gerar chaves: $par" }
[IO.File]::WriteAllText($ArqChaves, ($par -replace "`r", "") + "`n", $Utf8SemBom)
Info "novas chaves geradas"
Info "AVISO: Clientes que tinham a chave anterior precisam receber a nova (secao 8 do README)"

# ---------------------------------------------------------------- 4.1 IP e 6.2 servidor.properties
Passo "IP da maquina e config\servidor.properties"

if (-not $Ip) {
    try {
        $rota = Get-NetRoute -DestinationPrefix "0.0.0.0/0" -ErrorAction Stop | Sort-Object RouteMetric | Select-Object -First 1
        $Ip = (Get-NetIPAddress -AddressFamily IPv4 -InterfaceIndex $rota.InterfaceIndex -ErrorAction Stop |
            Where-Object { $_.IPAddress -notlike "169.254.*" } | Select-Object -First 1).IPAddress
    } catch {
        $Ip = ""
    }
}

$PortaTcp  = Get-Prop $ArqServidor "porta.tcp";  if (-not $PortaTcp)  { $PortaTcp  = "9000" }
$PortaWs   = Get-Prop $ArqServidor "porta.ws";   if (-not $PortaWs)   { $PortaWs   = "9001" }
$PortaHttp = Get-Prop $ArqServidor "porta.http"; if (-not $PortaHttp) { $PortaHttp = "8080" }

$Origens = "http://localhost:$PortaHttp"
if ($Ip) {
    $Origens = "$Origens,http://${Ip}:$PortaHttp"
    Info "IP detectado/informado: $Ip"
} else {
    Info "AVISO: IP nao detectado; use -Ip ENDERECO para que o Cliente_Web funcione em outras maquinas"
}

Set-Prop $ArqServidor "db.senha" $SenhaDb
Set-Prop $ArqServidor "ws.origens_permitidas" $Origens
Info "db.senha atualizada"
Info "ws.origens_permitidas=$Origens"

# ---------------------------------------------------------------- 4.2 Firewall
Passo "Firewall (portas $PortaTcp, $PortaWs, $PortaHttp)"

$admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
    ).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if ($admin) {
    Get-NetFirewallRule -DisplayName "Comunicador" -ErrorAction SilentlyContinue | Remove-NetFirewallRule
    New-NetFirewallRule -DisplayName "Comunicador" -Direction Inbound -Protocol TCP `
        -LocalPort $PortaTcp, $PortaWs, $PortaHttp -Action Allow | Out-Null
    Info "regra 'Comunicador' criada (a 5432 permanece fechada)"
} else {
    Info "AVISO: sem privilegio de administrador; regra de firewall NAO criada. Como administrador, execute:"
    Info "  New-NetFirewallRule -DisplayName `"Comunicador`" -Direction Inbound -Protocol TCP -LocalPort $PortaTcp,$PortaWs,$PortaHttp -Action Allow"
}

# ---------------------------------------------------------------- 8. Chave_Transito local
Passo "Chave_Transito para Clientes nesta maquina"

$ChaveTransito = Get-Prop $ArqChaves "chave.transito"
New-Item -ItemType Directory -Force (Join-Path $Raiz ".comunicador") | Out-Null
[IO.File]::WriteAllText((Join-Path $Raiz ".comunicador\chave_transito.txt"), "$ChaveTransito`n", $Utf8SemBom)
Info "gravada em .comunicador\chave_transito.txt (Clientes Desktop/Python locais)"
Info "Para outras maquinas, entregue por fora do socket esta chave (64 hex):"
Info "  $ChaveTransito"
$hostWeb = if ($Ip) { $Ip } else { "<IP_SERVIDOR>" }
Info "Cliente_Web: http://${hostWeb}:$PortaHttp/#chave=$ChaveTransito"

# ---------------------------------------------------------------- 7. Compilar e iniciar
Passo "Compilando o Middleware"
Executar $Mvn @("-q", "-pl", "middleware", "-am", "-DskipTests", "package")
Info "compilacao concluida"

$jar = Get-ChildItem "middleware\target\middleware-*.jar" |
    Where-Object { $_.Name -notlike "*-sources*" } | Select-Object -First 1
if (-not $jar) { Falha "Jar do Middleware nao encontrado em middleware\target." }

Passo "Iniciando o Middleware (Ctrl+C para encerrar)"
$env:DB_SENHA = $SenhaDb
& java -jar $jar.FullName "config\servidor.properties" "config\chaves.properties"
exit $LASTEXITCODE
