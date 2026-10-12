<#
.SYNOPSIS
Configura e inicia um Cliente (maquinas B e C) seguindo o README, secoes 8 a 10, no Windows.

.DESCRIPTION
Verifica os pre-requisitos do Cliente escolhido, grava a Chave_Transito em
.comunicador\chave_transito.txt, testa a conectividade com o Middleware, compila (Desktop) e
SEMPRE inicia o Cliente.

  desktop   Cliente_Desktop (Swing; precisa de JDK 21 e Maven)
  python    Cliente_Terceiro (Tkinter; precisa de Python 3.11+ com Tkinter)

O Cliente_Web nao precisa de script: abra http://<IP_SERVIDOR>:8080 no navegador (README, secao 11).

Variaveis de ambiente: MVN (comando do Maven, padrao mvn), COMUNICADOR_HOST, COMUNICADOR_PORTA,
CHAVE_TRANSITO (usadas quando os parametros correspondentes nao sao informados).

.PARAMETER Cliente
desktop ou python.

.PARAMETER Servidor
Endereco do Middleware (padrao: COMUNICADOR_HOST; senao pergunta).

.PARAMETER Porta
Porta TCP do Middleware (padrao: COMUNICADOR_PORTA ou 9000).

.PARAMETER Chave
chave.transito do servidor, 64 hexadecimais (padrao: CHAVE_TRANSITO; senao pergunta. Em branco,
mantem a de .comunicador\chave_transito.txt se valida).

.EXAMPLE
powershell -ExecutionPolicy Bypass -File scripts\configurar_cliente.ps1 desktop -Servidor 192.168.0.10
.EXAMPLE
powershell -ExecutionPolicy Bypass -File scripts\configurar_cliente.ps1 python -Servidor 192.168.0.10 -Chave <64 hex>
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet("desktop", "python")]
    [string]$Cliente,
    [string]$Servidor = $env:COMUNICADOR_HOST,
    [int]$Porta = $(if ($env:COMUNICADOR_PORTA) { [int]$env:COMUNICADOR_PORTA } else { 9000 }),
    [string]$Chave = $env:CHAVE_TRANSITO
)

$ErrorActionPreference = "Stop"

$Raiz = Split-Path -Parent $PSScriptRoot
Set-Location $Raiz
[Environment]::CurrentDirectory = $Raiz   # APIs .NET resolvem caminhos relativos a partir daqui

$Mvn        = if ($env:MVN) { $env:MVN } else { "mvn" }
$ArqChave   = Join-Path $Raiz ".comunicador\chave_transito.txt"
$Utf8SemBom = New-Object System.Text.UTF8Encoding($false)

function Passo($Texto) { Write-Host ""; Write-Host "==> $Texto" -ForegroundColor Cyan }
function Info($Texto)  { Write-Host "    $Texto" }
function Falha($Texto) { Write-Host "ERRO: $Texto" -ForegroundColor Red; exit 1 }

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

# ---------------------------------------------------------------- Parametros
Passo "Parametros de conexao"

if (-not $Servidor) { $Servidor = Read-Host -Prompt "    IP ou nome do servidor (Middleware)" }
if (-not $Servidor) { Falha "Host vazio." }
if ($Porta -lt 1 -or $Porta -gt 65535) { Falha "Porta invalida: $Porta" }
Info "Middleware: ${Servidor}:$Porta"

if (-not $Chave) {
    $Chave = Read-Host -Prompt "    chave.transito (64 hex; Enter mantem a atual)"
    if (-not $Chave -and (Test-Path $ArqChave)) { $Chave = [IO.File]::ReadAllText($ArqChave) }
}
$Chave = ($Chave -replace "\s", "")
if ($Chave -notmatch '^[0-9a-fA-F]{64}$') { Falha "A chave.transito deve ter exatamente 64 caracteres hexadecimais." }

# ---------------------------------------------------------------- Pre-requisitos
Passo "Verificando pre-requisitos ($Cliente)"

if ($Cliente -eq "desktop") {
    if (-not (Get-Command java -ErrorAction SilentlyContinue)) { Falha "java nao encontrado. Instale o JDK 21." }
    if ((Capturar "java" @("-version")) -notmatch 'version "(\d+)') { Falha "Nao foi possivel ler a versao do Java." }
    $versaoJava = [int]$Matches[1]
    if ($versaoJava -lt 21) { Falha "JDK 21 ou superior necessario (encontrado: $versaoJava)." }
    Info "java $versaoJava"
    if (-not (Get-Command $Mvn -ErrorAction SilentlyContinue)) { Falha "Maven nao encontrado. Instale-o ou defina `$env:MVN." }
    Info ((Capturar $Mvn @("-version")) -split "`n")[0]
} else {
    $Python = $null
    foreach ($candidato in @("python", "py")) {
        if (Get-Command $candidato -ErrorAction SilentlyContinue) { $Python = $candidato; break }
    }
    if (-not $Python) { Falha "Python 3.11+ nao encontrado." }
    Capturar $Python @("-c", "import sys; sys.exit(0 if sys.version_info >= (3, 11) else 1)") | Out-Null
    if ($LASTEXITCODE -ne 0) { Falha "Python 3.11 ou superior necessario (encontrado: $(Capturar $Python @('--version')))." }
    Capturar $Python @("-c", "import tkinter") | Out-Null
    if ($LASTEXITCODE -ne 0) { Falha "Tkinter ausente. Reinstale o Python marcando 'tcl/tk and IDLE'." }
    Info "$(Capturar $Python @('--version')) com Tkinter"
}

if (-not (Test-Path (Join-Path $Raiz "recursos"))) { Falha "Pasta recursos\ nao encontrada na raiz do projeto ($Raiz)." }
Info "recursos\ encontrada"

# ---------------------------------------------------------------- 8. Chave_Transito
Passo "Chave_Transito"

New-Item -ItemType Directory -Force (Join-Path $Raiz ".comunicador") | Out-Null
[IO.File]::WriteAllText($ArqChave, "$Chave`n", $Utf8SemBom)
Info "gravada em .comunicador\chave_transito.txt"

# ---------------------------------------------------------------- 4.3 Conectividade
Passo "Conectividade com ${Servidor}:$Porta"

$alcancavel = $false
$tcp = New-Object System.Net.Sockets.TcpClient
try {
    $espera = $tcp.BeginConnect($Servidor, $Porta, $null, $null)
    if ($espera.AsyncWaitHandle.WaitOne(3000, $false)) {
        $tcp.EndConnect($espera)
        $alcancavel = $true
    }
} catch {
    $alcancavel = $false
} finally {
    $tcp.Close()
}
if ($alcancavel) {
    Info "porta alcancavel"
} else {
    Info "AVISO: ${Servidor}:$Porta inacessivel agora. Confira se o Middleware esta no ar, o IP e o firewall (README, secao 12)."
    Info "O Cliente sera iniciado mesmo assim e tentara reconectar."
}

# ---------------------------------------------------------------- 9 / 10. Iniciar
if ($Cliente -eq "desktop") {
    Passo "Compilando o Cliente_Desktop"
    Executar $Mvn @("-q", "-pl", "cliente-desktop", "-am", "-DskipTests", "package",
        "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies",
        "-DoutputDirectory=target/lib", "-DincludeScope=runtime")

    $jar = Get-ChildItem "cliente-desktop\target\cliente-desktop-*.jar" |
        Where-Object { $_.Name -notlike "*-sources*" } | Select-Object -First 1
    if (-not $jar) { Falha "Jar do Cliente_Desktop nao encontrado em cliente-desktop\target." }

    Passo "Iniciando o Cliente_Desktop"
    & java -cp "$($jar.FullName);cliente-desktop\target\lib\*" br.cefet.sd.cliente.desktop.ClienteDesktop "--host=$Servidor" "--porta=$Porta"
    exit $LASTEXITCODE
} else {
    Passo "Iniciando o Cliente_Python"
    Set-Location (Join-Path $Raiz "cliente-python")
    & $Python -m comunicador --host $Servidor --porta $Porta
    exit $LASTEXITCODE
}
