$ErrorActionPreference = 'Stop'

$mysql = 'C:\tmp\mysql-9.7.0-winx64\bin\mysql.exe'

if (-not (Test-Path -LiteralPath $mysql)) {
    throw "mysql.exe was not found at $mysql"
}

& $mysql -h 127.0.0.1 -P 3306 -u root
