# Cleanup leftover files from 64-bit experiments
# Usage: .\cleanup-pepper.ps1 -PepperIP "10.95.65.123"

param(
    [Parameter(Mandatory=$true)]
    [string]$PepperIP
)

Write-Host "Cleaning up Pepper at $PepperIP..." -ForegroundColor Cyan

$cmd = @'
echo "Removing Miniconda..."
rm -rf /home/nao/miniconda3
rm -f /home/nao/miniconda.sh

echo "Removing Ubuntu 64-bit Chroot..."
rm -rf /home/nao/ubuntu64
rm -f /home/nao/ubuntu-base.tar.gz
rm -f /home/nao/run_64bit.sh
rm -f /home/nao/activate_vision.sh

echo "Cleanup complete!"
df -h /home
'@

ssh nao@$PepperIP $cmd

