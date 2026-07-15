# Deploy Glooko Gluroo Bridge AWS stack (requires AWS SAM CLI + credentials)
param(
  [string]$EnvironmentName = "prod",
  [string]$AlertEmail = "",
  [string]$Region = $env:AWS_REGION
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$Backend = Join-Path $Root "backend"
$BuildDir = Join-Path $Backend ".aws-sam-build"

Write-Host "Packaging Lambda dependencies..."
if (Test-Path $BuildDir) { Remove-Item -Recurse -Force $BuildDir }
New-Item -ItemType Directory -Force -Path $BuildDir | Out-Null

# Copy source
Copy-Item -Recurse (Join-Path $Backend "g2g") (Join-Path $BuildDir "g2g")
Copy-Item -Recurse (Join-Path $Backend "handlers") (Join-Path $BuildDir "handlers")

# Install runtime deps into package root
python -m pip install -r (Join-Path $Backend "requirements-lambda.txt") -t $BuildDir --upgrade --quiet

$Overrides = "EnvironmentName=$EnvironmentName"
if ($AlertEmail) { $Overrides += " AlertEmail=$AlertEmail" }

$samArgs = @(
  "deploy",
  "--template-file", (Join-Path $PSScriptRoot "template.yaml"),
  "--stack-name", "g2g-$EnvironmentName",
  "--capabilities", "CAPABILITY_IAM",
  "--resolve-s3",
  "--parameter-overrides", $Overrides,
  "--no-confirm-changeset",
  "--no-fail-on-empty-changeset"
)
if ($Region) { $samArgs += @("--region", $Region) }

# Point CodeUri via temporary template copy with build dir
$Template = Get-Content (Join-Path $PSScriptRoot "template.yaml") -Raw
$Template = $Template -replace "CodeUri: ../backend/", "CodeUri: $(($BuildDir -replace '\\','/'))/"
$TmpTemplate = Join-Path $PSScriptRoot "template.built.yaml"
Set-Content -Path $TmpTemplate -Value $Template -Encoding UTF8
$samArgs[($samArgs.IndexOf("--template-file") + 1)] = $TmpTemplate

Write-Host "sam $($samArgs -join ' ')"
& sam @samArgs

Write-Host ""
Write-Host "Stack outputs:"
aws cloudformation describe-stacks --stack-name "g2g-$EnvironmentName" `
  --query "Stacks[0].Outputs" --output table
