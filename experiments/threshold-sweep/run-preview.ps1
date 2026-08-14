$ErrorActionPreference = 'Continue'
$SweepDir = $PSScriptRoot
$Backend = (Resolve-Path (Join-Path $SweepDir '..\..\jd-rag-resume-backend')).Path
$env:RUN_THRESHOLD_SWEEP_PREVIEW = 'true'
$env:THRESHOLD_SWEEP_DIR = $SweepDir
$env:AI_MOCK_ENABLED = 'true'
$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8'
Set-Location $Backend
New-Item -ItemType Directory -Force -Path (Join-Path $SweepDir 'logs') | Out-Null
$log = Join-Path $SweepDir 'logs\maven-preview.log'
& .\mvnw.cmd -B -Dtest=ThresholdSweepExperimentTests#dumpChunksForAnnotation test *>&1 | Tee-Object -FilePath $log
exit $LASTEXITCODE
