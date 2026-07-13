# 查找并强制关闭所有与 Gradle 运行 Minecraft 相关的 java 进程
Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" | Where-Object { $_.CommandLine -match "fabric" -or $_.CommandLine -match "gradle" } | Invoke-CimMethod -MethodName Terminate

Write-Host "✅ 已清理所有后台驻留的 Minecraft 和 Gradle 进程！" -ForegroundColor Green
