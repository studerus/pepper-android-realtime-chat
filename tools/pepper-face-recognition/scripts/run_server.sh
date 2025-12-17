#!/bin/bash
# Start the Face Recognition Server with Camera Daemon

export LD_LIBRARY_PATH=/home/nao/python_packages/libs:$LD_LIBRARY_PATH
export PYTHONPATH=/home/nao/python_packages
export PYTHONUNBUFFERED=1

SCRIPT_DIR="/home/nao/face_data"
DAEMON_PID=""

# Function to cleanup on exit
cleanup() {
    echo "Stopping services..."
    if [ -n "$DAEMON_PID" ] && kill -0 $DAEMON_PID 2>/dev/null; then
        # Try graceful shutdown first
        curl -s http://127.0.0.1:5050/shutdown >/dev/null 2>&1
        sleep 1
        # Force kill if still running
        kill -9 $DAEMON_PID 2>/dev/null
    fi
    exit 0
}

trap cleanup SIGINT SIGTERM

# Kill any existing camera daemon
pkill -f camera_daemon.py 2>/dev/null
sleep 1

# Start Camera Daemon (Python 2.7)
echo "Starting Camera Daemon on port 5050..."
PYTHONPATH=/opt/aldebaran/lib/python2.7/site-packages python2 "$SCRIPT_DIR/camera_daemon.py" &
DAEMON_PID=$!

# Wait for daemon to be ready
echo "Waiting for Camera Daemon to initialize..."
for i in {1..10}; do
    if curl -s http://127.0.0.1:5050/status >/dev/null 2>&1; then
        echo "Camera Daemon ready!"
        break
    fi
    sleep 1
done

if ! curl -s http://127.0.0.1:5050/status >/dev/null 2>&1; then
    echo "ERROR: Camera Daemon failed to start!"
    kill $DAEMON_PID 2>/dev/null
    exit 1
fi

# Start Face Recognition Server (Python 3.7)
echo "Starting Face Recognition Server on port 5000..."
python3 -u "$SCRIPT_DIR/face_recognition_server.py"

# Cleanup when server exits
cleanup
