[CmdletBinding(PositionalBinding = $false)]
param(
    [string]$MysqlBaseDir,
    [string]$MysqlDataDir,
    [string]$User = 'root',
    [string]$Password,
    [string]$Database,
    [string]$Execute
)

$ErrorActionPreference = 'Stop'

# Keep this resolver in sync across start-mysql.ps1, stop-mysql.ps1, and connect-mysql.ps1.
# Priority: -MysqlBaseDir/-MysqlDataDir, then RESUMELENS_MYSQL_HOME/RESUMELENS_MYSQL_DATA, then auto-detect.

function Get-NormalizedPath {
    param([string]$PathSpec)
    if ([string]::IsNullOrWhiteSpace($PathSpec)) {
        return $null
    }
    $value = $PathSpec.Trim().Trim('"')
    if (-not [IO.Path]::IsPathRooted($value)) {
        $value = Join-Path -Path (Get-Location).Path -ChildPath $value
    }
    return [IO.Path]::GetFullPath($value)
}

function Test-MysqlBaseDir {
    param([string]$BaseDir)
    if ([string]::IsNullOrWhiteSpace($BaseDir) -or -not (Test-Path -LiteralPath $BaseDir -PathType Container)) {
        return $false
    }
    foreach ($exe in @('mysqld.exe', 'mysql.exe', 'mysqladmin.exe')) {
        if (-not (Test-Path -LiteralPath (Join-Path $BaseDir "bin\$exe") -PathType Leaf)) {
            return $false
        }
    }
    return $true
}

function Test-MysqlDataDir {
    param([string]$DataDir)
    return -not [string]::IsNullOrWhiteSpace($DataDir) -and (Test-Path -LiteralPath $DataDir -PathType Container)
}

function Get-MysqlNameVersion {
    param(
        [string]$Name,
        [ValidateSet('base', 'data')]
        [string]$Kind
    )
    if ($Kind -eq 'base' -and $Name -match '^mysql-(.+)-winx64$') {
        return $Matches[1]
    }
    if ($Kind -eq 'data' -and $Name -match '^mysql-(.+)-data$') {
        return $Matches[1]
    }
    return $null
}

function Test-MysqlVersionPair {
    param([string]$BaseVersion, [string]$DataVersion)
    if ([string]::IsNullOrWhiteSpace($BaseVersion) -or [string]::IsNullOrWhiteSpace($DataVersion)) {
        return $false
    }
    return $BaseVersion -eq $DataVersion -or
        $BaseVersion.StartsWith($DataVersion + '.') -or
        $DataVersion.StartsWith($BaseVersion + '.')
}

function Get-PairedMysqlDataDir {
    param(
        [string]$BaseDir,
        [string]$RepoRoot
    )
    $parent = [IO.Path]::GetDirectoryName($BaseDir)
    $leaf = [IO.Path]::GetFileName($BaseDir)
    $normParent = Get-NormalizedPath $parent
    $normRepo = Get-NormalizedPath $RepoRoot

    # Repo layout: <repo>\mysql-*-winx64  +  <repo>\data
    if ($normParent -eq $normRepo) {
        $repoData = Join-Path $RepoRoot 'data'
        if (Test-MysqlDataDir $repoData) {
            return (Get-NormalizedPath $repoData)
        }
        return $null
    }

    # Sibling layout: <parent>\mysql-*-winx64  +  <parent>\mysql-*-data (same version family)
    $version = Get-MysqlNameVersion -Name $leaf -Kind base
    if ($null -eq $version) {
        return $null
    }
    $parts = $version.Split('.')
    for ($count = $parts.Length; $count -ge 1; $count--) {
        $prefix = [string]::Join('.', $parts[0..($count - 1)])
        $candidate = Join-Path $parent "mysql-$prefix-data"
        if (Test-MysqlDataDir $candidate) {
            return (Get-NormalizedPath $candidate)
        }
    }
    return $null
}

function Get-PairedMysqlBaseDir {
    param(
        [string]$DataDir,
        [string]$RepoRoot
    )
    $parent = [IO.Path]::GetDirectoryName($DataDir)
    $leaf = [IO.Path]::GetFileName($DataDir)
    $normData = Get-NormalizedPath $DataDir
    $repoData = Get-NormalizedPath (Join-Path $RepoRoot 'data')

    if ($normData -eq $repoData) {
        $found = @(Get-ChildItem -LiteralPath $RepoRoot -Directory -Filter 'mysql-*-winx64' -ErrorAction SilentlyContinue |
            Where-Object { Test-MysqlBaseDir $_.FullName } |
            Sort-Object -Property Name -Descending)
        if ($found.Count -gt 0) {
            return $found[0].FullName
        }
        return $null
    }

    $version = Get-MysqlNameVersion -Name $leaf -Kind data
    if ($null -eq $version -or -not (Test-Path -LiteralPath $parent -PathType Container)) {
        return $null
    }
    $found = @(Get-ChildItem -LiteralPath $parent -Directory -Filter 'mysql-*-winx64' -ErrorAction SilentlyContinue |
        Where-Object {
            $baseVersion = Get-MysqlNameVersion -Name $_.Name -Kind base
            (Test-MysqlVersionPair -BaseVersion $baseVersion -DataVersion $version) -and (Test-MysqlBaseDir $_.FullName)
        } |
        Sort-Object -Property Name -Descending)
    if ($found.Count -gt 0) {
        return $found[0].FullName
    }
    return $null
}

function Get-DetectedMysqlPairs {
    param([string]$RepoRoot)
    $pairs = New-Object System.Collections.Generic.List[object]

    $repoData = Join-Path $RepoRoot 'data'
    if (Test-MysqlDataDir $repoData) {
        $bases = @(Get-ChildItem -LiteralPath $RepoRoot -Directory -Filter 'mysql-*-winx64' -ErrorAction SilentlyContinue |
            Where-Object { Test-MysqlBaseDir $_.FullName } |
            Sort-Object -Property Name -Descending)
        foreach ($base in $bases) {
            $pairs.Add([pscustomobject]@{
                    Base   = $base.FullName
                    Data   = (Get-NormalizedPath $repoData)
                    Origin = "auto-detect: $RepoRoot\mysql-*-winx64 + data\"
                })
        }
    }

    $tmpRoot = 'C:\tmp'
    if (Test-Path -LiteralPath $tmpRoot -PathType Container) {
        $tmpBases = @(Get-ChildItem -LiteralPath $tmpRoot -Directory -Filter 'mysql-*-winx64' -ErrorAction SilentlyContinue |
            Where-Object { Test-MysqlBaseDir $_.FullName } |
            Sort-Object -Property Name -Descending)
        $tmpDatas = @(Get-ChildItem -LiteralPath $tmpRoot -Directory -Filter 'mysql-*-data' -ErrorAction SilentlyContinue)
        foreach ($base in $tmpBases) {
            $baseVersion = Get-MysqlNameVersion -Name $base.Name -Kind base
            $dataMatch = @(
                $tmpDatas | Where-Object {
                    Test-MysqlVersionPair -BaseVersion $baseVersion -DataVersion (Get-MysqlNameVersion -Name $_.Name -Kind data)
                } | Sort-Object -Property Name -Descending
            )
            if ($dataMatch.Count -gt 0) {
                $pairs.Add([pscustomobject]@{
                        Base   = $base.FullName
                        Data   = $dataMatch[0].FullName
                        Origin = "auto-detect: $tmpRoot\mysql-*-winx64 + $tmpRoot\mysql-*-data"
                    })
            }
        }
    }

    return $pairs
}

function New-MysqlResolveMessage {
    param(
        [string]$Reason,
        [string[]]$Checked
    )
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add('Unable to resolve a MySQL basedir/datadir pair.')
    $lines.Add('')
    $lines.Add($Reason)
    if ($Checked -and $Checked.Count -gt 0) {
        $lines.Add('')
        $lines.Add('Locations checked:')
        foreach ($item in $Checked) {
            $lines.Add("  - $item")
        }
    }
    $lines.Add('')
    $lines.Add('Provide a matching pair using one of these (highest priority first):')
    $lines.Add('  1. Parameters: -MysqlBaseDir <portable bundle dir> [-MysqlDataDir <data dir>]')
    $lines.Add('  2. Environment: RESUMELENS_MYSQL_HOME [and RESUMELENS_MYSQL_DATA]')
    $lines.Add('  3. Auto-detect: <repo>\mysql-*-winx64\ + <repo>\data\')
    $lines.Add('     or C:\tmp\mysql-*-winx64\ + C:\tmp\mysql-*-data\ (same version family).')
    $lines.Add('basedir and datadir must be paired. Do not mix the repo bundle with the C:\tmp data directory.')
    return ($lines -join [Environment]::NewLine)
}

function Exit-MysqlResolveError {
    param(
        [string]$Reason,
        [string[]]$Checked
    )
    [Console]::Error.WriteLine((New-MysqlResolveMessage -Reason $Reason -Checked $Checked))
    exit 1
}

function Resolve-MysqlLayout {
    param(
        [string]$MysqlBaseDir,
        [string]$MysqlDataDir
    )
    $repoRoot = $PSScriptRoot
    $checked = New-Object System.Collections.Generic.List[string]

    $baseInput = $null
    $dataInput = $null
    $baseOrigin = $null
    $dataOrigin = $null

    if (-not [string]::IsNullOrWhiteSpace($MysqlBaseDir)) {
        $baseInput = Get-NormalizedPath $MysqlBaseDir
        $baseOrigin = 'parameter -MysqlBaseDir'
    }
    elseif (-not [string]::IsNullOrWhiteSpace($env:RESUMELENS_MYSQL_HOME)) {
        $baseInput = Get-NormalizedPath $env:RESUMELENS_MYSQL_HOME
        $baseOrigin = 'environment variable RESUMELENS_MYSQL_HOME'
    }

    if (-not [string]::IsNullOrWhiteSpace($MysqlDataDir)) {
        $dataInput = Get-NormalizedPath $MysqlDataDir
        $dataOrigin = 'parameter -MysqlDataDir'
    }
    elseif (-not [string]::IsNullOrWhiteSpace($env:RESUMELENS_MYSQL_DATA)) {
        $dataInput = Get-NormalizedPath $env:RESUMELENS_MYSQL_DATA
        $dataOrigin = 'environment variable RESUMELENS_MYSQL_DATA'
    }

    if ($null -ne $baseInput) {
        $checked.Add("$baseOrigin = $baseInput")
        if (-not (Test-MysqlBaseDir $baseInput)) {
            Exit-MysqlResolveError -Reason "$baseOrigin is missing or incomplete (need bin\mysqld.exe, bin\mysql.exe, bin\mysqladmin.exe): $baseInput" -Checked $checked.ToArray()
        }
        if ($null -ne $dataInput) {
            $checked.Add("$dataOrigin = $dataInput")
            if (-not (Test-MysqlDataDir $dataInput)) {
                Exit-MysqlResolveError -Reason "$dataOrigin does not exist: $dataInput" -Checked $checked.ToArray()
            }
            return [pscustomobject]@{
                Base   = $baseInput
                Data   = $dataInput
                Origin = "$baseOrigin + $dataOrigin"
            }
        }
        $pairedData = Get-PairedMysqlDataDir -BaseDir $baseInput -RepoRoot $repoRoot
        $checked.Add("paired datadir for $baseInput")
        if ($null -eq $pairedData) {
            Exit-MysqlResolveError -Reason "Found basedir but could not infer a matching datadir next to it. Pass -MysqlDataDir or set RESUMELENS_MYSQL_DATA." -Checked $checked.ToArray()
        }
        return [pscustomobject]@{
            Base   = $baseInput
            Data   = $pairedData
            Origin = "$baseOrigin + paired datadir"
        }
    }

    if ($null -ne $dataInput) {
        $checked.Add("$dataOrigin = $dataInput")
        if (-not (Test-MysqlDataDir $dataInput)) {
            Exit-MysqlResolveError -Reason "$dataOrigin does not exist: $dataInput" -Checked $checked.ToArray()
        }
        $pairedBase = Get-PairedMysqlBaseDir -DataDir $dataInput -RepoRoot $repoRoot
        $checked.Add("paired basedir for $dataInput")
        if ($null -eq $pairedBase) {
            Exit-MysqlResolveError -Reason "Found datadir but could not infer a matching basedir (mysql-*-winx64). Pass -MysqlBaseDir or set RESUMELENS_MYSQL_HOME." -Checked $checked.ToArray()
        }
        return [pscustomobject]@{
            Base   = $pairedBase
            Data   = $dataInput
            Origin = "paired basedir + $dataOrigin"
        }
    }

    $checked.Add((Join-Path $repoRoot 'mysql-*-winx64') + ' + ' + (Join-Path $repoRoot 'data'))
    $checked.Add('C:\tmp\mysql-*-winx64 + C:\tmp\mysql-*-data')
    $detected = @(Get-DetectedMysqlPairs -RepoRoot $repoRoot)
    if ($detected.Count -gt 0) {
        $chosen = $detected[0]
        return [pscustomobject]@{
            Base   = $chosen.Base
            Data   = $chosen.Data
            Origin = $chosen.Origin
        }
    }

    Exit-MysqlResolveError -Reason 'No complete basedir/datadir pair was found by auto-detect.' -Checked $checked.ToArray()
}

function Write-MysqlLayout {
    param($Layout)
    Write-Host "MySQL basedir: $($Layout.Base)"
    Write-Host "MySQL datadir: $($Layout.Data)"
    Write-Host "Resolved from: $($Layout.Origin)"
}

$layout = Resolve-MysqlLayout -MysqlBaseDir $MysqlBaseDir -MysqlDataDir $MysqlDataDir
Write-MysqlLayout $layout

$mysql = Join-Path $layout.Base 'bin\mysql.exe'
$cliArgs = @('-h', '127.0.0.1', '-P', '3306', '-u', $User)
if (-not [string]::IsNullOrEmpty($Password)) {
    $cliArgs += "-p$Password"
}
if (-not [string]::IsNullOrWhiteSpace($Database)) {
    $cliArgs += $Database
}
if (-not [string]::IsNullOrWhiteSpace($Execute)) {
    $cliArgs += '-e'
    $cliArgs += $Execute
}
& $mysql @cliArgs
exit $LASTEXITCODE
