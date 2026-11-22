# 从广州软件学院信息公开网批量抓取规章制度 PDF
# 用法：powershell -ExecutionPolicy Bypass -File fetch-gzus-pdf.ps1
# 说明：博达 CMS 的 PDF 真实地址在文章页源码里的 showVsbpdfIframe("/__local/....pdf") 中

$ErrorActionPreference = 'Stop'
$OutRoot = 'E:\tmp\campus-ai-rag\docs-src'
$Tmp     = Join-Path $env:TEMP 'gzus-page.html'
$UA      = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'

$Categories = [ordered]@{
    'xueji'   = 'https://www.gzus.edu.cn/xxgkw/ksdhlm/xsgljfwxx/xjglbf.htm'
    'jiangzhu'= 'https://www.gzus.edu.cn/xxgkw/ksdhlm/xsgljfwxx/xsjzsqyglgd.htm'
    'jiangli' = 'https://www.gzus.edu.cn/xxgkw/ksdhlm/xsgljfwxx/xsjlcfbf.htm'
    'shensu'  = 'https://www.gzus.edu.cn/xxgkw/ksdhlm/xsgljfwxx/xsssbf.htm'
}

function Get-Utf8Page([string]$Url) {
    curl.exe -s -L --max-time 30 -A $UA -o $Tmp $Url | Out-Null
    if (-not (Test-Path $Tmp)) { return '' }
    return [System.IO.File]::ReadAllText($Tmp, [System.Text.Encoding]::UTF8)
}

function Clean-Name([string]$Name) {
    $bad = [System.IO.Path]::GetInvalidFileNameChars()
    foreach ($c in $bad) { $Name = $Name.Replace([string]$c, '_') }
    $Name = $Name -replace '\s+', ' '
    if ($Name.Length -gt 60) { $Name = $Name.Substring(0, 60) }
    return $Name.Trim()
}

$summary = @()

foreach ($cat in $Categories.Keys) {
    $listUrl = $Categories[$cat]
    $dir = Join-Path $OutRoot $cat
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }

    $listHtml = Get-Utf8Page $listUrl
    $relLinks = [regex]::Matches($listHtml, 'href="([^"]*info/\d+/\d+\.htm)"') |
                ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique
    Write-Host "[$cat] 文章数: $($relLinks.Count)"

    $n = 0
    foreach ($rel in $relLinks) {
        $articleUrl = ([System.Uri]::new([System.Uri]$listUrl, $rel)).AbsoluteUri
        $page = Get-Utf8Page $articleUrl

        $title = ''
        $m = [regex]::Match($page, '<title>(.*?)</title>', 'Singleline')
        if ($m.Success) { $title = $m.Groups[1].Value -replace '[-_].*$', '' }
        if ([string]::IsNullOrWhiteSpace($title)) { $title = $cat }

        $pm = [regex]::Match($page, 'showVsbpdfIframe\("(/__local/[^"]+\.pdf)"')
        if (-not $pm.Success) { Write-Host "    - 跳过（无 PDF）: $title"; continue }

        $pdfUrl = 'https://www.gzus.edu.cn' + $pm.Groups[1].Value
        $n++
        $fileName = Clean-Name ("{0:D2}-{1}.pdf" -f $n, $title)
        $outFile = Join-Path $dir $fileName
        curl.exe -s -L --max-time 60 -A $UA -o $outFile $pdfUrl | Out-Null
        $size = if (Test-Path $outFile) { (Get-Item $outFile).Length } else { 0 }
        Write-Host ("    + {0} ({1:N0} 字节)" -f $fileName, $size)
        $summary += [pscustomobject]@{ cat = $cat; title = $title; file = $outFile; bytes = $size }
    }
}

"---"
"共下载: $($summary.Count) 个 PDF"
$summary | Format-Table cat, bytes, title -AutoSize
