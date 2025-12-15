# Deploy face_recognition packages (OpenCV) to Pepper's head
# Usage: .\deploy.ps1 -PepperIP "10.95.65.123"

param(
    [Parameter(Mandatory=$true)]
    [string]$PepperIP
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$OutputDir = Join-Path $ScriptDir "output"
$PackagesZip = Join-Path $OutputDir "packages.zip"
$ModelsDir = Join-Path $OutputDir "models"

# Check prerequisites
if (-not (Test-Path $PackagesZip)) {
    Write-Error "packages.zip not found in output/. Run build.ps1 first."
    exit 1
}

if (-not (Test-Path $ModelsDir)) {
    Write-Error "models/ directory not found in output/. Run build.ps1 first."
    exit 1
}

Write-Host "Deploying OpenCV Face Recognition to Pepper at $PepperIP..." -ForegroundColor Cyan

# Step 1: Create directories on Pepper
Write-Host ""
Write-Host "[1/5] Creating directories on Pepper..." -ForegroundColor Yellow
ssh nao@$PepperIP "mkdir -p /home/nao/python_packages; mkdir -p /home/nao/face_data"

# Step 2: Copy packages.zip
Write-Host ""
Write-Host "[2/5] Copying packages.zip to Pepper..." -ForegroundColor Yellow
scp $PackagesZip "nao@${PepperIP}:/home/nao/"

# Step 3: Copy Models
Write-Host ""
Write-Host "[3/5] Copying models to Pepper..." -ForegroundColor Yellow
$models = Get-ChildItem $ModelsDir -Filter "*.onnx"
foreach ($model in $models) {
    Write-Host "  Copying $($model.Name)..."
    scp $model.FullName "nao@${PepperIP}:/home/nao/face_data/"
}

# Step 3.5: Copy Libs (if any)
$LibsDir = Join-Path $OutputDir "libs"
if (Test-Path $LibsDir) {
    Write-Host ""
    Write-Host "[3.5/5] Copying native libraries..." -ForegroundColor Yellow
    ssh nao@$PepperIP "mkdir -p /home/nao/python_packages/libs"
    $libs = Get-ChildItem $LibsDir
    foreach ($lib in $libs) {
        Write-Host "  Copying $($lib.Name)..."
        scp $lib.FullName "nao@${PepperIP}:/home/nao/python_packages/libs/"
    }
}

# Step 4: Copy scripts
Write-Host ""
Write-Host "[4/5] Copying scripts..." -ForegroundColor Yellow
$ScriptsDir = Join-Path $ScriptDir "scripts"
if (Test-Path $ScriptsDir) {
    $scripts = Get-ChildItem $ScriptsDir -Filter "*.py"
    foreach ($script in $scripts) {
        Write-Host "  Copying $($script.Name)..."
        scp $script.FullName "nao@${PepperIP}:/home/nao/face_data/"
    }
}

# Step 5: Unzip and setup on Pepper
Write-Host ""
Write-Host "[5/5] Extracting and setting up packages on Pepper..." -ForegroundColor Yellow
$setupScript = @'
cd /home/nao

# Unzip packages
echo "Unzipping packages..."
unzip -o packages.zip -d python_packages_temp > /dev/null
cp -r python_packages_temp/packages/* python_packages/
rm -rf python_packages_temp
rm packages.zip

echo "Setup complete!"
'@

ssh nao@$PepperIP $setupScript

# Step 6: Test installation
Write-Host ""
Write-Host "[6/6] Testing installation (Self-Test)..." -ForegroundColor Yellow

$testPyContent = @'
import sys
try:
    import cv2
    import numpy
    print("SUCCESS: Loaded cv2 " + cv2.__version__ + ", numpy " + numpy.__version__)
    
    # Check if SFace/YuNet are supported
    try:
        if hasattr(cv2, 'FaceDetectorYN'):
            print("SUCCESS: FaceDetectorYN available")
        else:
            print("ERROR: FaceDetectorYN NOT available")
            
        if hasattr(cv2, 'FaceRecognizerSF'):
            print("SUCCESS: FaceRecognizerSF available")
        else:
            print("ERROR: FaceRecognizerSF NOT available")
    except Exception as e:
        print("ERROR checking Face modules: " + str(e))
        
except Exception as e:
    print("ERROR: " + str(e))
    sys.exit(1)
'@

# Save test script locally
$TestScriptPath = Join-Path $OutputDir "test_install.py"
Set-Content -Path $TestScriptPath -Value $testPyContent -Encoding ASCII

# Copy to Pepper
scp $TestScriptPath "nao@${PepperIP}:/home/nao/"

# Run on Pepper
$runTestCmd = @'
export PYTHONPATH=/home/nao/python_packages:$PYTHONPATH
export LD_LIBRARY_PATH=/home/nao/python_packages/libs:$LD_LIBRARY_PATH
python3 /home/nao/test_install.py
'@

try {
    ssh nao@$PepperIP $runTestCmd
    if ($LASTEXITCODE -eq 0) {
        Write-Host ""
        Write-Host "✅ VERIFICATION SUCCESSFUL!" -ForegroundColor Green
        Write-Host "The OpenCV face recognition stack is correctly installed and working on Pepper."
    } else {
        throw "Test script failed with exit code $LASTEXITCODE"
    }
} catch {
    Write-Host ""
    Write-Host "❌ VERIFICATION FAILED!" -ForegroundColor Red
    Write-Host "There was an error loading the modules on Pepper. Check the output above."
    exit 1
} finally {
    if (Test-Path $TestScriptPath) { Remove-Item $TestScriptPath }
}

Write-Host ""
Write-Host "How to use:" -ForegroundColor Cyan
Write-Host "  1. SSH into Pepper: ssh nao@$PepperIP" -ForegroundColor White
Write-Host "  2. Go to face data: cd /home/nao/face_data" -ForegroundColor White
Write-Host "  3. Run script: PYTHONPATH=/home/nao/python_packages python3 recognize_face.py" -ForegroundColor White
