$ErrorActionPreference = 'Continue'
$SweepDir = $PSScriptRoot
$Backend = (Resolve-Path (Join-Path $SweepDir '..\..\jd-rag-resume-backend')).Path
$Tokenizer = Join-Path $Backend 'models\gte-multilingual-base-int8\tokenizer.json'
$Model = Join-Path $Backend 'models\gte-multilingual-base-int8\model_int8.onnx'
if (-not (Test-Path -LiteralPath $Tokenizer)) { throw "missing tokenizer: $Tokenizer" }
if (-not (Test-Path -LiteralPath $Model)) { throw "missing ONNX model: $Model" }

$env:RUN_THRESHOLD_SWEEP = 'true'
$env:THRESHOLD_SWEEP_DIR = $SweepDir
$env:AI_MOCK_ENABLED = 'true'
$env:RAG_EMBEDDING_TOKENIZER_URI = ([Uri]$Tokenizer).AbsoluteUri
$env:RAG_EMBEDDING_MODEL_URI = ([Uri]$Model).AbsoluteUri
$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8'

Set-Location $Backend
New-Item -ItemType Directory -Force -Path (Join-Path $SweepDir 'logs') | Out-Null
$log = Join-Path $SweepDir 'logs\maven-sweep.log'
Write-Host "THRESHOLD_SWEEP_DIR=$env:THRESHOLD_SWEEP_DIR"
Write-Host "RAG_EMBEDDING_MODEL_URI=$env:RAG_EMBEDDING_MODEL_URI"
& .\mvnw.cmd -B -Dtest=ThresholdSweepExperimentTests#sweepMinSimilarityThresholds test *>&1 | Tee-Object -FilePath $log
exit $LASTEXITCODE
