# 本地 PostgreSQL(zip版) + pgvector 一键初始化脚本
# 用法: pwsh -ExecutionPolicy Bypass -File scripts/init-pg.ps1
# 输出: 数据库 shortdrama 就绪；连接信息写入项目 .env（已被 .gitignore 排除，不进 git）

$ErrorActionPreference = 'Stop'

$projectDir = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $projectDir '.env'
$pgHome = Join-Path $env:USERPROFILE 'pgsql'
$pgInstall = Join-Path $pgHome '17'
$pgData = Join-Path $pgHome 'data'
$port = 5432

# ---------- 1. 准备安装目录 ----------
New-Item -ItemType Directory -Force -Path $pgHome | Out-Null
$pgZip = Join-Path $env:USERPROFILE 'Downloads\postgresql-17.7-1-windows-x64-binaries.zip'
if (-not (Test-Path $pgZip)) { throw "未找到 PG zip 包: $pgZip" }
if (-not (Test-Path (Join-Path $pgInstall 'bin\initdb.exe'))) {
    Write-Host "[1/5] 解压 PostgreSQL zip 到 $pgInstall ..."
    Expand-Archive -Path $pgZip -DestinationPath $pgInstall -Force
    # zip 内是 pgsql/ 子目录，若存在则平铺
    if (Test-Path (Join-Path $pgInstall 'pgsql\bin\initdb.exe')) {
        Get-ChildItem (Join-Path $pgInstall 'pgsql') | Move-Item -Destination $pgInstall -Force
        Remove-Item (Join-Path $pgInstall 'pgsql') -Recurse -Force -ErrorAction SilentlyContinue
    }
}

$bin = Join-Path $pgInstall 'bin'
if (-not (Test-Path (Join-Path $bin 'initdb.exe'))) { throw "PG 解压失败: $bin 下没有 initdb.exe" }

# ---------- 2. pgvector 扩展 ----------
$vecZip = Join-Path $env:USERPROFILE 'Downloads\vector.v0.8.6-pg17.zip'
if (-not (Test-Path $vecZip)) { throw "未找到 pgvector zip 包: $vecZip" }
$extDir = Join-Path $pgInstall 'share\extension'
if (-not (Test-Path (Join-Path $extDir 'vector.control'))) {
    Write-Host "[2/5] 安装 pgvector 0.8.6 扩展 ..."
    $tmp = Join-Path $env:TEMP 'pgvector-extract'
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
    Expand-Archive -Path $vecZip -DestinationPath $tmp -Force
    # zip 结构: 顶层 lib/ share/ include/
    Copy-Item (Join-Path $tmp 'lib\vector.dll') (Join-Path $pgInstall 'lib') -Force
    Copy-Item (Join-Path $tmp 'share\extension\*') $extDir -Force
}

# ---------- 3. 读取/生成数据库密码 ----------
$dbPassword = $null
if (Test-Path $envFile) {
    $m = Select-String -Path $envFile -Pattern '^DB_PASSWORD=(.+)$'
    if ($m) { $dbPassword = $m.Groups[1].Value }
}
if (-not $dbPassword) {
    $dbPassword = -join ((48..57) + (65..90) + (97..122) | Get-Random -Count 20 | ForEach-Object { [char]$_ })
}

# ---------- 4. initdb + 启动 ----------
if (-not (Test-Path (Join-Path $pgData 'PG_VERSION'))) {
    Write-Host "[3/5] initdb 初始化数据目录 $pgData ..."
    $pwdFile = Join-Path $env:TEMP 'pg-pwd.txt'
    Set-Content -Path $pwdFile -Value $dbPassword -NoNewline -Encoding ascii
    & (Join-Path $bin 'initdb.exe') -D $pgData -U postgres --encoding=UTF8 --auth=scram-sha-256 --pwfile=$pwdFile 2>&1 | Out-Host
    Remove-Item $pwdFile -Force
}

# 已运行则跳过；否则启动（用 pg_ctl status 判断，端口检测留给 OS 处理）
& (Join-Path $bin 'pg_ctl.exe') -D $pgData status *> $null
if ($LASTEXITCODE -ne 0) {
    Write-Host "[4/5] 启动 PostgreSQL (port $port) ..."
    & (Join-Path $bin 'pg_ctl.exe') -D $pgData -l (Join-Path $pgHome 'pg.log') -o "-p $port" start | Out-Host
    Start-Sleep -Seconds 3
}

# ---------- 5. 建库 + 启用 pgvector ----------
$psql = Join-Path $bin 'psql.exe'
$env:PGPASSWORD = $dbPassword
Write-Host "[5/5] 创建数据库 shortdrama 并启用 vector 扩展 ..."
$exists = & $psql -w -h 127.0.0.1 -p $port -U postgres -tAc "SELECT 1 FROM pg_database WHERE datname='shortdrama'" 2>&1
if ($LASTEXITCODE -ne 0) { throw "psql 连接失败: $exists" }
if ("$exists".Trim() -ne '1') {
    & $psql -w -h 127.0.0.1 -p $port -U postgres -c "CREATE DATABASE shortdrama ENCODING 'UTF8'" | Out-Host
    if ($LASTEXITCODE -ne 0) { throw 'CREATE DATABASE 失败' }
}
& $psql -w -h 127.0.0.1 -p $port -U postgres -d shortdrama -c "CREATE EXTENSION IF NOT EXISTS vector" | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'CREATE EXTENSION vector 失败' }

# ---------- 6. 写入 .env ----------
Write-Host "写入 .env（含 DB 连接信息，不进 git）..."
$existing = ''
if (Test-Path $envFile) { $existing = Get-Content $envFile -Raw }
$lines = @(
    "DB_URL=jdbc:postgresql://127.0.0.1:$port/shortdrama",
    "DB_USERNAME=postgres",
    "DB_PASSWORD=$dbPassword"
)
$new = ($existing.TrimEnd("`r`n") + "`n" + ($lines -join "`n") + "`n")
Set-Content -Path $envFile -Value $new -Encoding utf8

Write-Host ""
Write-Host "完成！PostgreSQL $port 端口已启动，库 shortdrama + vector 扩展就绪。"
& $psql -h 127.0.0.1 -p $port -U postgres -d shortdrama -c "SELECT extversion FROM pg_extension WHERE extname='vector'"
