# 无界面交互回归：假 Minecraft JVM + 真 agent + scratch-vm 跑 sb3。
#   powershell -ExecutionPolicy Bypass -File .\tests\vm.ps1
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root

$port = 8791
$agentJar = Join-Path $root 'java\build\mcsi-agent.jar'
if (-not (Test-Path $agentJar)) { throw '先跑 build.ps1' }

if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) {
    throw "端口 $port 已被占用，先停掉那个注入端"
}

$out = Join-Path $root 'java\build\vmhost.out'
$err = Join-Path $root 'java\build\vmhost.err'
Remove-Item $out, $err -ErrorAction SilentlyContinue

Write-Host '==> 起假 Minecraft JVM'
$hostProc = Start-Process -FilePath 'java' -ArgumentList @(
    '-cp', "`"$root\java\build\selftest;$root\java\build\mock-mojang`"", 'mcsi.MockHost'
) -PassThru -RedirectStandardOutput $out -RedirectStandardError $err -WindowStyle Hidden

$mockPid = $null
for ($i = 0; $i -lt 40; $i++) {
    Start-Sleep -Milliseconds 250
    if (Test-Path $out) {
        $line = Get-Content $out -Raw
        if ($line -match 'MockHost ready pid=(\d+)') { $mockPid = $Matches[1]; break }
    }
}
if (-not $mockPid) {
    Stop-Process -Id $hostProc.Id -Force -ErrorAction SilentlyContinue
    throw '假 MC 没起来'
}
Write-Host "    pid = $mockPid"

try {
    Write-Host '==> 注入 agent'
    & java --add-modules jdk.attach -cp (Join-Path $root 'loader\build') McAttach `
        --pid $mockPid --jar $agentJar --port $port
    if ($LASTEXITCODE -ne 0) { throw '注入失败' }

    Write-Host '==> scratch-vm 跑工程'
    & node (Join-Path $root 'tools\vmtest.js') (Join-Path $root 'ScratchMC.sb3')
    $code = $LASTEXITCODE
    Write-Host ''
    if ($code -eq 0) { Write-Host '== vm 回归全部通过 ==' } else { Write-Host "== vm 回归失败（exit $code）==" }
    exit $code
}
finally {
    Stop-Process -Id $mockPid -Force -ErrorAction SilentlyContinue
    Stop-Process -Id $hostProc.Id -Force -ErrorAction SilentlyContinue
}
