#!/usr/bin/env bash
# Installs or upgrades frontdoor on a Raspberry Pi OS (64-bit) box. Run from the synced source directory:
#   sudo ./deploy/install.sh
set -euo pipefail
cd "$(dirname "$0")/.."

# picamera2 needs the apt build (libcamera bindings), so the venv sees system site-packages. numpy and
# pillow also come from apt so they match what picamera2 was built against.
apt-get update
apt-get install -y --no-install-recommends python3-picamera2 python3-venv python3-numpy python3-pil

id frontdoor &>/dev/null || useradd --system --home-dir /var/lib/frontdoor --shell /usr/sbin/nologin --groups video frontdoor
install -d -o frontdoor -g frontdoor /var/lib/frontdoor
install -d /opt/frontdoor/models /etc/frontdoor

[ -d /opt/frontdoor/venv ] || python3 -m venv --system-site-packages /opt/frontdoor/venv
/opt/frontdoor/venv/bin/pip install --quiet --upgrade .

if compgen -G "models/*.onnx" >/dev/null; then
    install -m 644 models/*.onnx /opt/frontdoor/models/
fi
# Files kept in the gitignored local/ folder (config.toml, mask.png, aws-credentials) replace the Pi's copies.
for file in local/*; do
    if [ -f "$file" ]; then
        install -m 640 -g frontdoor "$file" /etc/frontdoor/
        echo "Installed $file to /etc/frontdoor/"
    fi
done
if [ ! -f /etc/frontdoor/config.toml ]; then
    install -m 640 -g frontdoor config.example.toml /etc/frontdoor/config.toml
    echo "Created /etc/frontdoor/config.toml, edit it before starting the service."
fi
if [ ! -f /etc/frontdoor/aws-credentials ]; then
    install -m 640 -g frontdoor /dev/null /etc/frontdoor/aws-credentials
    echo "Created empty /etc/frontdoor/aws-credentials, add [default] aws_access_key_id/aws_secret_access_key for S3."
fi

install -m 644 deploy/frontdoor.service /etc/systemd/system/frontdoor.service
systemctl daemon-reload
systemctl enable frontdoor.service
systemctl restart frontdoor.service
systemctl --no-pager status frontdoor.service || true
