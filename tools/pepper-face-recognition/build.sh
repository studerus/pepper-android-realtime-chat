#!/bin/bash
# Build face_recognition packages for Pepper (i386 architecture)
# Prerequisites: Docker installed and running

set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo -e "\033[36mBuilding face_recognition for Pepper (i386)...\033[0m"
echo -e "\033[33mThis will take 15-30 minutes due to dlib compilation.\n\033[0m"

# Step 1: Build Docker image
echo -e "\033[33m[1/3] Building Docker image...\033[0m"
docker build -t pepper-face-rec-builder .

# Step 2: Create output directory and extract packages
echo -e "\n\033[33m[2/3] Extracting Python packages...\033[0m"
mkdir -p output
docker run --rm -v "${PWD}/output:/output" pepper-face-rec-builder

# Step 3: Extract native libraries that dlib needs
echo -e "\n\033[33m[3/3] Extracting native libraries...\033[0m"
mkdir -p output/libs

docker run --rm -v "${PWD}/output:/output" pepper-face-rec-builder sh -c '
cp /usr/lib/i386-linux-gnu/libopenblas.so.0 /output/libs/
cp /usr/lib/i386-linux-gnu/libwebp.so.6 /output/libs/
cp /usr/lib/i386-linux-gnu/libgfortran.so.5 /output/libs/
cp /usr/lib/i386-linux-gnu/libSM.so.6 /output/libs/
cp /usr/lib/i386-linux-gnu/libICE.so.6 /output/libs/
cp /usr/lib/i386-linux-gnu/libX11.so.6 /output/libs/
cp /usr/lib/i386-linux-gnu/libXext.so.6 /output/libs/
cp /usr/lib/i386-linux-gnu/libsqlite3.so.0 /output/libs/
cp /usr/lib/i386-linux-gnu/libpng16.so.16 /output/libs/
cp /usr/lib/i386-linux-gnu/libjpeg.so.62 /output/libs/
cp /usr/lib/i386-linux-gnu/libbsd.so.0 /output/libs/
cp /usr/lib/i386-linux-gnu/libxcb.so.1 /output/libs/
cp /usr/lib/i386-linux-gnu/libquadmath.so.0 /output/libs/
cp /usr/lib/i386-linux-gnu/libXau.so.6 /output/libs/
cp /usr/lib/i386-linux-gnu/libXdmcp.so.6 /output/libs/
ls -la /output/libs/
'

echo -e "\n\033[32m✓ Build complete!\033[0m"
echo -e "\n\033[36mOutput files:\033[0m"
echo "  - output/packages.zip  (Python packages)"
echo "  - output/libs/         (Native libraries)"
echo -e "\n\033[33mNext step: Run ./deploy.sh to install on Pepper\033[0m"

