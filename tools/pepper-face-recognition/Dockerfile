FROM i386/python:3.7-buster

# Update sources for archived Debian Buster
RUN echo "deb http://archive.debian.org/debian/ buster main" > /etc/apt/sources.list && \
    echo "deb-src http://archive.debian.org/debian/ buster main" >> /etc/apt/sources.list && \
    echo "deb http://archive.debian.org/debian-security buster/updates main" >> /etc/apt/sources.list && \
    echo "deb-src http://archive.debian.org/debian-security buster/updates main" >> /etc/apt/sources.list && \
    echo "Acquire::Check-Valid-Until false;" > /etc/apt/apt.conf.d/10no-check-valid-until

# Install dependencies
RUN apt-get update && apt-get install -y --allow-unauthenticated \
    libopenblas-dev \
    liblapack-dev \
    libjpeg-dev \
    libpng-dev \
    libtiff-dev \
    wget \
    unzip \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /build

# Upgrade pip
RUN pip install --upgrade pip

# Install numpy and OpenCV headless
# Using a specific version of numpy compatible with Python 3.7
RUN pip install --target=/build/packages numpy==1.21.6
# Install opencv-python-headless (contains YuNet and SFace support since 4.5.4)
RUN pip install --target=/build/packages opencv-python-headless
# Install websockets for streaming perception data (last version supporting Python 3.7)
RUN pip install --target=/build/packages websockets==10.4

# Download Models (YuNet for detection, SFace for recognition)
WORKDIR /build/models
RUN wget https://github.com/opencv/opencv_zoo/raw/main/models/face_detection_yunet/face_detection_yunet_2023mar.onnx -O face_detection_yunet.onnx
RUN wget https://github.com/opencv/opencv_zoo/raw/main/models/face_recognition_sface/face_recognition_sface_2021dec.onnx -O face_recognition_sface.onnx

# Zip everything
WORKDIR /build
RUN python -m zipfile -c packages.zip packages/

# Default command: copy output to volume
CMD ["sh", "-c", "cp /build/packages.zip /output/ && cp -r /build/models /output/"]
