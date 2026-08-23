param(
    [string]$JavaHome,
    [string]$TokenizerPath,
    [string]$ModelPath
)

$ErrorActionPreference = 'Stop'
$AblationDir = $PSScriptRoot
$RepoRoot = (Resolve-Path (Join-Path $AblationDir '..\..')).Path
$Backend = (Resolve-Path (Join-Path $RepoRoot 'jd-rag-resume-backend')).Path
$SweepDir = (Resolve-Path (Join-Path $RepoRoot 'experiments\threshold-sweep')).Path

if ([string]::IsNullOrWhiteSpace($TokenizerPath)) {
    $TokenizerPath = Join-Path $Backend 'models\gte-multilingual-base-int8\tokenizer.json'
}
if ([string]::IsNullOrWhiteSpace($ModelPath)) {
    $ModelPath = Join-Path $Backend 'models\gte-multilingual-base-int8\model_int8.onnx'
}
if (-not (Test-Path -LiteralPath $TokenizerPath -PathType Leaf)) {
    throw "missing tokenizer: $TokenizerPath"
}
if (-not (Test-Path -LiteralPath $ModelPath -PathType Leaf)) {
    throw "missing ONNX model: $ModelPath"
}

if (-not [string]::IsNullOrWhiteSpace($JavaHome)) {
    $javaExecutable = Join-Path $JavaHome 'bin\java.exe'
    if (-not (Test-Path -LiteralPath $javaExecutable -PathType Leaf)) {
        throw "missing java.exe under JavaHome: $javaExecutable"
    }
    $env:JAVA_HOME = $JavaHome
    $env:Path = (Join-Path $JavaHome 'bin') + ';' + $env:Path
}

$env:RUN_RAG_ABLATION = 'true'
$env:RAG_ABLATION_DIR = $AblationDir
$env:THRESHOLD_SWEEP_DIR = $SweepDir
$env:AI_MOCK_ENABLED = 'true'
$env:RAG_EMBEDDING_TOKENIZER_URI = ([Uri](Resolve-Path -LiteralPath $TokenizerPath).Path).AbsoluteUri
$env:RAG_EMBEDDING_MODEL_URI = ([Uri](Resolve-Path -LiteralPath $ModelPath).Path).AbsoluteUri
$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8'

Set-Location $Backend
New-Item -ItemType Directory -Force -Path (Join-Path $AblationDir 'logs') | Out-Null
$logPath = Join-Path $AblationDir 'logs\maven-ablation.log'
& .\mvnw.cmd -B '-Dtest=ThresholdSweepExperimentTests#compareFullTextWithRagAcrossConfigurations' test *>&1 |
    Tee-Object -FilePath $logPath
exit $LASTEXITCODE
