<#
.SYNOPSIS
    Deploys the local Waqf Support remote configuration (waqf_config.json) to Cloudflare R2.
.DESCRIPTION
    Reads local.properties / environment variables for the target R2 bucket and public base URL,
    validates waqf_config.json (strict UTF-8 JSON, placeholder guard against leaking 010XXXXXXXX),
    verifies Wrangler authentication, and uploads waqf_config.json with short-TTL cache headers.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $false)]
    [string]$BucketName = "antiporn-releases",

    [Parameter(Mandatory = $false)]
    [string]$ConfigFile = "scripts\waqf_config.json",

    [Parameter(Mandatory = $false)]
    [switch]$AllowPlaceholder
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$localPropsFile = Join-Path $repoRoot "local.properties"
$localProps = @{}
if (Test-Path $localPropsFile) {
    Get-Content $localPropsFile -Encoding UTF8 | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith("#") -and $line.Contains("=")) {
            $parts = $line.Split("=", 2)
            $localProps[$parts[0].Trim()] = $parts[1].Trim()
        }
    }
}

if ([string]::IsNullOrWhiteSpace($BucketName) -or $BucketName -eq "antiporn-releases") {
    if ($env:CLOUDFLARE_R2_BUCKET) {
        $BucketName = $env:CLOUDFLARE_R2_BUCKET
    } elseif ($localProps.ContainsKey("r2.bucket.name")) {
        $BucketName = $localProps["r2.bucket.name"]
    }
}

$r2BaseUrl = $env:CLOUDFLARE_R2_PUBLIC_URL
if ([string]::IsNullOrWhiteSpace($r2BaseUrl) -and $localProps.ContainsKey("ota.base.url")) {
    $r2BaseUrl = $localProps["ota.base.url"]
}

$resolvedConfigFile = $ConfigFile
if (-not (Test-Path $resolvedConfigFile) -and -not [System.IO.Path]::IsPathRooted($ConfigFile)) {
    $repoRelativePath = Join-Path $repoRoot $ConfigFile
    if (Test-Path $repoRelativePath) {
        $resolvedConfigFile = $repoRelativePath
    }
}

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "  Hisn (AntiPorn) - Waqf Config R2 Deployer               " -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# 1. Ensure local waqf_config.json exists
Write-Host "[1/4] Checking local Waqf configuration file..." -ForegroundColor Green
if (-not (Test-Path $resolvedConfigFile)) {
    Write-Host "   -> Error: Config file not found at '$ConfigFile'." -ForegroundColor Red
    Write-Host "   -> Copy 'scripts\waqf_config.example.json' to 'scripts\waqf_config.json'," -ForegroundColor Yellow
    Write-Host "      replace '010XXXXXXXX' with your real Vodafone Cash number, and re-run." -ForegroundColor Yellow
    Write-Host "   -> Note: 'scripts\waqf_config.json' is Git-ignored and will NEVER be committed." -ForegroundColor Gray
    exit 1
}

# 2. Validate UTF-8 JSON & check for unreplaced placeholder
Write-Host "[2/4] Validating JSON syntax and placeholder safety..." -ForegroundColor Green
try {
    $rawContent = Get-Content $resolvedConfigFile -Raw -Encoding UTF8
    if ([string]::IsNullOrWhiteSpace($rawContent)) {
        throw "Configuration file is empty."
    }
    $parsedConfig = $rawContent | ConvertFrom-Json
} catch {
    Write-Host "   -> Error: Invalid JSON in '$resolvedConfigFile': $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

$hasChannels = ($null -ne $parsedConfig.PSObject.Properties["channels"])
$hasHero = ($null -ne $parsedConfig.PSObject.Properties["hero"])
if (-not $hasChannels -and -not $hasHero) {
    Write-Host "   -> Error: Waqf config JSON must contain 'channels' or 'hero'." -ForegroundColor Red
    exit 1
}

$containsPlaceholder = $rawContent -match "010XXXXXXXX"
if ($containsPlaceholder) {
    if (-not $AllowPlaceholder) {
        Write-Host "   -> Error: Placeholder '010XXXXXXXX' is still present in '$resolvedConfigFile'." -ForegroundColor Red
        Write-Host "   -> Replace '010XXXXXXXX' with your actual Vodafone Cash number before deploying," -ForegroundColor Yellow
        Write-Host "      or pass -AllowPlaceholder if this is intentional for testing." -ForegroundColor Yellow
        exit 1
    } else {
        Write-Host "   -> Warning: Placeholder '010XXXXXXXX' detected, continuing because -AllowPlaceholder was specified." -ForegroundColor Yellow
    }
}

# Sanity-check channel actions to match WaqfActionValidator rules
if ($hasChannels -and $null -ne $parsedConfig.channels) {
    foreach ($channel in $parsedConfig.channels) {
        foreach ($actionProp in @("primaryAction", "secondaryAction")) {
            $action = $channel.$actionProp
            if ($null -eq $action) { continue }
            $actionType = [string]$action.type
            $actionTarget = [string]$action.target
            if ($actionType -eq "OPEN_URL" -and ($actionTarget -notmatch "^https://")) {
                Write-Host "   -> Error: Channel '$($channel.id)' $actionProp OPEN_URL target must start with 'https://'." -ForegroundColor Red
                exit 1
            }
            if ($actionType -eq "DIAL_USSD" -and -not $AllowPlaceholder -and ($actionTarget -notmatch "^[0-9*#]+$")) {
                Write-Host "   -> Error: Channel '$($channel.id)' $actionProp DIAL_USSD target '$actionTarget' must match '^[0-9*#]+$'." -ForegroundColor Red
                exit 1
            }
        }
    }
}
Write-Host "   -> JSON validated successfully." -ForegroundColor Gray

# 3. Verify Wrangler authentication
Write-Host "[3/4] Verifying Cloudflare Wrangler authentication..." -ForegroundColor Green
$wranglerCmd = Get-Command wrangler -ErrorAction SilentlyContinue
if ($null -eq $wranglerCmd) {
    Write-Host "   -> Error: 'wrangler' CLI not found in PATH. Install Wrangler and run 'wrangler login'." -ForegroundColor Red
    exit 1
}

$whoami = & wrangler whoami 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host "   -> Error: Cloudflare authentication failed. Run 'wrangler login' first." -ForegroundColor Red
    Write-Host "   -> Details: $whoami" -ForegroundColor Gray
    exit 1
}
Write-Host "   -> Logged in to Cloudflare successfully!" -ForegroundColor Gray

# 4. Upload waqf_config.json to Cloudflare R2
Write-Host "[4/4] Uploading '$resolvedConfigFile' to '$BucketName/waqf_config.json'..." -ForegroundColor Green
$uploadResult = & wrangler r2 object put "$BucketName/waqf_config.json" `
    --file="$resolvedConfigFile" `
    --content-type="application/json; charset=utf-8" `
    --cache-control="public, max-age=60, must-revalidate" `
    --remote `
    --force 2>&1

Write-Host "   -> $uploadResult" -ForegroundColor Gray
if ($LASTEXITCODE -ne 0) {
    Write-Host "   -> Error: Failed to upload waqf_config.json to Cloudflare R2 (exit code $LASTEXITCODE)." -ForegroundColor Red
    exit 1
}

$publicEndpoint = if (-not [string]::IsNullOrWhiteSpace($r2BaseUrl)) {
    "$($r2BaseUrl.TrimEnd('/'))/waqf_config.json"
} else {
    "<ota.base.url>/waqf_config.json (configure ota.base.url in local.properties)"
}

Write-Host "`n==========================================================" -ForegroundColor Cyan
Write-Host "  WAQF CONFIG DEPLOYMENT COMPLETE!                        " -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "Bucket:   $BucketName" -ForegroundColor White
Write-Host "Object:   waqf_config.json" -ForegroundColor White
Write-Host "Endpoint: $publicEndpoint" -ForegroundColor Green
