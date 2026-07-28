param(
    [string]$JavaPath = 'C:\Program Files\Java\jdk-25.0.2\bin\java.exe',
    [string]$JarPath = (Join-Path $PSScriptRoot 'target\jd-rag-resume-backend-0.0.1-SNAPSHOT.jar'),
    [int]$Port = 8080,
    [int]$MaxWaitSeconds = 180,
    [string]$StdoutLog = (Join-Path $PSScriptRoot 'target\app.out.log'),
    [string]$StderrLog = (Join-Path $PSScriptRoot 'target\app.err.log')
)

$ErrorActionPreference = 'Stop'

function Test-PortOpen {
    param([int]$TargetPort)

    try {
        $client = [System.Net.Sockets.TcpClient]::new()
        $connect = $client.BeginConnect('127.0.0.1', $TargetPort, $null, $null)
        if (-not $connect.AsyncWaitHandle.WaitOne(500)) {
            $client.Close()
            return $false
        }
        $client.EndConnect($connect)
        $client.Close()
        return $true
    } catch {
        return $false
    }
}

function Save-RemoteFile {
    param(
        [string]$Uri,
        [string]$Destination
    )

    if (Test-Path -LiteralPath $Destination) {
        return
    }

    $parent = Split-Path -Parent $Destination
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
    $partial = "$Destination.part"
    Write-Host "Downloading RAG model asset: $(Split-Path -Leaf $Destination)"
    & curl.exe -L --fail --retry 3 --retry-delay 2 -C - -o $partial $Uri
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to download $Uri"
    }
    Move-Item -LiteralPath $partial -Destination $Destination -Force
}

if (Test-PortOpen -TargetPort $Port) {
    Write-Host "Port $Port is already ready."
    exit 0
}

if (-not (Test-Path -LiteralPath $JavaPath)) {
    throw "java.exe was not found at $JavaPath"
}

if (-not (Test-Path -LiteralPath $JarPath)) {
    throw "jar was not found at $JarPath"
}

$ragModelDirectory = Join-Path $PSScriptRoot 'models\gte-multilingual-base-int8'
$ragTokenizerPath = Join-Path $ragModelDirectory 'tokenizer.json'
$ragModelPath = Join-Path $ragModelDirectory 'model_int8.onnx'
Save-RemoteFile `
    -Uri 'https://huggingface.co/onnx-community/gte-multilingual-base/resolve/main/tokenizer.json' `
    -Destination $ragTokenizerPath
Save-RemoteFile `
    -Uri 'https://huggingface.co/onnx-community/gte-multilingual-base/resolve/main/onnx/model_int8.onnx' `
    -Destination $ragModelPath
if ([string]::IsNullOrWhiteSpace($env:RAG_EMBEDDING_TOKENIZER_URI)) {
    $env:RAG_EMBEDDING_TOKENIZER_URI = ([Uri]$ragTokenizerPath).AbsoluteUri
}
if ([string]::IsNullOrWhiteSpace($env:RAG_EMBEDDING_MODEL_URI)) {
    $env:RAG_EMBEDDING_MODEL_URI = ([Uri]$ragModelPath).AbsoluteUri
}

if ([string]::IsNullOrWhiteSpace($env:AI_BASE_URL)) {
    $env:AI_BASE_URL = 'https://api.deepseek.com'
}
if ([string]::IsNullOrWhiteSpace($env:AI_MODEL)) {
    $env:AI_MODEL = 'deepseek-v4-flash'
}
if ([string]::IsNullOrWhiteSpace($env:AI_MOCK_ENABLED)) {
    $env:AI_MOCK_ENABLED = 'false'
}
if ($env:AI_MOCK_ENABLED -ne 'true' -and [string]::IsNullOrWhiteSpace($env:AI_API_KEY)) {
    $secureKey = Read-Host 'Enter DeepSeek API Key' -AsSecureString
    $env:AI_API_KEY = [Net.NetworkCredential]::new('', $secureKey).Password
    if ([string]::IsNullOrWhiteSpace($env:AI_API_KEY)) {
        throw 'DeepSeek API Key cannot be empty'
    }
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $StdoutLog) | Out-Null

$pathValue = [Environment]::GetEnvironmentVariable('Path', 'Process')
if ([string]::IsNullOrWhiteSpace($pathValue)) {
    $pathValue = [Environment]::GetEnvironmentVariable('PATH', 'Process')
}
[Environment]::SetEnvironmentVariable('PATH', $null, 'Process')
[Environment]::SetEnvironmentVariable('Path', $pathValue, 'Process')

$process = Start-Process `
    -FilePath $JavaPath `
    -ArgumentList @('-jar', $JarPath) `
    -WorkingDirectory $PSScriptRoot `
    -RedirectStandardOutput $StdoutLog `
    -RedirectStandardError $StderrLog `
    -WindowStyle Hidden `
    -PassThru

$deadline = [DateTime]::UtcNow.AddSeconds($MaxWaitSeconds)
while ([DateTime]::UtcNow -lt $deadline) {
    if (Test-PortOpen -TargetPort $Port) {
        Write-Host "Backend is ready on port $Port. PID: $($process.Id)"
        exit 0
    }

    if ($process.HasExited) {
        Write-Error "Backend process exited before port $Port became ready. ExitCode: $($process.ExitCode). Logs: $StdoutLog, $StderrLog"
        exit 1
    }

    Start-Sleep -Milliseconds 500
}

Write-Error "Timed out after $MaxWaitSeconds seconds waiting for port $Port. PID: $($process.Id). Logs: $StdoutLog, $StderrLog"
exit 1
