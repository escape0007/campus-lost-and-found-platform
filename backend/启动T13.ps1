$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Write-Host '未找到 Java。请安装 JDK 21 后重试。'
    exit 1
}
$jar = 'campus-lost-found-0.1.0.jar'
if (-not (Test-Path $jar)) {
    $jar = 'target\campus-lost-found-0.1.0.jar'
}
if (-not (Test-Path $jar)) {
    Write-Host '请先在 backend 目录执行 mvn clean package，或使用交付包 app 目录中的已构建版本。'
    exit 1
}
Write-Host 'T13 本地演示：启动后打开 http://127.0.0.1:8080/app/'
Write-Host '请先注册账号再登录，然后打开「提醒」。'
Write-Host '账号不代表校园身份认证。按 Ctrl+C 停止。data 文件夹会保留本地数据。'
java -jar $jar @args
