# Loot Gobbler in-app update, started hidden by lootgobbler.Updater (which then closes the app).
# Waits for Loot Gobbler to exit, moves the old program files to update\old, moves the new ones from update\new
# into the app folder, and starts Loot Gobbler again. On any error the old files are put back.
# Only names that exist in the new version are touched, so the user's data files stay where they are.
param([int]$ProcessId, [string]$AppDir, [string]$NewDir)

$ErrorActionPreference = 'Stop'
$updateDir = Split-Path -Parent $NewDir
$oldDir = Join-Path $updateDir 'old'
$log = Join-Path $updateDir 'update.log'
$exe = Join-Path $AppDir 'LootGobbler.exe'

function Log([string]$text) {
    Add-Content -LiteralPath $log -Value ((Get-Date -Format s) + ' ' + $text)
}

function Restore([string[]]$names) {
    foreach ($n in $names) {
        $saved = Join-Path $oldDir $n
        if (-not (Test-Path -LiteralPath $saved)) { continue }
        $target = Join-Path $AppDir $n
        if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Recurse -Force }
        Move-Item -LiteralPath $saved -Destination $target
    }
}

Log "Updating $AppDir"
try { Wait-Process -Id $ProcessId -Timeout 120 -ErrorAction SilentlyContinue } catch { }
if (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue) {
    Log 'Loot Gobbler did not close, update cancelled'
    exit 1
}

$names = @(Get-ChildItem -LiteralPath $NewDir | ForEach-Object { $_.Name })

# 1. move the old program files out of the way (files can stay locked for a moment after the exit, so retry)
$movedOut = $false
for ($attempt = 1; $attempt -le 15 -and -not $movedOut; $attempt++) {
    $moved = @()
    try {
        if (Test-Path -LiteralPath $oldDir) { Remove-Item -LiteralPath $oldDir -Recurse -Force }
        New-Item -ItemType Directory -Path $oldDir | Out-Null
        foreach ($n in $names) {
            $target = Join-Path $AppDir $n
            if (Test-Path -LiteralPath $target) {
                Move-Item -LiteralPath $target -Destination (Join-Path $oldDir $n)
                $moved += $n
            }
        }
        $movedOut = $true
    } catch {
        Log "Attempt ${attempt}: $_"
        try { Restore $moved } catch { Log "Restore after attempt ${attempt} failed: $_" }
        Start-Sleep -Seconds 1
    }
}

# 2. move the new version in
$updated = $false
if ($movedOut) {
    try {
        foreach ($n in $names) {
            Move-Item -LiteralPath (Join-Path $NewDir $n) -Destination (Join-Path $AppDir $n)
        }
        $updated = $true
        Log 'Updated'
    } catch {
        Log "Install failed, restoring the old version: $_"
        try { Restore $names } catch { Log "Restore failed: $_" }
    }
} else {
    Log 'Could not move the old version away, update cancelled'
}

# 3. start Loot Gobbler again (new or old) and clean up
Start-Process -FilePath $exe -WorkingDirectory $AppDir
if ($updated) {
    Start-Sleep -Seconds 3
    foreach ($p in @($oldDir, $NewDir, (Join-Path $updateDir 'LootGobbler-portable.zip'))) {
        try { Remove-Item -LiteralPath $p -Recurse -Force } catch { Log "Cleanup of ${p}: $_" }
    }
}
