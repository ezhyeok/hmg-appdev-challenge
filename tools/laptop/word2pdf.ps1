param([string]$In, [string]$Out)
$w = New-Object -ComObject Word.Application
$w.Visible = $false
$w.DisplayAlerts = 0
try {
  $d = $w.Documents.Open($In, $false, $true)
  "pages: " + $d.ComputeStatistics(2)
  $d.ExportAsFixedFormat($Out, 17)
  $d.Close(0)
} finally { $w.Quit() }
