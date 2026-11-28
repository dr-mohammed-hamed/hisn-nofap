<#
.SYNOPSIS
    Pre-flight Validated GitHub Release Publisher for Hisn (AntiPorn).
.DESCRIPTION
    1. Resolves GitHub CLI (gh.exe) and verifies authentication.
    2. Validates version.json and APK byte size / lowercase SHA-256 match.
    3. Verifies APK v2/v3 signatures and pinned certificate SHA-256 via apksigner.bat.
    4. Enforces Zero-Leak Gate (ensures keystore, local.properties, and HISN_CONTEXT.md are untracked).
    5. Stages release assets, checksums.txt, and bilingual release notes in a temporary directory.
    6. Creates/pushes annotated git tag and publishes the GitHub Release (unless -DryRun is specified).
.EXAMPLE
    .\scripts\publish_github_release.ps1 -DryRun
.EXAMPLE
    .\scripts\publish_github_release.ps1 -IncludeCanonicalAsset -Draft
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $false)]
    [string]$Repo = "dr-mohammed-hamed/hisn-nofap",

    [Parameter(Mandatory = $false)]
    [string]$ApkPath = "app\build\outputs\apk\release\app-release-rotated.apk",

    [Parameter(Mandatory = $false)]
    [string]$VersionFile = "version.json",

    [Parameter(Mandatory = $false)]
    [string]$Tag,

    [Parameter(Mandatory = $false)]
    [string]$Title,

    [Parameter(Mandatory = $false)]
    [string]$NotesFile,

    [Parameter(Mandatory = $false)]
    [switch]$IncludeCanonicalAsset,

    [Parameter(Mandatory = $false)]
    [switch]$Draft,

    [Parameter(Mandatory = $false)]
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"

$ExpectedCertSha256 = "0b18bb0ca9f775d7095d822e0cd5dd94a37ce04b8abbc05fd0bd129123c238c9"
$projectRoot = Split-Path -Parent $PSScriptRoot

function Resolve-ProjectPath {
    param([string]$PathValue)
    if ([System.IO.Path]::IsPathRooted($PathValue)) {
        return $PathValue
    }
    return Join-Path $projectRoot $PathValue
}

function Invoke-NativeQuiet {
    param(
        [string]$Executable,
        [string[]]$Arguments
    )
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & $Executable @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prevEap
    }
    return [PSCustomObject]@{
        ExitCode = $exitCode
        Output   = ($output | Out-String).Trim()
    }
}

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "  Hisn (AntiPorn) - GitHub Release Publisher              " -ForegroundColor Cyan
if ($DryRun) {
    Write-Host "  MODE: DRY-RUN (No Git Tags or GitHub Changes)           " -ForegroundColor Yellow
}
Write-Host "==========================================================" -ForegroundColor Cyan

# ------------------------------------------------------------------
# [1/5] Pre-flight A: Resolve gh.exe and verify authentication
# ------------------------------------------------------------------
Write-Host "[1/5] Checking GitHub CLI (gh.exe) & authentication..." -ForegroundColor Green
$ghCmd = Get-Command gh -ErrorAction SilentlyContinue
$ghExe = $null
if ($ghCmd) {
    $ghExe = $ghCmd.Source
} elseif (Test-Path "C:\Program Files\GitHub CLI\gh.exe") {
    $ghExe = "C:\Program Files\GitHub CLI\gh.exe"
}

if ([string]::IsNullOrWhiteSpace($ghExe) -or -not (Test-Path $ghExe)) {
    throw "GitHub CLI (gh.exe) was not found in PATH or 'C:\Program Files\GitHub CLI\gh.exe'."
}
Write-Host "   -> Found gh CLI: $ghExe" -ForegroundColor Gray

$authCheck = Invoke-NativeQuiet -Executable $ghExe -Arguments @("auth", "status")
if ($authCheck.ExitCode -ne 0) {
    if ($DryRun) {
        Write-Warning "GitHub CLI is not authenticated (allowed in -DryRun mode). Run 'gh auth login' before publishing."
    } else {
        throw "GitHub CLI authentication failed. Please run 'gh auth login' or set GH_TOKEN.`n$($authCheck.Output)"
    }
} else {
    Write-Host "   -> GitHub CLI authentication verified." -ForegroundColor Gray
}

# ------------------------------------------------------------------
# [2/5] Pre-flight B: Validate version.json & APK size/SHA-256
# ------------------------------------------------------------------
Write-Host "[2/5] Validating version metadata and APK integrity..." -ForegroundColor Green
$resolvedVersionFile = Resolve-ProjectPath $VersionFile
$resolvedApkPath = Resolve-ProjectPath $ApkPath

if (-not (Test-Path $resolvedVersionFile)) {
    throw "Version file not found: $resolvedVersionFile"
}
if (-not (Test-Path $resolvedApkPath)) {
    throw "Release APK not found: $resolvedApkPath"
}

$vData = Get-Content $resolvedVersionFile -Raw -Encoding UTF8 | ConvertFrom-Json
if ([string]::IsNullOrWhiteSpace($vData.versionName) -or -not $vData.versionCode) {
    throw "Invalid version.json: 'versionName' or 'versionCode' is missing."
}

$versionName = [string]$vData.versionName
$versionCode = [string]$vData.versionCode

if ([string]::IsNullOrWhiteSpace($Tag)) {
    $Tag = "v$versionName"
}
if ([string]::IsNullOrWhiteSpace($Title)) {
    $Title = "Hisn v$versionName (Build $versionCode)"
}

$apkItem = Get-Item $resolvedApkPath
$actualSizeBytes = [long]$apkItem.Length
$actualSizeMb = [math]::Round($actualSizeBytes / 1MB, 2)
$actualSha256 = (Get-FileHash -Path $resolvedApkPath -Algorithm SHA256).Hash.ToLowerInvariant()

if ([long]$vData.fileSizeBytes -ne $actualSizeBytes) {
    throw "APK size mismatch! version.json expects $($vData.fileSizeBytes) bytes, but '$resolvedApkPath' is $actualSizeBytes bytes."
}

$expectedSha256 = ([string]$vData.sha256ApkHash).Trim().ToLowerInvariant()
if ($expectedSha256 -ne $actualSha256) {
    throw "APK SHA-256 mismatch! version.json expects '$expectedSha256', but actual hash is '$actualSha256'."
}

Write-Host "   -> Version: v$versionName (Build $versionCode)" -ForegroundColor Gray
Write-Host "   -> Size:    $actualSizeBytes bytes ($actualSizeMb MB) [MATCH]" -ForegroundColor Gray
Write-Host "   -> SHA-256: $actualSha256 [MATCH]" -ForegroundColor Gray

# ------------------------------------------------------------------
# [3/5] Pre-flight C: Verify APK v2/v3 Signature & Pinned Cert
# ------------------------------------------------------------------
Write-Host "[3/5] Verifying APK v2/v3 signature and pinned certificate..." -ForegroundColor Green
$buildToolsRoot = Join-Path $env:LOCALAPPDATA "Android\Sdk\build-tools"
if (-not (Test-Path $buildToolsRoot)) {
    throw "Android SDK build-tools directory not found at: $buildToolsRoot"
}

$apksignerItem = Get-ChildItem -Path $buildToolsRoot -Filter "apksigner.bat" -Recurse -ErrorAction SilentlyContinue |
    Sort-Object FullName -Descending |
    Select-Object -First 1

if (-not $apksignerItem) {
    throw "apksigner.bat not found under: $buildToolsRoot"
}
$apksigner = $apksignerItem.FullName
Write-Host "   -> Using apksigner: $apksigner" -ForegroundColor Gray

$sigCheck = Invoke-NativeQuiet -Executable $apksigner -Arguments @("verify", "--verbose", "--print-certs", $resolvedApkPath)
if ($sigCheck.ExitCode -ne 0) {
    throw "apksigner verification failed with exit code $($sigCheck.ExitCode):`n$($sigCheck.Output)"
}

if ($sigCheck.Output -notmatch "Verified using v2 scheme.*:\s*true") {
    throw "APK signature check failed: v2 scheme verification is not true.`n$($sigCheck.Output)"
}
if ($sigCheck.Output -notmatch "Verified using v3 scheme.*:\s*true") {
    throw "APK signature check failed: v3 scheme verification is not true.`n$($sigCheck.Output)"
}
if ($sigCheck.Output.ToLowerInvariant() -notmatch [regex]::Escape($ExpectedCertSha256.ToLowerInvariant())) {
    throw "APK certificate SHA-256 mismatch! Expected '$ExpectedCertSha256'.`n$($sigCheck.Output)"
}

Write-Host "   -> v2 & v3 schemes verified; certificate SHA-256 matches pinned key." -ForegroundColor Gray

# ------------------------------------------------------------------
# [4/5] Pre-flight D: Zero-Leak Gate
# ------------------------------------------------------------------
Write-Host "[4/5] Running Zero-Leak Gate against tracked git files..." -ForegroundColor Green
$sensitivePaths = @(
    "app/keystore/antiporn-release.jks",
    "local.properties",
    "HISN_CONTEXT.md"
)
$leakCheck = Invoke-NativeQuiet -Executable "git" -Arguments (@("-C", $projectRoot, "ls-files", "--") + $sensitivePaths)
if ($leakCheck.ExitCode -ne 0) {
    throw "Failed to run git ls-files in '$projectRoot': $($leakCheck.Output)"
}
if (-not [string]::IsNullOrWhiteSpace($leakCheck.Output)) {
    throw "ZERO-LEAK GATE VIOLATION! Sensitive file(s) are tracked by Git:`n$($leakCheck.Output)"
}
Write-Host "   -> Zero-Leak Gate passed (no sensitive files tracked)." -ForegroundColor Gray

# ------------------------------------------------------------------
# [5/5] Stage Assets in Temp & Publish (or Dry-Run)
# ------------------------------------------------------------------
Write-Host "[5/5] Staging release assets and notes..." -ForegroundColor Green

$stagingDir = Join-Path $env:TEMP ("hisn-gh-release-" + [guid]::NewGuid().ToString("N"))
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)

try {
    New-Item -ItemType Directory -Path $stagingDir -Force | Out-Null

    $versionedAssetName = "hisn-v$versionName.apk"
    $versionedAssetPath = Join-Path $stagingDir $versionedAssetName
    Copy-Item -Path $resolvedApkPath -Destination $versionedAssetPath -Force

    $stagedApkFiles = [System.Collections.Generic.List[string]]::new()
    $stagedApkFiles.Add($versionedAssetPath)

    if ($IncludeCanonicalAsset) {
        $canonicalAssetPath = Join-Path $stagingDir "antiporn-release.apk"
        Copy-Item -Path $resolvedApkPath -Destination $canonicalAssetPath -Force
        $stagedApkFiles.Add($canonicalAssetPath)
    }

    # Generate checksums.txt (<sha256>  <filename>) for every attached APK
    $checksumLines = [System.Collections.Generic.List[string]]::new()
    foreach ($apkAsset in $stagedApkFiles) {
        $assetName = [System.IO.Path]::GetFileName($apkAsset)
        $assetHash = (Get-FileHash -Path $apkAsset -Algorithm SHA256).Hash.ToLowerInvariant()
        $checksumLines.Add("$assetHash  $assetName")
    }
    $checksumsPath = Join-Path $stagingDir "checksums.txt"
    $checksumsContent = ($checksumLines -join "`n") + "`n"
    [System.IO.File]::WriteAllText($checksumsPath, $checksumsContent, $utf8NoBom)

    # Prepare release notes
    $notesTempPath = Join-Path $stagingDir "release-notes.md"
    if (-not [string]::IsNullOrWhiteSpace($NotesFile)) {
        $resolvedNotesFile = Resolve-ProjectPath $NotesFile
        if (-not (Test-Path $resolvedNotesFile)) {
            throw "NotesFile not found: $resolvedNotesFile"
        }
        $notesContent = Get-Content -Path $resolvedNotesFile -Raw -Encoding UTF8
        [System.IO.File]::WriteAllText($notesTempPath, $notesContent, $utf8NoBom)
    } else {
        $releaseDate = if ($vData.releaseDate) { [string]$vData.releaseDate } else { Get-Date -Format "yyyy-MM-dd" }
        $releaseNotesText = if ($vData.releaseNotes) { [string]$vData.releaseNotes } else { "Performance improvements and stability fixes." }
        $r2DownloadUrl = if ($vData.downloadUrl) { [string]$vData.downloadUrl } else { "https://pub-e568dc85c0ec4f2fb3d1b90e310b4310.r2.dev/antiporn-release.apk" }
        $certChecksum = if ($vData.sha256CertificateChecksum) { [string]$vData.sha256CertificateChecksum } else { "N/A" }
        $ghAssetUrl = "https://github.com/$Repo/releases/download/$Tag/$versionedAssetName"
        $canonicalLines = ""
        if ($IncludeCanonicalAsset) {
            $canonicalUrl = "https://github.com/$Repo/releases/download/$Tag/antiporn-release.apk"
            $canonicalLines = "`n- **الحزمة القياسية على GitHub (Canonical APK) | GitHub Canonical APK:** [antiporn-release.apk]($canonicalUrl)"
        }

        $notesTemplate = @'
# 🛡️ حصن (Hisn) - الإصدار {{VERSION_NAME}} | Release v{{VERSION_NAME}} (Build {{VERSION_CODE}})

> **تاريخ الإصدار (Release Date):** `{{RELEASE_DATE}}`

---

### 🌟 ما الجديد في هذا الإصدار | What's New (v{{VERSION_NAME}} - Build {{VERSION_CODE}}):

{{RELEASE_NOTES}}

---

### 📦 روابط التحميل والتثبيت المباشر | Direct Downloads:
- **حزمة التطبيق الرسمية الموقّعة (GitHub Release APK):** [{{VERSIONED_ASSET_NAME}}]({{GH_ASSET_URL}}){{CANONICAL_LINES}}
- **مرآة التوزيع السحابية (Cloudflare R2 Mirror):** [antiporn-release.apk]({{R2_DOWNLOAD_URL}})
- **ملف التحقق من البصمات (Checksums):** [checksums.txt](https://github.com/{{REPO}}/releases/download/{{TAG}}/checksums.txt)

---

### 🔐 بيانات التحقق والأمان | Verification & Integrity:
- **حجم الحزمة (File Size):** `{{SIZE_MB}} MB` (`{{SIZE_BYTES}}` bytes)
- **بصمة ملف الـ APK (APK SHA-256):** `{{APK_SHA256}}`
- **بصمة الشهادة الرقمية المعتمدة (Signing Cert SHA-256):** `{{CERT_SHA256}}`
- **Device Owner QR Provisioning Checksum:** `{{QR_CERT_CHECKSUM}}`

#### خطوات التحقق قبل التثبيت | Verify Before Installing:
```powershell
# Windows PowerShell
Get-FileHash .\{{VERSIONED_ASSET_NAME}} -Algorithm SHA256
```
```bash
# Linux / macOS
sha256sum -c checksums.txt
```
'@
        $notesContent = $notesTemplate.
            Replace("{{VERSION_NAME}}", $versionName).
            Replace("{{VERSION_CODE}}", $versionCode).
            Replace("{{RELEASE_DATE}}", $releaseDate).
            Replace("{{RELEASE_NOTES}}", $releaseNotesText).
            Replace("{{VERSIONED_ASSET_NAME}}", $versionedAssetName).
            Replace("{{GH_ASSET_URL}}", $ghAssetUrl).
            Replace("{{CANONICAL_LINES}}", $canonicalLines).
            Replace("{{R2_DOWNLOAD_URL}}", $r2DownloadUrl).
            Replace("{{REPO}}", $Repo).
            Replace("{{TAG}}", $Tag).
            Replace("{{SIZE_MB}}", [string]$actualSizeMb).
            Replace("{{SIZE_BYTES}}", [string]$actualSizeBytes).
            Replace("{{APK_SHA256}}", $actualSha256).
            Replace("{{CERT_SHA256}}", $ExpectedCertSha256).
            Replace("{{QR_CERT_CHECKSUM}}", $certChecksum)
        [System.IO.File]::WriteAllText($notesTempPath, $notesContent, $utf8NoBom)
    }

    $allAssets = [System.Collections.Generic.List[string]]::new()
    foreach ($apkAsset in $stagedApkFiles) {
        $allAssets.Add($apkAsset)
    }
    $allAssets.Add($checksumsPath)

    if ($DryRun) {
        Write-Host "`n==========================================================" -ForegroundColor Yellow
        Write-Host "  DRY-RUN SUMMARY (No changes made to Git or GitHub)      " -ForegroundColor Yellow
        Write-Host "==========================================================" -ForegroundColor Yellow
        Write-Host "Repository : $Repo" -ForegroundColor White
        Write-Host "Tag        : $Tag" -ForegroundColor White
        Write-Host "Title      : $Title" -ForegroundColor White
        Write-Host "Draft      : $($Draft.IsPresent)" -ForegroundColor White
        Write-Host "Assets     :" -ForegroundColor White
        foreach ($asset in $allAssets) {
            $assetSize = (Get-Item $asset).Length
            Write-Host "   - $([System.IO.Path]::GetFileName($asset)) ($assetSize bytes)" -ForegroundColor Cyan
        }
        Write-Host "`nchecksums.txt:" -ForegroundColor White
        Write-Host $checksumsContent.TrimEnd() -ForegroundColor Gray
        Write-Host "`nRelease Notes Preview:" -ForegroundColor White
        Write-Host "----------------------------------------------------------" -ForegroundColor DarkGray
        Write-Host $notesContent.TrimEnd() -ForegroundColor Gray
        Write-Host "----------------------------------------------------------" -ForegroundColor DarkGray
        Write-Host "`nDRY-RUN PASSED: All pre-flight checks, staging, checksums, and notes succeeded." -ForegroundColor Green
        exit 0
    }

    # Ensure annotated git tag exists on HEAD
    $headCheck = Invoke-NativeQuiet -Executable "git" -Arguments @("-C", $projectRoot, "rev-parse", "HEAD")
    if ($headCheck.ExitCode -ne 0) {
        throw "Unable to resolve HEAD commit: $($headCheck.Output)"
    }
    $headSha = $headCheck.Output

    $tagCheck = Invoke-NativeQuiet -Executable "git" -Arguments @("-C", $projectRoot, "rev-parse", "-q", "--verify", "refs/tags/$Tag^{commit}")
    if ($tagCheck.ExitCode -ne 0) {
        Write-Host "   -> Creating annotated tag '$Tag' on HEAD ($headSha)..." -ForegroundColor Cyan
        $tagCreate = Invoke-NativeQuiet -Executable "git" -Arguments @("-C", $projectRoot, "tag", "-a", $Tag, "-m", "Release $Tag")
        if ($tagCreate.ExitCode -ne 0) {
            throw "Failed to create git tag '$Tag': $($tagCreate.Output)"
        }
    } elseif ($tagCheck.Output -ne $headSha) {
        throw "Git tag '$Tag' already exists on commit $($tagCheck.Output), which does not match HEAD ($headSha)."
    } else {
        Write-Host "   -> Annotated tag '$Tag' already exists on HEAD ($headSha)." -ForegroundColor Gray
    }

    Write-Host "   -> Pushing tag '$Tag' to origin..." -ForegroundColor Cyan
    $tagPush = Invoke-NativeQuiet -Executable "git" -Arguments @("-C", $projectRoot, "push", "origin", $Tag)
    if ($tagPush.ExitCode -ne 0) {
        throw "Failed to push tag '$Tag' to origin: $($tagPush.Output)"
    }

    # Check if GitHub release already exists
    $releaseCheck = Invoke-NativeQuiet -Executable $ghExe -Arguments @("release", "view", $Tag, "--repo", $Repo)
    if ($releaseCheck.ExitCode -eq 0) {
        Write-Host "   -> GitHub Release '$Tag' already exists on '$Repo'. Skipping creation." -ForegroundColor Yellow
        exit 0
    }

    Write-Host "   -> Creating GitHub Release '$Tag' on '$Repo'..." -ForegroundColor Cyan
    $ghArgs = [System.Collections.Generic.List[string]]::new()
    $ghArgs.Add("release")
    $ghArgs.Add("create")
    $ghArgs.Add($Tag)
    foreach ($asset in $allAssets) {
        $ghArgs.Add($asset)
    }
    $ghArgs.Add("--repo")
    $ghArgs.Add($Repo)
    $ghArgs.Add("--title")
    $ghArgs.Add($Title)
    $ghArgs.Add("--notes-file")
    $ghArgs.Add($notesTempPath)
    if ($Draft) {
        $ghArgs.Add("--draft")
    }

    $createRelease = Invoke-NativeQuiet -Executable $ghExe -Arguments $ghArgs.ToArray()
    if ($createRelease.ExitCode -ne 0) {
        throw "gh release create failed:`n$($createRelease.Output)"
    }

    Write-Host "`n==========================================================" -ForegroundColor Green
    Write-Host "  SUCCESS! GITHUB RELEASE PUBLISHED ($Tag)" -ForegroundColor Green
    Write-Host "==========================================================" -ForegroundColor Green
    Write-Host $createRelease.Output -ForegroundColor Cyan
} finally {
    if (Test-Path $stagingDir) {
        Remove-Item -Path $stagingDir -Recurse -Force -ErrorAction SilentlyContinue
    }
}
