# Pre-fetch the Robolectric Android SDK jars the unit tests run on (API 26, 28, 31, 33, 34, 35) into
# the test user.home's Maven repository, with retries and SHA-512 checks.
param(
    [string]$ToolchainDir = $(if ($env:P3R_TOOLCHAIN) { $env:P3R_TOOLCHAIN } else { Join-Path (Split-Path -Parent $PSScriptRoot) ".toolchain" })
)

$ErrorActionPreference = "Stop"
$Repo = Join-Path $ToolchainDir "test-home\.m2\repository\org\robolectric\android-all-instrumented"
$Base = "https://repo1.maven.org/maven2/org/robolectric/android-all-instrumented"
$Versions = @(
    "8.0.0_r4-robolectric-r1-i7",
    "9-robolectric-4913185-2-i7",
    "12-robolectric-7732740-i7",
    "13-robolectric-9030017-i7",
    "14-robolectric-10818077-i7",
    "15-robolectric-12650502-i7"
)

foreach ($v in $Versions) {
    $dir = Join-Path $Repo $v
    $name = "android-all-instrumented-$v"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $jarPath = Join-Path $dir "$name.jar"
    $shaPath = Join-Path $dir "$name.jar.sha512"

    if ((Test-Path $jarPath) -and (Test-Path $shaPath)) {
        $expected = ((Get-Content $shaPath -Raw) -replace '\s+', '').Substring(0, 128).ToLowerInvariant()
        $actual = (Get-FileHash -Path $jarPath -Algorithm SHA512).Hash.ToLowerInvariant()
        if ($actual -eq $expected) {
            Write-Host "ok   $v"
            continue
        }
    }

    Write-Host "get  $v"
    foreach ($f in @("$name.pom", "$name.pom.sha512", "$name.jar.sha512", "$name.jar")) {
        $outPart = Join-Path $dir "$f.part"
        $outFinal = Join-Path $dir $f
        $url = "$Base/$v/$f"
        $tries = 0
        while ($true) {
            try {
                $tries++
                & curl.exe -fsSL --retry 3 -o $outPart $url
                if ($LASTEXITCODE -ne 0) { throw "curl failed with exit code $LASTEXITCODE" }
                Move-Item -Path $outPart -Destination $outFinal -Force
                break
            } catch {
                if ($tries -ge 5) { throw }
                Start-Sleep -Seconds 3
            }
        }
    }

    $expected = ((Get-Content $shaPath -Raw) -replace '\s+', '').Substring(0, 128).ToLowerInvariant()
    $actual = (Get-FileHash -Path $jarPath -Algorithm SHA512).Hash.ToLowerInvariant()
    if ($actual -ne $expected) {
        Remove-Item -Path $jarPath -Force -ErrorAction SilentlyContinue
        throw "SHA-512 mismatch for $v"
    }
}

Write-Host "Robolectric jars ready in $Repo"
