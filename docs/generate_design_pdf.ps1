# Converts Glooko-Gluroo-Bridge-AWS-Design.md to print-ready HTML
$mdPath = Join-Path $PSScriptRoot "Glooko-Gluroo-Bridge-AWS-Design.md"
$htmlPath = Join-Path $PSScriptRoot "Glooko-Gluroo-Bridge-AWS-Design.html"
$pdfPath = Join-Path $PSScriptRoot "Glooko-Gluroo-Bridge-AWS-Design.pdf"

$md = Get-Content $mdPath -Raw -Encoding UTF8
$lines = $md -split "`n"
$body = [System.Text.StringBuilder]::new()

function Esc([string]$s) {
    return ($s -replace '&', '&amp;' -replace '<', '&lt;' -replace '>', '&gt;')
}

$i = 0
while ($i -lt $lines.Count) {
    $line = $lines[$i]
    if ($line -match '^\|') {
        $tableLines = @()
        while ($i -lt $lines.Count -and $lines[$i] -match '^\|') {
            $tableLines += $lines[$i]
            $i++
        }
        if ($tableLines.Count -ge 2) {
            $rows = $tableLines | Where-Object { $_ -notmatch '^\|[-:\s|]+\|$' }
            [void]$body.AppendLine('<table>')
            $r = 0
            foreach ($row in $rows) {
                $cells = ($row.Trim('|').Split('|') | ForEach-Object { $_.Trim() })
                $tag = if ($r -eq 0) { 'th' } else { 'td' }
                [void]$body.AppendLine('<tr>')
                foreach ($cell in $cells) {
                    $c = Esc($cell) -replace '\*\*([^*]+)\*\*', '<strong>$1</strong>' -replace '`([^`]+)`', '<code>$1</code>'
                    [void]$body.AppendLine("<$tag>$c</$tag>")
                }
                [void]$body.AppendLine('</tr>')
                $r++
            }
            [void]$body.AppendLine('</table>')
        }
        continue
    }

    if ($line -match '^### (.+)$') {
        [void]$body.AppendLine("<h3>$(Esc($matches[1]))</h3>")
    } elseif ($line -match '^## (.+)$') {
        [void]$body.AppendLine("<h2>$(Esc($matches[1]))</h2>")
    } elseif ($line -match '^# (.+)$') {
        [void]$body.AppendLine("<h1>$(Esc($matches[1]))</h1>")
    } elseif ($line -match '^---\s*$') {
        [void]$body.AppendLine('<hr/>')
    } elseif ($line -match '^\s*-\s+(.+)$') {
        $content = Esc($matches[1]) -replace '\*\*([^*]+)\*\*', '<strong>$1</strong>' -replace '`([^`]+)`', '<code>$1</code>'
        [void]$body.AppendLine("<ul><li>$content</li></ul>")
    } elseif ($line -match '^\d+\.\s+(.+)$') {
        $content = Esc($matches[1]) -replace '\*\*([^*]+)\*\*', '<strong>$1</strong>'
        [void]$body.AppendLine("<ol><li>$content</li></ol>")
    } elseif ($line.Trim() -eq '```') {
        $i++
        $code = [System.Text.StringBuilder]::new()
        while ($i -lt $lines.Count -and $lines[$i].Trim() -ne '```') {
            [void]$code.AppendLine((Esc $lines[$i]))
            $i++
        }
        [void]$body.AppendLine("<pre><code>$code</code></pre>")
    } elseif ($line.Trim().Length -gt 0) {
        $content = Esc($line) -replace '\*\*([^*]+)\*\*', '<strong>$1</strong>' -replace '`([^`]+)`', '<code>$1</code>' -replace '\[([^\]]+)\]\([^)]+\)', '$1'
        [void]$body.AppendLine("<p>$content</p>")
    }
    $i++
}

$html = @"
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8"/>
<title>Glooko Gluroo Bridge - AWS Migration Design Document</title>
<style>
  @page { margin: 20mm 18mm 22mm 18mm; }
  body {
    font-family: "Segoe UI", Calibri, Arial, sans-serif;
    font-size: 10.5pt;
    line-height: 1.45;
    color: #1a1a1a;
    max-width: 100%;
  }
  .cover {
    margin-top: 80px;
    margin-bottom: 40px;
    page-break-after: always;
  }
  .cover h1 { font-size: 28pt; margin-bottom: 8px; color: #111; }
  .cover .subtitle { font-size: 14pt; color: #555; margin-bottom: 32px; }
  .meta { border: 1px solid #ddd; padding: 16px 20px; background: #fafafa; width: 70%; }
  .meta td { padding: 4px 12px 4px 0; vertical-align: top; }
  .meta td:first-child { font-weight: 600; color: #444; width: 130px; }
  h1 { font-size: 18pt; margin-top: 24px; color: #111; border-bottom: 2px solid #2563eb; padding-bottom: 4px; }
  h2 { font-size: 13pt; margin-top: 20px; color: #1e3a5f; }
  h3 { font-size: 11pt; margin-top: 14px; color: #334155; }
  p { margin: 6px 0 10px; }
  ul, ol { margin: 4px 0 8px; padding-left: 0; list-style: none; }
  li { margin: 3px 0; padding-left: 14px; position: relative; }
  ul li::before { content: "•"; position: absolute; left: 0; color: #2563eb; }
  table {
    width: 100%;
    border-collapse: collapse;
    margin: 10px 0 14px;
    font-size: 9.5pt;
    page-break-inside: avoid;
  }
  th, td { border: 1px solid #ccc; padding: 6px 8px; text-align: left; vertical-align: top; }
  th { background: #eef2ff; font-weight: 600; }
  tr:nth-child(even) td { background: #fafafa; }
  code, pre { font-family: Consolas, "Courier New", monospace; font-size: 9pt; }
  pre {
    background: #f4f4f5;
    border: 1px solid #e4e4e7;
    padding: 10px;
    overflow-x: auto;
    white-space: pre-wrap;
    page-break-inside: avoid;
  }
  hr { border: none; border-top: 1px solid #ddd; margin: 16px 0; }
  .footer-note { font-size: 8pt; color: #888; margin-top: 40px; }
  h2, h3 { page-break-after: avoid; }
</style>
</head>
<body>
<div class="cover">
  <h1>Glooko Gluroo Bridge</h1>
  <div class="subtitle">AWS Migration &mdash; Design Document</div>
  <table class="meta">
    <tr><td>Author</td><td>Principal Engineering</td></tr>
    <tr><td>Audience</td><td>CTO / Engineering Leadership</td></tr>
    <tr><td>Date</td><td>July 8, 2026</td></tr>
    <tr><td>Status</td><td>Draft for Review</td></tr>
    <tr><td>Version</td><td>1.0</td></tr>
    <tr><td>Classification</td><td>Internal / Confidential</td></tr>
  </table>
</div>
$($body.ToString())
<p class="footer-note">End of document.</p>
</body>
</html>
"@

[System.IO.File]::WriteAllText($htmlPath, $html, [System.Text.UTF8Encoding]::new($false))

$chrome = "C:\Program Files\Google\Chrome\Application\chrome.exe"
if (-not (Test-Path $chrome)) {
    $chrome = "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
}

$uri = [System.Uri]::new($htmlPath).AbsoluteUri
& $chrome --headless=new --disable-gpu --no-pdf-header-footer --print-to-pdf="$pdfPath" $uri 2>&1 | Out-Null

if (Test-Path $pdfPath) {
    $size = (Get-Item $pdfPath).Length
    Write-Host "Generated PDF: $pdfPath ($size bytes)"
} else {
    Write-Error "PDF generation failed"
    exit 1
}
