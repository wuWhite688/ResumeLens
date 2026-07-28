$ErrorActionPreference = 'Stop'

$mysqladmin = 'C:\tmp\mysql-9.7.0-winx64\bin\mysqladmin.exe'

if (-not (Test-Path -LiteralPath $mysqladmin)) {
    throw "mysqladmin.exe was not found at $mysqladmin"
}

& $mysqladmin -h 127.0.0.1 -P 3306 -u root shutdown
Write-Host 'MySQL stopped.'
