$ErrorActionPreference = 'Stop'

$root = 'C:\tmp'
$base = 'C:\tmp\mysql-9.7.0-winx64'
$data = 'C:\tmp\mysql-9.7-data'
$mysqld = Join-Path $base 'bin\mysqld.exe'

if (-not (Test-Path -LiteralPath $mysqld)) {
    throw "mysqld.exe was not found at $mysqld"
}

if (-not (Test-Path -LiteralPath $data)) {
    throw "Data directory was not found at $data"
}

$listening = netstat -ano | Select-String -Pattern ':3306 '
if ($listening) {
    Write-Host 'MySQL appears to already be listening on port 3306.'
    exit 0
}

$args = @(
    '--no-defaults',
    "--basedir=$base",
    "--datadir=$data",
    '--port=3306',
    '--bind-address=127.0.0.1',
    "--log-error=$root\mysql-9.7-error.log"
)

$process = Start-Process -FilePath $mysqld -ArgumentList $args -WorkingDirectory $root -WindowStyle Hidden -PassThru
Write-Host "Started MySQL with PID $($process.Id)"
