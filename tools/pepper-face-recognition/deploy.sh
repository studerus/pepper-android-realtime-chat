#!/bin/bash
# Deploy face_recognition packages to Pepper's head
# Usage: ./deploy.sh <PEPPER_IP>

set -e

if [ -z "$1" ]; then
    echo "Usage: ./deploy.sh <PEPPER_IP>"
    echo "Example: ./deploy.sh 10.95.65.123"
    exit 1
fi

PEPPER_IP="$1"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT_DIR="$SCRIPT_DIR/output"
PACKAGES_ZIP="$OUTPUT_DIR/packages.zip"
LIBS_DIR="$OUTPUT_DIR/libs"

# Check prerequisites
if [ ! -f "$PACKAGES_ZIP" ]; then
    echo "Error: packages.zip not found in output/. Run ./build.sh first."
    exit 1
fi

if [ ! -d "$LIBS_DIR" ]; then
    echo "Error: libs/ directory not found in output/. Run ./build.sh first."
    exit 1
fi

echo -e "\033[36mDeploying face_recognition to Pepper at $PEPPER_IP...\033[0m"

# Step 1: Create directories on Pepper
echo -e "\n\033[33m[1/5] Creating directories on Pepper...\033[0m"
ssh nao@$PEPPER_IP "mkdir -p /home/nao/python_packages/libs"

# Step 2: Copy packages.zip
echo -e "\n\033[33m[2/5] Copying packages.zip to Pepper...\033[0m"
scp "$PACKAGES_ZIP" "nao@${PEPPER_IP}:/home/nao/"

# Step 3: Copy all library files
echo -e "\n\033[33m[3/5] Copying native libraries to Pepper...\033[0m"
scp "$LIBS_DIR"/*.so* "nao@${PEPPER_IP}:/home/nao/python_packages/libs/"

# Step 4: Copy Python scripts
echo -e "\n\033[33m[4/6] Copying face recognition scripts...\033[0m"
SCRIPTS_DIR="$SCRIPT_DIR/scripts"
if [ -d "$SCRIPTS_DIR" ]; then
    ssh nao@$PEPPER_IP "mkdir -p /home/nao/face_data"
    scp "$SCRIPTS_DIR"/*.py "nao@${PEPPER_IP}:/home/nao/face_data/"
fi

# Step 5: Unzip and setup on Pepper
echo -e "\n\033[33m[5/6] Extracting and setting up packages on Pepper...\033[0m"
ssh nao@$PEPPER_IP << 'SETUP_EOF'
cd /home/nao

# Unzip packages
unzip -o packages.zip -d python_packages_temp
cp -r python_packages_temp/packages/* python_packages/
rm -rf python_packages_temp
rm packages.zip

# Create symlink for dlib (Python needs simple .so name)
cd python_packages
if [ -f "_dlib_pybind11.cpython-37m-i386-linux-gnu.so" ]; then
    ln -sf _dlib_pybind11.cpython-37m-i386-linux-gnu.so _dlib_pybind11.so
    echo "Symlink created for dlib"
fi

echo "Setup complete!"
SETUP_EOF

# Step 6: Test installation
echo -e "\n\033[33m[6/6] Testing installation...\033[0m"
TEST_RESULT=$(ssh nao@$PEPPER_IP << 'TEST_EOF'
export LD_LIBRARY_PATH=/home/nao/python_packages/libs
export PYTHONPATH=/home/nao/python_packages
python3 -c "
import sys
sys.path.insert(0, '/home/nao/python_packages')
import dlib
print('dlib version:', dlib.__version__)
print('SUCCESS: dlib is working!')
"
TEST_EOF
)

echo "$TEST_RESULT"

if echo "$TEST_RESULT" | grep -q "SUCCESS"; then
    echo -e "\n\033[32m✓ Deployment successful!\033[0m"
    echo -e "\n\033[36mTo use face_recognition on Pepper, run Python with:\033[0m"
    echo "  LD_LIBRARY_PATH=/home/nao/python_packages/libs PYTHONPATH=/home/nao/python_packages python3 your_script.py"
else
    echo -e "\n\033[31m✗ Test failed. Check the error messages above.\033[0m"
    exit 1
fi

