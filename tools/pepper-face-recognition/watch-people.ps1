# Watch People - Live Face Tracking Monitor for Pepper
# Deploys scripts, restarts server, and monitors tracking
# Usage: .\watch-people.ps1 [-Deploy] [-Ip "10.95.65.123"] [-Interval 1]

param(
    [switch]$Deploy,           # Deploy scripts and restart server
    [switch]$Restart,          # Just restart server (no deploy)
    [string]$Ip = "10.95.65.123",
    [string]$User = "nao",
    [int]$Interval = 1
)

$RemotePath = "/home/nao/face_data"
$ScriptsDir = $PSScriptRoot + "\scripts"

function Deploy-Scripts {
    Write-Host "`n=== Deploying Scripts ===" -ForegroundColor Cyan
    
    $files = @(
        "face_recognition_server.py",
        "face_tracker.py",
        "get_sensors.py",
        "recognize_face.py",
        "face_database.py"
    )
    
    foreach ($file in $files) {
        $localPath = Join-Path $ScriptsDir $file
        if (Test-Path $localPath) {
            Write-Host "  Copying $file..." -NoNewline
            scp $localPath "${User}@${Ip}:${RemotePath}/" 2>$null
            if ($LASTEXITCODE -eq 0) {
                Write-Host " OK" -ForegroundColor Green
            } else {
                Write-Host " FAILED" -ForegroundColor Red
            }
        }
    }
}

function Restart-Server {
    Write-Host "`n=== Restarting Server ===" -ForegroundColor Cyan
    
    Write-Host "  Stopping old server..." -NoNewline
    ssh "${User}@${Ip}" "pkill -f face_recognition_server.py" 2>$null
    Write-Host " OK" -ForegroundColor Green
    
    Write-Host "  Starting new server..." -NoNewline
    # Start server in background - the 'sleep 1' ensures SSH returns after backgrounding
    ssh "${User}@${Ip}" "cd ${RemotePath} && (bash run_server.sh > /tmp/server.log 2>&1 &) && sleep 1" 2>$null
    Write-Host " OK" -ForegroundColor Green
    
    Write-Host "  Waiting for server to start (4s)..." -NoNewline
    Start-Sleep -Seconds 4
    Write-Host " OK" -ForegroundColor Green
}

# Deploy if requested
if ($Deploy) {
    Deploy-Scripts
    Restart-Server
} elseif ($Restart) {
    Restart-Server
}

Write-Host "`n=== Pepper Face Tracking Monitor ===" -ForegroundColor Cyan
Write-Host "Host: ${User}@${Ip}"
Write-Host "Interval: ${Interval}s"
Write-Host "Press Ctrl+C to stop"
Write-Host "Tip: Use -Deploy to deploy & restart, -Restart to just restart"
Write-Host "=====================================" -ForegroundColor Cyan

while ($true) {
    try {
        # Use SSH to query localhost on Pepper
        $json = ssh "${User}@${Ip}" "curl -s http://localhost:5000/people" 2>$null
        
        if (-not $json) {
            throw "No response from server"
        }
        
        $response = $json | ConvertFrom-Json
        
        # Clear previous output
        Clear-Host
        Write-Host "=== Pepper Face Tracking ===" -ForegroundColor Cyan
        Write-Host "Time: $(Get-Date -Format 'HH:mm:ss')"
        Write-Host "Head: yaw=$([math]::Round($response.head_angles.yaw, 1)) pitch=$([math]::Round($response.head_angles.pitch, 1))" -ForegroundColor DarkGray
        Write-Host "Update: $($response.timing.update_ms)ms" -ForegroundColor DarkGray
        Write-Host ""
        
        if ($response.people.Count -eq 0) {
            Write-Host "No people detected" -ForegroundColor Yellow
        } else {
            Write-Host "People detected: $($response.people.Count)" -ForegroundColor Green
            Write-Host ""
            
            foreach ($person in $response.people) {
                $nameColor = if ($person.name -eq "Unknown") { "Yellow" } else { "Green" }
                $conf = [math]::Round($person.confidence * 100)
                $yaw = [math]::Round($person.world_yaw, 1)
                $pitch = [math]::Round($person.world_pitch, 1)
                $dist = if ($person.distance -gt 0) { "$([math]::Round($person.distance, 2))m" } else { "?" }
                
                # Gaze indicator
                if ($person.looking_at_robot) {
                    $gaze = "[LOOKING]"
                    $gazeColor = "Green"
                } else {
                    $headDir = $person.head_direction
                    if ($headDir -gt 0.2) {
                        $gaze = "[<- LEFT]"
                    } elseif ($headDir -lt -0.2) {
                        $gaze = "[RIGHT ->]"
                    } else {
                        $gaze = "[AWAY]"
                    }
                    $gazeColor = "DarkGray"
                }
                
                Write-Host "  Track $($person.track_id): " -NoNewline
                Write-Host "$($person.name)" -ForegroundColor $nameColor -NoNewline
                Write-Host " (${conf}%)" -NoNewline
                Write-Host " | yaw=${yaw} | ${dist} | " -ForegroundColor DarkGray -NoNewline
                Write-Host $gaze -ForegroundColor $gazeColor
            }
        }
        
        Write-Host ""
        Write-Host "Press Ctrl+C to stop" -ForegroundColor DarkGray
        
    } catch {
        Write-Host "Error: $_" -ForegroundColor Red
        Write-Host "Retrying in ${Interval}s..." -ForegroundColor Yellow
    }
    
    Start-Sleep -Seconds $Interval
}

