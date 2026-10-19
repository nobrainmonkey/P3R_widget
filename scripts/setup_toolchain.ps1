# Fetch a self-contained build toolchain into ./.toolchain on Windows (nothing is installed system-wide):
#   - Eclipse Temurin JDK 17 (pinned release jdk-17.0.14+7)
#   - Android command-line tools, SDK Platform 35, Build-Tools 35.0.0
#   - Gradle caches (GRADLE_USER_HOME) and Robolectric's Android SDK jars (test user.home) under .toolchain
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$Tc = Join-Path $Root ".toolchain"
New-Item -ItemType Directory -Force -Path $Tc | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $Tc "test-home\.m2") | Out-Null

$TemurinTag = if ($env:TEMURIN_17_TAG) { $env:TEMURIN_17_TAG } else { "jdk-17.0.14+7" }
$EncodedTag = [uri]::EscapeDataString($TemurinTag)

$jdkDirs = Get-ChildItem -Path $Tc -Directory -Filter "jdk-17*" -ErrorAction SilentlyContinue
if (-not $jdkDirs) {
    Write-Host "Downloading Temurin JDK 17 ($TemurinTag, windows/x64)..."
    $jdkZip = Join-Path $Tc "jdk17.zip"
    & curl.exe -fsSL --retry 3 -o $jdkZip "https://api.adoptium.net/v3/binary/version/$EncodedTag/windows/x64/jdk/hotspot/normal/eclipse"
    if ($LASTEXITCODE -ne 0) { throw "Failed to download Temurin JDK 17" }
    Expand-Archive -Path $jdkZip -DestinationPath $Tc -Force
    Remove-Item -Path $jdkZip -Force
    $jdkDirs = Get-ChildItem -Path $Tc -Directory -Filter "jdk-17*"
}
$JavaHome = $jdkDirs[0].FullName

$SdkRoot = Join-Path $Tc "android-sdk"
$SdkManager = Join-Path $SdkRoot "cmdline-tools\latest\bin\sdkmanager.bat"
if (-not (Test-Path $SdkManager)) {
    Write-Host "Downloading Android command-line tools (windows)..."
    $cmdZip = Join-Path $Tc "cmdtools.zip"
    & curl.exe -fsSL --retry 3 -o $cmdZip "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
    if ($LASTEXITCODE -ne 0) { throw "Failed to download Android cmdline-tools" }
    $cmdBase = Join-Path $SdkRoot "cmdline-tools"
    New-Item -ItemType Directory -Force -Path $cmdBase | Out-Null
    Expand-Archive -Path $cmdZip -DestinationPath $cmdBase -Force
    $latestDir = Join-Path $cmdBase "latest"
    if (Test-Path $latestDir) { Remove-Item -Path $latestDir -Recurse -Force }
    Move-Item -Path (Join-Path $cmdBase "cmdline-tools") -Destination $latestDir -Force
    Remove-Item -Path $cmdZip -Force
}

$EnvPs1 = Join-Path $Tc "env.ps1"
@"
`$env:JAVA_HOME = "$JavaHome"
`$env:ANDROID_HOME = "$SdkRoot"
`$env:ANDROID_SDK_ROOT = "$SdkRoot"
`$env:ANDROID_USER_HOME = "$Tc\android-user-home"
`$env:ANDROID_EMULATOR_HOME = "$Tc\android-user-home"
`$env:ANDROID_AVD_HOME = "$Tc\avd"
`$env:JAVA_TOOL_OPTIONS = "-Duser.home=$($Tc -replace '\\','/')/home"
`$env:GRADLE_USER_HOME = "$Tc\gradle-home"
`$env:P3R_TOOLCHAIN = "$Tc"
`$env:P3R_ROOT = "$Root"
`$env:PATH = "`$env:JAVA_HOME\bin;`$env:ANDROID_HOME\cmdline-tools\latest\bin;`$env:ANDROID_HOME\build-tools\35.0.0;`$env:ANDROID_HOME\platform-tools;`$env:PATH"
"@ | Set-Content -Path $EnvPs1 -Encoding UTF8

. $EnvPs1
New-Item -ItemType Directory -Force -Path $env:ANDROID_USER_HOME | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $Tc "home") | Out-Null

Write-Host "Accepting Android SDK licenses and installing platforms;android-35 and build-tools;35.0.0..."
$prevPrefs = $env:ANDROID_PREFS_ROOT
$env:ANDROID_PREFS_ROOT = $env:ANDROID_USER_HOME
try {
    $yesInput = ("y`n" * 30)
    $yesInput | & $SdkManager "--sdk_root=$SdkRoot" --licenses | Out-Null
    & $SdkManager "--sdk_root=$SdkRoot" "platforms;android-35" "build-tools;35.0.0" | Out-Null
} finally {
    $env:ANDROID_PREFS_ROOT = $prevPrefs
}

& (Join-Path $PSScriptRoot "fetch_robolectric_jars.ps1") -ToolchainDir $Tc
Write-Host "Toolchain ready. Run: . .\.toolchain\env.ps1"
