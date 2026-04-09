#!/usr/bin/env bash
set -euo pipefail

cd /Users/asami/src/dev2026/textus-ai
exec sbt --batch "runMain org.goldenport.cncf.CncfMain --discover=classes" "$@"
