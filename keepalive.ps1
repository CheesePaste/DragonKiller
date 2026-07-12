  while ($true) {
      $wshell = New-Object -ComObject WScript.Shell
      $wshell.SendKeys('{F15}')
      Start-Sleep -Seconds 30
  }