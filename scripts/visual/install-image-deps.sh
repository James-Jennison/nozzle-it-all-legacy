#!/bin/bash
set -euo pipefail

apt-get update
DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    bubblewrap \
    fonts-dejavu-core \
    fonts-noto-core \
    locales \
    mesa-utils \
    openjdk-17-jre-headless \
    python3 \
    x11-apps
locale-gen en_US.UTF-8
rm -rf /var/lib/apt/lists/*
