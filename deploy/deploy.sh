#!/usr/bin/env bash
# Syncs this repo to the Pi and runs the installer there:
#   ./deploy/deploy.sh [user@host]
set -euo pipefail
cd "$(dirname "$0")/.."
host=${1:-pi@frontdoor3}

rsync -az --delete --exclude .git --exclude '.venv*' --exclude __pycache__ --exclude .pytest_cache \
    --include 'models/***' --include 'frontdoor/***' --include 'deploy/***' \
    --include pyproject.toml --include config.example.toml --exclude '*' \
    ./ "$host:frontdoor-src/"
# local/ (gitignored) holds config and secrets, so keep it private to the login user on the Pi.
mkdir -p local
rsync -az --delete --chmod=go-rwx local/ "$host:frontdoor-src/local/"
ssh -t "$host" "sudo ./frontdoor-src/deploy/install.sh"
