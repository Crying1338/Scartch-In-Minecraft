# 编译注入端（agent jar）+ attach loader + 自测用 mock。
# 用法： powershell -ExecutionPolicy Bypass -File .\build.ps1
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

$out = Join-Path $root 'java\build'
$classes = Join-Path $out 'classes'
New-Item -ItemType Directory -Force -Path $out, $classes, "$out\selftest", "$out\mock-mojang", "$out\mock-inter" | Out-Null

Write-Host "==> 编译 agent（--release 17，只用 java.base）"
$src = Get-ChildItem -Recurse -Filter *.java (Join-Path $root 'java\src') | ForEach-Object { $_.FullName }
& javac --release 17 -encoding UTF-8 -Xlint:-options -d $classes $src
if ($LASTEXITCODE -ne 0) { throw 'javac agent 失败' }

Write-Host "==> 收集 web 资源（扩展 + 演示工程）"
$web = Join-Path $classes 'web'
New-Item -ItemType Directory -Force -Path $web | Out-Null
$ext = Join-Path $root 'extension\scratch-mc-inject.js'
if (Test-Path $ext) { Copy-Item $ext (Join-Path $web 'extension.js') -Force }
$sb3 = Join-Path $root 'project\ScratchMC.sb3'
if (Test-Path $sb3) { Copy-Item $sb3 (Join-Path $web 'ScratchMC.sb3') -Force }

Write-Host "==> 打 jar（Agent-Class / Premain-Class）"
$manifest = Join-Path $out 'MANIFEST.MF'
$lines = @(
    'Manifest-Version: 1.0',
    'Agent-Class: mcsi.Agent',
    'Premain-Class: mcsi.Agent',
    'Can-Retransform-Classes: true',
    'Can-Redefine-Classes: true',
    '',
    ''
)
[System.IO.File]::WriteAllLines($manifest, $lines, (New-Object System.Text.UTF8Encoding($false)))

$jarPath = Join-Path $out 'mcsi-agent.jar'
if (Test-Path $jarPath) { Remove-Item $jarPath -Force }
& jar --create --file $jarPath --manifest $manifest -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'jar 打包失败' }

Write-Host "==> 编译 attach loader"
$loaderOut = Join-Path $root 'loader\build'
New-Item -ItemType Directory -Force -Path $loaderOut | Out-Null
& javac --release 17 --add-modules jdk.attach -encoding UTF-8 -Xlint:-options -d $loaderOut (Join-Path $root 'loader\McAttach.java')
if ($LASTEXITCODE -ne 0) { throw 'javac loader 失败' }

Write-Host "==> 编译自测 + mock"
$test = Get-ChildItem -Recurse -Filter *.java (Join-Path $root 'java\selftest') | ForEach-Object { $_.FullName }
& javac --release 17 -encoding UTF-8 -Xlint:-options -d "$out\selftest" ($src + $test)
if ($LASTEXITCODE -ne 0) { throw 'javac 自测失败' }
& javac --release 17 -encoding UTF-8 -Xlint:-options -d "$out\mock-mojang" (Get-ChildItem -Recurse -Filter *.java (Join-Path $root 'java\mock-mojang') | ForEach-Object { $_.FullName })
if ($LASTEXITCODE -ne 0) { throw 'javac mock-mojang 失败' }
& javac --release 17 -encoding UTF-8 -Xlint:-options -d "$out\mock-inter" (Get-ChildItem -Recurse -Filter *.java (Join-Path $root 'java\mock-inter') | ForEach-Object { $_.FullName })
if ($LASTEXITCODE -ne 0) { throw 'javac mock-inter 失败' }

Write-Host ''
Write-Host '构建完成：'
Write-Host "  agent : $jarPath"
Write-Host "  loader: $loaderOut\McAttach.class"
Write-Host '  注入  : java --add-modules jdk.attach -cp loader\build McAttach --pid <游戏PID> --jar java\build\mcsi-agent.jar'
