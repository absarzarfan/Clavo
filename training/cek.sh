#!/bin/bash
echo "--- proses:"; pgrep -af my_script.py || echo "TIDAK BERJALAN"
echo "--- epoch terakhir:"; tr '\r' '\n' < ~/clavo/train.log | grep -a "^Epoch" | tail -n 1
echo "--- progres:"; tail -c 800 ~/clavo/train.log | tr '\r' '\n' | grep -a -E "^ *[0-9]+/[0-9]+ \[" | tail -n 1 | cut -c1-100
echo "--- RAM:"; free -h | sed -n 2p
echo "--- GPU:"; nvidia-smi --query-gpu=utilization.gpu,memory.used,temperature.gpu --format=csv,noheader
