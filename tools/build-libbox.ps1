# Builds app/libs/libbox.aar (the sing-box core for Android) from the official sing-box sources.
#
# Needs: Go (the version sing-box's official builds use), Android SDK + NDK, OpenJDK 17,
#        gomobile/gobind from github.com/sagernet/gomobile (the version in sing-box's go.mod).
# Usage: powershell -File tools\build-libbox.ps1 -Version 1.14.2
param(
    [string]$Version = "1.14.2",
    [string]$Go = "$env:USERPROFILE\sdk\go1.26.8",
    [string]$Ndk = "$env:LOCALAPPDATA\Android\Sdk\ndk\30.0.16248370",
    [string]$Src = "$env:USERPROFILE\sdk\src\sing-box-$Version"
)
$ErrorActionPreference = "Stop"
$git = (Get-Command git -ErrorAction SilentlyContinue).Source
if (-not $git) { $git = "$env:LOCALAPPDATA\GitHubDesktop\app-3.6.6\resources\app\git\cmd\git.exe" }

if (-not (Test-Path $Src)) {
    & $git -c advice.detachedHead=false clone -q --depth 1 --branch "v$Version" https://github.com/SagerNet/sing-box.git $Src
}
# A failed earlier run leaves files that make gomobile stop with "file exists"
if (Test-Path "$Src\build") { [IO.Directory]::Delete("$Src\build", $true) }

$out = Join-Path $PSScriptRoot "..\app\libs\libbox.aar"
$psi = New-Object Diagnostics.ProcessStartInfo
$psi.FileName = "$env:USERPROFILE\go\bin\gomobile.exe"
$psi.WorkingDirectory = $Src
$psi.UseShellExecute = $false
# Passed as one string: PowerShell 5.1 would split "-javapkg=io.nekohasekai" at the dots
$psi.Arguments = 'bind -o "' + $out + '" -target android/arm64,android/arm,android/amd64 -androidapi 24 ' +
    '-javapkg=io.nekohasekai -libname=box -trimpath -buildvcs=false ' +
    '-ldflags "-X github.com/sagernet/sing-box/constant.Version=' + $Version + ' -X runtime.godebugDefault=multipathtcp=0,tlssha1=1 -checklinkname=0 -s -w -buildid=" ' +
    '-tags with_gvisor,with_quic,with_utls,with_clash_api,badlinkname,tfogo_checklinkname0 ./experimental/libbox'
# gomobile cannot parse the hidden "=C:" variables cmd.exe adds to the environment
foreach ($k in @($psi.EnvironmentVariables.Keys)) { if ($k.StartsWith("=")) { $psi.EnvironmentVariables.Remove($k) } }
$psi.EnvironmentVariables["GOROOT"] = $Go
$psi.EnvironmentVariables["GOTOOLCHAIN"] = "local"
$psi.EnvironmentVariables["ANDROID_HOME"] = "$env:LOCALAPPDATA\Android\Sdk"
$psi.EnvironmentVariables["ANDROID_NDK_HOME"] = $Ndk
$psi.EnvironmentVariables["PATH"] = "$Go\bin;$env:USERPROFILE\go\bin;" + $env:PATH

$p = [Diagnostics.Process]::Start($psi)
$p.WaitForExit()
if ($p.ExitCode -ne 0) { throw "gomobile bind failed ($($p.ExitCode))" }
Remove-Item (Join-Path $PSScriptRoot "..\app\libs\libbox-sources.jar") -ErrorAction SilentlyContinue
"libbox.aar: {0:N1} MB" -f ((Get-Item $out).Length / 1MB)
