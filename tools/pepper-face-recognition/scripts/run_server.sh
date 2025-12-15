#!/bin/bash
# Start the Face Recognition Server
export LD_LIBRARY_PATH=/home/nao/python_packages/libs:$LD_LIBRARY_PATH
export PYTHONPATH=/home/nao/python_packages
echo "Starting Face Recognition Server on port 5000..."
python3 /home/nao/face_data/face_recognition_server.py


