# 端到端：真的 attach 进一个 "假 Minecraft JVM"，再从 HTTP 端验证。
#   powershell -ExecutionPolicy Bypass -File .\tests\e2e.ps1
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root

$port = 8791
$agentJar = Join-Path $root 'java\build\mcsi-agent.jar'
$loaderCp = Join-Path $root 'loader\build'
$hostCp = "$root\java\build\selftest;$root\java\build\mock-mojang"

if (-not (Test-Path $agentJar)) { throw "先跑 build.ps1（缺 $agentJar）" }

$out = Join-Path $root 'java\build\mockhost.out'
$err = Join-Path $root 'java\build\mockhost.err'
Remove-Item $out, $err -ErrorAction SilentlyContinue

Write-Host "==> 起假 Minecraft 进程"
$host1 = Start-Process -FilePath 'java' -ArgumentList @('-cp', "`"$hostCp`"", 'mcsi.MockHost') `
    -PassThru -RedirectStandardOutput $out -RedirectStandardError $err -WindowStyle Hidden

$targetPid = $null
for ($i = 0; $i -lt 40; $i++) {
    Start-Sleep -Milliseconds 250
    if (Test-Path $out) {
        $line = Get-Content $out -Raw
        if ($line -match 'MockHost ready pid=(\d+)') { $targetPid = $Matches[1]; break }
    }
}
if (-not $targetPid) {
    Get-Content $out, $err -ErrorAction SilentlyContinue
    Stop-Process -Id $host1.Id -Force -ErrorAction SilentlyContinue
    throw '假 MC 没起来'
}
Write-Host "    目标 pid = $targetPid"

$fails = 0
function Check($name, $cond, $detail) {
    if ($cond) { Write-Host "[PASS] $name" }
    else { Write-Host "[FAIL] $name  ($detail)"; $script:fails++ }
}

try {
    Write-Host "==> attach 注入"
    $attach = & java --add-modules jdk.attach -cp $loaderCp McAttach --pid $targetPid --jar $agentJar --port $port 2>&1
    $attach | ForEach-Object { Write-Host "    $_" }
    Check 'attach 无异常' ($LASTEXITCODE -eq 0) "exit=$LASTEXITCODE"

    Write-Host "==> 等注入端就绪"
    $status = $null
    for ($i = 0; $i -lt 40; $i++) {
        Start-Sleep -Milliseconds 250
        try {
            $status = Invoke-RestMethod -Uri "http://127.0.0.1:$port/status" -TimeoutSec 2
            if ($status.ok) { break }
        } catch { $status = $null }
    }
    Check 'HTTP /status 可用' ($null -ne $status) '没连上注入端'
    if (-not $status) { throw '注入端没起来' }

    Check '识别到玩家 Steve' ($status.game.player -eq 'Steve') $status.game.player
    Check '映射已解析' ($status.game.ready -eq $true) $status.game.fail
    Check '能写视角' ($status.game.canAim -eq $true) ''
    Check '能攻击' ($status.game.canAttack -eq $true) ''
    Check '实体数 = 4' ($status.game.entities -eq 4) $status.game.entities

    $ents = Invoke-RestMethod -Uri "http://127.0.0.1:$port/entities?range=32&filter=all&limit=10" -TimeoutSec 3
    Check '/entities 返回 4 个' ($ents.count -eq 4) $ents.count

    $look = Invoke-RestMethod -Uri "http://127.0.0.1:$port/look" -Method Post -ContentType 'application/json' `
        -Body '{"yaw":123,"pitch":-10}' -TimeoutSec 3
    Check '/look ok' ($look.ok -eq $true) $look.note
    $player = Invoke-RestMethod -Uri "http://127.0.0.1:$port/player" -TimeoutSec 3
    Check '/look 真的改了朝向' ([math]::Abs($player.rot[0] - 123) -lt 0.01) ($player.rot -join '/')

    $aim = Invoke-RestMethod -Uri "http://127.0.0.1:$port/module" -Method Post -ContentType 'application/json' `
        -Body '{"name":"aim","on":true}' -TimeoutSec 3
    Check '/module 开自瞄' ($aim.modules.aim -eq $true) $aim.note

    $par = Invoke-RestMethod -Uri "http://127.0.0.1:$port/params" -Method Post -ContentType 'application/json' `
        -Body '{"key":"fov","value":"179"}' -TimeoutSec 3
    Check '/params 改 FOV' ($par.params.fov -eq 179) $par.note

    # 自瞄速度调快（默认 300°/s 在 400ms 内转不完 150°）
    $null = Invoke-RestMethod -Uri "http://127.0.0.1:$port/params" -Method Post -ContentType 'application/json' `
        -Body '{"key":"speed","value":"900"}' -TimeoutSec 3
    $null = Invoke-RestMethod -Uri "http://127.0.0.1:$port/params" -Method Post -ContentType 'application/json' `
        -Body '{"key":"step","value":"30"}' -TimeoutSec 3

    Start-Sleep -Milliseconds 600
    $tgt = Invoke-RestMethod -Uri "http://127.0.0.1:$port/target" -TimeoutSec 3
    Check '自瞄锁到目标' ($tgt.target.locked -eq $true) $tgt.target.name
    Check '自瞄把视角转过去了' ([math]::Abs($tgt.target.dyaw) -lt 12) $tgt.target.dyaw

    $atk = Invoke-RestMethod -Uri "http://127.0.0.1:$port/attack" -Method Post -ContentType 'application/json' `
        -Body '{}' -TimeoutSec 3
    Check '/attack 成功' ($atk.ok -eq $true) $atk.note

    $maps = Invoke-RestMethod -Uri "http://127.0.0.1:$port/mappings" -TimeoutSec 3
    Check '/mappings 有报告' ($maps.report -match 'Entity 类') ''

    $extJs = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$port/extension.js" -TimeoutSec 3
    Check '/extension.js 可取（TurboWarp 从 URL 加载扩展）' ($extJs.Content -match 'scratchMC') $extJs.StatusCode
    Check '带 PNA 头（Chrome 网页版 TurboWarp 才连得上）' ($extJs.Headers['Access-Control-Allow-Private-Network'] -eq 'true') $extJs.Headers['Access-Control-Allow-Private-Network']

    $sb3res = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$port/project/ScratchMC.sb3" -TimeoutSec 5
    Check '/project/ScratchMC.sb3 可取' ($sb3res.RawContentLength -gt 8000) $sb3res.RawContentLength

    $null = Invoke-RestMethod -Uri "http://127.0.0.1:$port/shutdown" -Method Post -ContentType 'application/json' `
        -Body '{}' -TimeoutSec 3
    Start-Sleep -Milliseconds 300
    $down = $false
    try { $null = Invoke-RestMethod -Uri "http://127.0.0.1:$port/status" -TimeoutSec 1 } catch { $down = $true }
    Check '/shutdown 真的停了' $down ''
}
finally {
    Stop-Process -Id $targetPid -Force -ErrorAction SilentlyContinue
    Stop-Process -Id $host1.Id -Force -ErrorAction SilentlyContinue
    Write-Host "日志：$out / $err"
}

Write-Host ''
if ($fails -eq 0) { Write-Host '== e2e 全部通过 ==' } else { Write-Host "== e2e 失败 $fails 项 ==" ; exit 1 }
