# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.

<#
.SYNOPSIS
    Puts the signing key into the repository's secrets, once.

.DESCRIPTION
    Run this yourself, in your own terminal. It asks for the keystore and its
    passwords, proves they actually open it, and hands them to GitHub as
    encrypted secrets. Nothing is written to disk, nothing is echoed, and no
    password is ever passed as a command-line argument, where the rest of the
    machine could read it out of the process list.

    Saved as UTF-8 with a byte order mark on purpose: Windows PowerShell 5.1
    reads a .ps1 without one as ANSI, which turns every Russian line in here
    into mojibake and, worse, breaks the quoting around it.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\publish_setup.ps1
#>

$ErrorActionPreference = 'Stop'

function Read-Plain([System.Security.SecureString] $secure) {
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

function Find-Keytool {
    $found = Get-Command keytool -ErrorAction SilentlyContinue
    if ($null -ne $found) { return $found.Source }
    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }
    return $null
}

Write-Host ''
Write-Host 'Подпись релиза Zales' -ForegroundColor Cyan
Write-Host '--------------------'
Write-Host 'Ключ никуда не копируется и нигде не остаётся: он уходит'
Write-Host 'прямо в секреты репозитория и стирается из памяти.'
Write-Host ''

$keytool = Find-Keytool
if ($null -eq $keytool) {
    Write-Host 'Не нашёл keytool. Он лежит в bin рядом с установленной Java.' -ForegroundColor Red
    Write-Host 'Пропишите JAVA_HOME или добавьте его bin в PATH.' -ForegroundColor DarkGray
    exit 1
}

$gh = Get-Command gh -ErrorAction SilentlyContinue
if ($null -eq $gh) {
    Write-Host 'Не нашёл gh (GitHub CLI). Поставьте его и повторите.' -ForegroundColor Red
    exit 1
}

# ── The keystore ───────────────────────────────────────────────────────────
$keystore = Read-Host 'Путь к файлу ключа (zales.jks)'
if ([string]::IsNullOrWhiteSpace($keystore)) {
    Write-Host 'Путь не введён.' -ForegroundColor Red
    exit 1
}
$keystore = $keystore.Trim('"').Trim()
if (-not (Test-Path -LiteralPath $keystore)) {
    Write-Host "Файла нет: $keystore" -ForegroundColor Red
    exit 1
}
$keystore = (Resolve-Path -LiteralPath $keystore).Path

$alias = Read-Host 'Псевдоним ключа (alias, обычно zales)'
if ([string]::IsNullOrWhiteSpace($alias)) { $alias = 'zales' }

$storeSecure = Read-Host 'Пароль хранилища' -AsSecureString
$keySecure = Read-Host 'Пароль ключа (Enter, если тот же)' -AsSecureString

$storePass = Read-Plain $storeSecure
$keyPass = Read-Plain $keySecure
if ([string]::IsNullOrEmpty($keyPass)) { $keyPass = $storePass }
if ([string]::IsNullOrEmpty($storePass)) {
    Write-Host 'Пароль хранилища не введён.' -ForegroundColor Red
    exit 1
}

# ── Prove both passwords work, before they become a broken release ─────────
# A certificate request needs the private key, so it checks the key password
# as well as the store's — which listing alone would not. keytool reads both
# from the environment, so neither appears in the process list where any other
# program on this machine could see it.
$env:ZALES_SETUP_STOREPASS = $storePass
$env:ZALES_SETUP_KEYPASS = $keyPass
$scratch = Join-Path ([IO.Path]::GetTempPath()) ([IO.Path]::GetRandomFileName())
try {
    $null = & $keytool -certreq -alias $alias -keystore $keystore `
        -storepass:env ZALES_SETUP_STOREPASS -keypass:env ZALES_SETUP_KEYPASS -file $scratch
    $opened = ($LASTEXITCODE -eq 0)
} finally {
    Remove-Item Env:\ZALES_SETUP_STOREPASS -ErrorAction SilentlyContinue
    Remove-Item Env:\ZALES_SETUP_KEYPASS -ErrorAction SilentlyContinue
    if (Test-Path -LiteralPath $scratch) { Remove-Item -LiteralPath $scratch -Force }
}

if (-not $opened) {
    Write-Host ''
    Write-Host 'Ключ не открылся: не тот пароль или не тот псевдоним.' -ForegroundColor Red
    Write-Host 'Посмотреть, какие псевдонимы внутри:' -ForegroundColor DarkGray
    Write-Host '  keytool -list -keystore zales.jks' -ForegroundColor DarkGray
    exit 1
}
Write-Host 'Ключ открылся, оба пароля верные.' -ForegroundColor Green

# ── Hand them to GitHub ────────────────────────────────────────────────────
$secrets = [ordered]@{
    'ZALES_KEYSTORE_BASE64'   = [Convert]::ToBase64String([IO.File]::ReadAllBytes($keystore))
    'ZALES_KEYSTORE_PASSWORD' = $storePass
    'ZALES_KEY_ALIAS'         = $alias
    'ZALES_KEY_PASSWORD'      = $keyPass
}

foreach ($name in $secrets.Keys) {
    # Through stdin, so the value is not an argument anywhere.
    $secrets[$name] | & gh secret set $name
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Не удалось записать секрет $name" -ForegroundColor Red
        exit 1
    }
    Write-Host "  $name — записан" -ForegroundColor Green
}

# ── Forget everything ──────────────────────────────────────────────────────
foreach ($name in @($secrets.Keys)) { $secrets[$name] = $null }
$storePass = $null
$keyPass = $null
[GC]::Collect()

Write-Host ''
Write-Host 'Готово. Теперь релиз — это один тег:' -ForegroundColor Cyan
Write-Host '  git tag -a v0.1.0 -m "Zales 0.1.0"' -ForegroundColor DarkGray
Write-Host '  git push origin v0.1.0' -ForegroundColor DarkGray
Write-Host ''
Write-Host 'И главное: не потеряйте сам zales.jks.' -ForegroundColor Yellow
Write-Host 'Android не даст обновить приложение, подписанное другим ключом, —'
Write-Host 'придётся удалять и ставить заново, теряя вставленный ключ доступа.'
Write-Host ''
