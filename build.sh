#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
rm -rf src/main/resources/static
mkdir -p src/main/resources/static
cd frontend
npm install --no-audit --no-fund
npm run build
cd ..
cp -R frontend/dist/. src/main/resources/static/
mvn -B clean test package
