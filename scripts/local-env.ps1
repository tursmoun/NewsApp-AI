$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$localRoot = Join-Path $projectRoot '.local'
New-Item -ItemType Directory -Force -Path $localRoot,(Join-Path $localRoot 'logs') | Out-Null
$configFile = Join-Path $localRoot 'config.json'
function New-Secret {
    $bytes = New-Object byte[] 16
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return [BitConverter]::ToString($bytes).Replace('-','').ToLowerInvariant()
}
if (!(Test-Path -LiteralPath $configFile)) {
    $config = @{ dbPassword=(New-Secret); adminPassword=('Uu!'+(New-Secret)); ownerPassword=('It!'+(New-Secret)) }
    [IO.File]::WriteAllText($configFile,($config | ConvertTo-Json),(New-Object Text.UTF8Encoding $false))
    $accountText = "LOCAL ACCOUNTS - do not publish this file`r`n`r`nUniversity/platform administrator`r`nUsername: uunit.admin`r`nPassword: $($config.adminPassword)`r`n`r`nOrdinary community creator`r`nUsername: club.owner`r`nPassword: $($config.ownerPassword)`r`n"
    [IO.File]::WriteAllText((Join-Path $localRoot 'accounts.txt'),$accountText,(New-Object Text.UTF8Encoding $false))
}
$config = Get-Content -LiteralPath $configFile -Raw | ConvertFrom-Json
. (Join-Path $PSScriptRoot 'ai-secret.ps1')
Import-NewsAppAiSecret -LocalDirectory $localRoot
$javaCandidates = @()
if ($env:JAVA_HOME) { $javaCandidates += $env:JAVA_HOME }
$javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
if ($javaCommand) { $javaCandidates += [IO.Path]::GetDirectoryName([IO.Path]::GetDirectoryName($javaCommand.Source)) }
if (Test-Path -LiteralPath (Join-Path $env:ProgramFiles 'JetBrains')) { $javaCandidates += Get-ChildItem -LiteralPath (Join-Path $env:ProgramFiles 'JetBrains') -Directory | ForEach-Object { Join-Path $_.FullName 'jbr' } }
if (Test-Path -LiteralPath (Join-Path $env:USERPROFILE '.jdks')) { $javaCandidates += Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE '.jdks') -Directory | ForEach-Object { $_.FullName } }
$selectedJava = $null
foreach ($candidate in $javaCandidates) {
    $release = Join-Path $candidate 'release'
    if ((Test-Path -LiteralPath $release) -and ((Get-Content -LiteralPath $release -Raw) -match 'JAVA_VERSION="25\.')) { $selectedJava=$candidate; break }
}
if (!$selectedJava) { throw 'Java 25 JDK was not found. Install JDK 25 or set JAVA_HOME to its directory.' }
$env:JAVA_HOME=$selectedJava
$env:NEWSAPP_DB_PASSWORD=$config.dbPassword
$env:NEWSAPP_DB_USER='newsapp'
$env:NEWSAPP_DB_URL='jdbc:postgresql://127.0.0.1:55432/newsapp'
$env:NEWSAPP_ADMIN_PASSWORD=$config.adminPassword
$env:NEWSAPP_OWNER_PASSWORD=$config.ownerPassword
$env:NEWSAPP_LOCAL_DIR=$localRoot
$env:NEWSAPP_FFMPEG=Join-Path $localRoot 'tools\ffmpeg\bin\ffmpeg.exe'
$env:NEWSAPP_FFPROBE=Join-Path $localRoot 'tools\ffmpeg\bin\ffprobe.exe'
$env:PGPASSWORD=$config.dbPassword
$pgBin=Join-Path $localRoot 'tools\postgres\bin'
$dbDirectory=Join-Path $localRoot 'postgres-data'
$maven=Join-Path $localRoot 'tools\apache-maven-3.9.11\bin\mvn.cmd'
