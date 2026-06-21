# 下载本地中文 embedding 模型（bge-small-zh-v1.5 ONNX 版，约 95MB）
# 用法: pwsh -ExecutionPolicy Bypass -File scripts/download-model.ps1
# 来源: hf-mirror 镜像（DeepSeek 无 embedding API，本地模型零成本）
$ErrorActionPreference = 'Stop'
$dir = Join-Path $PSScriptRoot '..\models\onnx'
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$base = 'https://hf-mirror.com/Xenova/bge-small-zh-v1.5/resolve/main'
# model.onnx 在 onnx/ 子目录；tokenizer.json 在仓库根目录（实测路径，勿改）
curl.exe -sL -o (Join-Path $dir 'model.onnx') "$base/onnx/model.onnx" --retry 3 --retry-all-errors --max-time 900
if ($LASTEXITCODE -ne 0) { throw 'model.onnx 下载失败' }
curl.exe -sL -o (Join-Path $dir 'tokenizer.json') "$base/tokenizer.json" --retry 3 --retry-all-errors --max-time 300
if ($LASTEXITCODE -ne 0) { throw 'tokenizer.json 下载失败' }
Get-ChildItem $dir | ForEach-Object { Write-Host ($_.Name + ' ' + [math]::Round($_.Length/1MB,1) + 'MB') }
Write-Host '模型就绪。'
