#!/usr/bin/env bash
# Boards the bus on the recorded trip (no keys needed) unless the keys are set.
# A live run wants OPENAI_API_KEY and BRAVE_API_KEY (YOUTUBE_API_KEY optional),
# exactly as Tripper does; without them this script replays the recording.
set -euo pipefail
cd "$(dirname "$0")"
if [[ -z "${OPENAI_API_KEY:-}" || -z "${BRAVE_API_KEY:-}" ]]; then
  export PARTYBUS_SOURCES=recorded
  echo "no OPENAI_API_KEY/BRAVE_API_KEY in the environment: replaying the recorded trip"
fi
# `compile` first: this project sits outside the reactor, so a build at the
# repository root never produces its classes, and exec:java alone would fail
# with ClassNotFoundException.
mvn -q compile exec:java -Dexec.cleanupDaemonThreads=false -Dstdout.encoding=UTF-8
