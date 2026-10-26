param(
    [Parameter(Mandatory = $true)][string]$ClientJar,
    [string]$Directory = 'build/reports/login-ui'
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.Drawing

# Render exported View records with the installed client's own font and widget assets.
# This is a layout preview, not a screenshot of a running Minecraft client.
# Minecraft assets stay in memory and are never bundled with the plugin or sources.
$glyphs = @{}
$tints = @{}
$images = [System.Collections.Generic.List[System.Drawing.Bitmap]]::new()
$palette = @('000000','0000aa','00aa00','00aaaa','aa0000','aa00aa','ffaa00','aaaaaa',
    '555555','5555ff','55ff55','55ffff','ff5555','ff55ff','ffff55','ffffff')

function Read-JarText($archive, [string]$name) {
    $reader = [System.IO.StreamReader]::new($archive.GetEntry($name).Open())
    try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
}

function Read-JarBitmap($archive, [string]$name) {
    $stream = $archive.GetEntry($name).Open()
    try {
        $original = [System.Drawing.Bitmap]::new($stream)
        try { $copy = [System.Drawing.Bitmap]::new($original) } finally { $original.Dispose() }
        $images.Add($copy)
        return $copy
    } finally { $stream.Dispose() }
}

function Code-Points([string]$text) {
    $points = [System.Collections.Generic.List[int]]::new()
    for ($index = 0; $index -lt $text.Length; $index++) {
        $points.Add([char]::ConvertToUtf32($text, $index))
        if ([char]::IsHighSurrogate($text[$index])) { $index++ }
    }
    return ,$points.ToArray()
}

$archive = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $ClientJar))
try {
    $font = (Read-JarText $archive 'assets/minecraft/font/include/default.json') | ConvertFrom-Json
    foreach ($provider in $font.providers | Where-Object { $_.type -eq 'bitmap' }) {
        $imagePath = 'assets/minecraft/textures/' + ($provider.file -replace '^minecraft:', '')
        $bitmap = Read-JarBitmap $archive $imagePath
        $cellWidth = [int]($bitmap.Width / (Code-Points $provider.chars[0]).Count)
        $cellHeight = [int]($bitmap.Height / $provider.chars.Count)
        $height = if ($provider.height) { [int]$provider.height } else { 8 }
        $ratio = $height / $cellHeight
        for ($row = 0; $row -lt $provider.chars.Count; $row++) {
            $rowCharacters = Code-Points $provider.chars[$row]
            for ($column = 0; $column -lt $rowCharacters.Count; $column++) {
                $character = $rowCharacters[$column]
                if ($character -eq 0 -or $glyphs.ContainsKey($character)) { continue }
                $right = -1
                for ($x = 0; $x -lt $cellWidth; $x++) {
                    for ($y = 0; $y -lt $cellHeight; $y++) {
                        if ($bitmap.GetPixel($column * $cellWidth + $x, $row * $cellHeight + $y).A -gt 0) { $right = $x; break }
                    }
                }
                $glyphs[$character] = @{
                    Bitmap = $bitmap; X = $column * $cellWidth; Y = $row * $cellHeight
                    CellWidth = $cellWidth; CellHeight = $cellHeight; Height = $height
                    Width = [int][Math]::Ceiling($cellWidth * $ratio)
                    Advance = [int][Math]::Floor(($right + 1) * $ratio + 0.5) + 1
                    Ascent = [int]$provider.ascent
                }
            }
        }
    }
    $button = Read-JarBitmap $archive 'assets/minecraft/textures/gui/sprites/widget/button.png'
    $disabled = Read-JarBitmap $archive 'assets/minecraft/textures/gui/sprites/widget/button_disabled.png'
} finally { $archive.Dispose() }

function Text-Units([string]$text) {
    $units = [System.Collections.Generic.List[object]]::new()
    $color = 'ffffff'
    $bold = $false
    for ($index = 0; $index -lt $text.Length; $index++) {
        $character = $text[$index]
        if ($character -eq '§' -and $index + 1 -lt $text.Length) {
            $index++
            $code = [char]::ToLowerInvariant($text[$index])
            $colorIndex = '0123456789abcdef'.IndexOf($code)
            if ($colorIndex -ge 0) { $color = $palette[$colorIndex]; $bold = $false }
            elseif ($code -eq 'l') { $bold = $true }
            elseif ($code -eq 'r') { $color = 'ffffff'; $bold = $false }
            continue
        }
        $glyph = $glyphs[[int]$character]
        if ($null -eq $glyph) { $glyph = $glyphs[[int][char]'?'] }
        $width = if ($character -eq ' ') { 4 } else { $glyph.Advance + [int]$bold }
        $units.Add(@{ Character = $character; Glyph = $glyph; Color = $color; Bold = $bold; Width = $width })
    }
    return ,$units.ToArray()
}

function Wrapped-Rows([string]$text, [int]$maxWidth) {
    $rows = [System.Collections.Generic.List[object]]::new()
    $current = [System.Collections.Generic.List[object]]::new()
    $width = 0
    foreach ($unit in (Text-Units $text)) {
        if ($unit.Character -eq "`n") {
            $rows.Add(@{ Units = $current.ToArray() })
            $current.Clear(); $width = 0
            continue
        }
        if ($width + $unit.Width -gt $maxWidth -and $current.Count -gt 0) {
            $space = -1
            for ($index = 0; $index -lt $current.Count; $index++) { if ($current[$index].Character -eq ' ') { $space = $index } }
            if ($space -ge 0) {
                $rows.Add(@{ Units = $current.GetRange(0, $space).ToArray() })
                $current = $current.GetRange($space + 1, $current.Count - $space - 1)
            } else { $rows.Add(@{ Units = $current.ToArray() }); $current.Clear() }
            $width = 0
            foreach ($remaining in $current) { $width += $remaining.Width }
        }
        $current.Add($unit)
        $width += $unit.Width
    }
    $rows.Add(@{ Units = $current.ToArray() })
    return ,$rows.ToArray()
}

function Draw-Units($graphics, $units, [int]$center, [int]$top) {
    $width = 0
    foreach ($unit in $units) { $width += $unit.Width }
    $cursor = [int][Math]::Floor($center - $width / 2)
    foreach ($unit in $units) {
        if ($unit.Character -ne ' ') {
            $key = [string][int]$unit.Character + '/' + $unit.Color
            if (-not $tints.ContainsKey($key)) {
                $glyph = $unit.Glyph
                $colored = [System.Drawing.Bitmap]::new($glyph.CellWidth, $glyph.CellHeight)
                $color = [System.Drawing.ColorTranslator]::FromHtml('#' + $unit.Color)
                for ($x = 0; $x -lt $glyph.CellWidth; $x++) {
                    for ($y = 0; $y -lt $glyph.CellHeight; $y++) {
                        $alpha = $glyph.Bitmap.GetPixel($glyph.X + $x, $glyph.Y + $y).A
                        $colored.SetPixel($x, $y, [System.Drawing.Color]::FromArgb($alpha, $color))
                    }
                }
                $tints[$key] = $colored
                $images.Add($colored)
            }
            $glyph = $unit.Glyph
            $rectangle = [System.Drawing.Rectangle]::new($cursor, $top + 7 - $glyph.Ascent, $glyph.Width, $glyph.Height)
            $graphics.DrawImage($tints[$key], $rectangle)
            if ($unit.Bold) { $rectangle.X++; $graphics.DrawImage($tints[$key], $rectangle) }
        }
        $cursor += $unit.Width
    }
}

function Draw-Button($graphics, $model, [int]$left, [int]$top, [int]$width) {
    $sprite = if ($model.action -eq 'NONE') { $disabled } else { $button }
    # Match the client's nine-slice widget scaling; the border remains three pixels wide.
    $sourceWidths = @(3, ($sprite.Width - 6), 3)
    $targetWidths = @(3, ($width - 6), 3)
    $sourceHeights = @(3, ($sprite.Height - 6), 3)
    $targetHeights = @(3, 14, 3)
    $sourceY = 0; $targetY = $top
    for ($row = 0; $row -lt 3; $row++) {
        $sourceX = 0; $targetX = $left
        for ($column = 0; $column -lt 3; $column++) {
            $destination = [System.Drawing.Rectangle]::new($targetX, $targetY, $targetWidths[$column], $targetHeights[$row])
            $source = [System.Drawing.Rectangle]::new($sourceX, $sourceY, $sourceWidths[$column], $sourceHeights[$row])
            $graphics.DrawImage($sprite, $destination, $source, [System.Drawing.GraphicsUnit]::Pixel)
            $sourceX += $sourceWidths[$column]; $targetX += $targetWidths[$column]
        }
        $sourceY += $sourceHeights[$row]; $targetY += $targetHeights[$row]
    }
    Draw-Units $graphics (Text-Units $model.label) ($left + [int]($width / 2)) ($top + 6)
}

try {
    foreach ($file in Get-ChildItem -LiteralPath $Directory -Filter '*.json') {
        $view = Get-Content -LiteralPath $file.FullName -Raw | ConvertFrom-Json
        $canvas = [System.Drawing.Bitmap]::new(320, 240)
        $graphics = [System.Drawing.Graphics]::FromImage($canvas)
        $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
        $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
        $graphics.Clear([System.Drawing.Color]::FromArgb(17, 23, 30))
        Draw-Units $graphics (Text-Units $view.title) 148 13
        Draw-Button $graphics @{ action = 'WARNING'; label = '§e!' } 224 7 20
        $rows = [System.Collections.Generic.List[object]]::new()
        foreach ($line in $view.lines) { foreach ($row in (Wrapped-Rows $line $view.layout.bodyWidth)) { $rows.Add($row) } }
        $columns = [int]$view.layout.columns
        $gridRows = [int][Math]::Ceiling($view.buttons.Count / $columns)
        $gridHeight = if ($gridRows -gt 0) { $gridRows * 22 - 2 } else { 0 }
        $contentHeight = $rows.Count * 9 + $(if ($gridRows -gt 0) { 10 + $gridHeight } else { 0 }) + $(if ($view.textEntry) { 30 } else { 0 })
        if ($contentHeight -gt 174) { throw "Layout would scroll: $($file.Name) ($contentHeight pixels)." }
        $top = 33 + [int][Math]::Floor((174 - $contentHeight) / 2)
        foreach ($row in $rows) { Draw-Units $graphics $row.Units 160 $top; $top += 9 }
        if ($view.textEntry) {
            $left = [int]((320 - $view.layout.bodyWidth) / 2)
            $graphics.FillRectangle([System.Drawing.Brushes]::Gray, $left, $top + 10, [int]$view.layout.bodyWidth, 20)
            $graphics.FillRectangle([System.Drawing.Brushes]::Black, $left + 1, $top + 11, [int]$view.layout.bodyWidth - 2, 18)
            Draw-Units $graphics (Text-Units $view.initialText) 160 ($top + 16)
            $top += 30
        }
        $top += 10
        $gridWidth = $columns * $view.layout.buttonWidth + ($columns - 1) * 2
        for ($index = 0; $index -lt $view.buttons.Count; $index++) {
            $rowWidth = $gridWidth
            $remaining = $view.buttons.Count % $columns
            if ($remaining -gt 0 -and $index -ge $view.buttons.Count - $remaining) {
                $rowWidth = $remaining * $view.layout.buttonWidth + ($remaining - 1) * 2
            }
            $left = [int]((320 - $rowWidth) / 2) + ($index % $columns) * ($view.layout.buttonWidth + 2)
            Draw-Button $graphics $view.buttons[$index] $left ($top + [int][Math]::Floor($index / $columns) * 22) $view.layout.buttonWidth
        }
        if ($view.primary) { $width = [Math]::Max(100, $gridWidth); Draw-Button $graphics $view.primary ([int]((320 - $width) / 2)) 213 $width }
        $graphics.Dispose()
        $scaled = [System.Drawing.Bitmap]::new(960, 720)
        $output = [System.Drawing.Graphics]::FromImage($scaled)
        $output.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
        $output.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
        $output.DrawImage($canvas, [System.Drawing.Rectangle]::new(0, 0, 960, 720))
        $output.Dispose()
        $path = [System.IO.Path]::ChangeExtension($file.FullName, '.png')
        $scaled.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
        $scaled.Dispose(); $canvas.Dispose()
    }
    Write-Output "Rendered dialog layout previews in $Directory using vanilla client assets."
} finally { foreach ($image in $images) { $image.Dispose() } }
