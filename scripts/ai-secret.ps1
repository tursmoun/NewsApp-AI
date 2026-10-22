 # Resolve the module from this PowerShell installation, not an inherited PSModulePath.
Import-Module -Name (Join-Path $PSHOME 'Modules\Microsoft.PowerShell.Security\Microsoft.PowerShell.Security.psd1') -ErrorAction Stop

function Save-NewsAppAiSecret {
    param([Parameter(Mandatory=$true)][string]$LocalDirectory, [Parameter(Mandatory=$true)][Security.SecureString]$Secret)
    New-Item -ItemType Directory -Force -Path $LocalDirectory | Out-Null
    $secretPath=Join-Path $LocalDirectory 'ai-secret.dpapi'
    $temporaryPath=$secretPath+'.partial'
    $encrypted=ConvertFrom-SecureString -SecureString $Secret
    [IO.File]::WriteAllText($temporaryPath,$encrypted,(New-Object Text.UTF8Encoding $false))
    Move-Item -LiteralPath $temporaryPath -Destination $secretPath -Force
}

function Import-NewsAppAiSecret {
    param([Parameter(Mandatory=$true)][string]$LocalDirectory)
    $secretPath=Join-Path $LocalDirectory 'ai-secret.dpapi'
    $settingsPath=Join-Path $LocalDirectory 'ai-config.json'
    # Upgrade the old configuration without displaying the key or making plaintext backups.
    if (Test-Path -LiteralPath $settingsPath) {
        $settings=Get-Content -LiteralPath $settingsPath -Raw | ConvertFrom-Json
        if ($settings.PSObject.Properties.Name -contains 'key') {
            if ($settings.key -and !(Test-Path -LiteralPath $secretPath)) {
                $legacySecret=ConvertTo-SecureString -String $settings.key -AsPlainText -Force
                try { Save-NewsAppAiSecret -LocalDirectory $LocalDirectory -Secret $legacySecret }
                finally { $legacySecret.Dispose() }
            }
            $settings.PSObject.Properties.Remove('key')
            $temporarySettings=$settingsPath+'.partial'
            [IO.File]::WriteAllText($temporarySettings,($settings | ConvertTo-Json),(New-Object Text.UTF8Encoding $false))
            Move-Item -LiteralPath $temporarySettings -Destination $settingsPath -Force
        }
    }
    if (Test-Path -LiteralPath $secretPath) {
        try {
            $secure=Get-Content -LiteralPath $secretPath -Raw | ConvertTo-SecureString
            $pointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
            try { $env:NEWSAPP_AI_KEY=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
            finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer); $secure.Dispose() }
        } catch { throw 'Cannot unlock the Claude key. Run setup-ai-key.cmd under your Windows account.' }
    }
}
