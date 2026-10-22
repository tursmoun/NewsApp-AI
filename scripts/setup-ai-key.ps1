$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'ai-secret.ps1')
$keyDirectory=Join-Path ([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))) '.local'
Write-Host 'Claude / MWAPI: enter the API key. Input is hidden.'
Write-Host 'The key will be encrypted for this Windows account on this computer.'
$secureKey=Read-Host 'API key' -AsSecureString
try {
    if ($secureKey.Length -eq 0) { throw 'Empty key. Nothing was changed.' }
    $keyPointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureKey)
    try {
        $keyInput=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($keyPointer).Trim()
        $installerKey=[regex]::Match($keyInput,'https://api\.mwapi\.dev/install\.ps1\?key=(sk-[A-Za-z0-9_-]+)')
        if ($installerKey.Success) { $keyInput=$installerKey.Groups[1].Value }
        if ($keyInput -notmatch '^sk-[A-Za-z0-9_-]+$') { throw 'Expected an MWAPI key starting with sk-. Nothing was changed.' }
        $secureKey.Dispose()
        $secureKey=ConvertTo-SecureString -String $keyInput -AsPlainText -Force
    } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($keyPointer); $keyInput=$null; $installerKey=$null }
    Save-NewsAppAiSecret -LocalDirectory $keyDirectory -Secret $secureKey
    Import-NewsAppAiSecret -LocalDirectory $keyDirectory
    Remove-Item Env:NEWSAPP_AI_KEY -ErrorAction SilentlyContinue
    Write-Host 'Saved. Restart NewsApp to use the key.'
} finally { $secureKey.Dispose() }
