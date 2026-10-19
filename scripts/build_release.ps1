# Build, test, lint and package the signed release APK on Windows using only the project-local toolchain.
$ErrorActionPreference = "Stop"
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
$Root = Split-Path -Parent $PSScriptRoot
$EnvPs1 = Join-Path $Root ".toolchain\env.ps1"
if (-not (Test-Path $EnvPs1)) {
    throw "Toolchain not initialized. Run scripts\setup_toolchain.ps1 first."
}
. $EnvPs1
$env:P3R_QA_DIR = Join-Path $Root "qa_out"

$KeystoreProps = Join-Path $Root ".signing\keystore.properties"
if (-not (Test-Path $KeystoreProps)) {
    throw ".signing/keystore.properties is required; restore the private signing backup first."
}

Push-Location $Root
try {
    & python rules/build/build_p3r_rules.py --check
    if ($LASTEXITCODE -ne 0) { throw "rules build check failed" }
    & python -m unittest rules/build/test_build.py
    if ($LASTEXITCODE -ne 0) { throw "rules unit tests failed" }
    & python scripts/validate_persona_data.py
    if ($LASTEXITCODE -ne 0) { throw "Persona data check failed" }
} finally {
    Pop-Location
}

Push-Location (Join-Path $Root "android")
try {
    & .\gradlew.bat --no-daemon :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed with exit code $LASTEXITCODE" }

    $Apk = "app\build\outputs\apk\release\app-release.apk"
    $BuildGradle = Get-Content "app\build.gradle" -Raw
    if ($BuildGradle -match "versionName\s+'([^']+)'") {
        $Version = $Matches[1]
    } else {
        throw "Could not parse versionName from app/build.gradle"
    }

    $DistDir = Join-Path $Root "dist"
    New-Item -ItemType Directory -Force -Path $DistDir | Out-Null
    $OutApk = Join-Path $DistDir "P3R-Planner-$Version.apk"

    & zipalign.exe -c -P 16 -v 4 $Apk | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "zipalign check failed" }

    $CertOut = & apksigner.bat verify --print-certs $Apk
    if ($LASTEXITCODE -ne 0) { throw "apksigner verification failed" }
    $ExpectedCert = "83d4395bcac9e7b33b20f3bd2b1327c856e56a6f0bd4816c52a42550a2b653ba"
    $CertLines = @($CertOut | Select-String -Pattern '^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-fA-F]+)$')
    if ($CertLines.Count -ne 1 -or $CertLines[0].Matches[0].Groups[1].Value.ToLowerInvariant() -ne $ExpectedCert) {
        throw "Release must retain the 3.3.0 signing certificate ($ExpectedCert)"
    }
    $CertOut | Select-Object -First 4

    Copy-Item -Path $Apk -Destination $OutApk -Force
    $Hash = (Get-FileHash -Path $OutApk -Algorithm SHA256).Hash.ToLowerInvariant()
    $ShaLine = "$Hash  P3R-Planner-$Version.apk"
    $ShaLine | Set-Content -Path "$OutApk.sha256" -Encoding ASCII
    Write-Host $ShaLine

    & aapt2.exe dump badging $OutApk | Select-String -Pattern "^(package:|application-label:|sdkVersion|targetSdkVersion)"
    Write-Host "Release APK: $OutApk"
} finally {
    Pop-Location
}
