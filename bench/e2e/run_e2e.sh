#!/bin/bash
ADB=/home/not_me/antigravity-workspace/agentstuff/agenttranslator/tools/platform-tools/adb
E=/data/local/tmp/e2e; Q=$E/q
run(){ # dir asrTag wavglob label
  local dir=$1 tag=$2 glob=$3 label=$4
  $ADB shell "rm -f $Q/*"
  $ADB shell "cd $E && CLASSPATH=$E/mt.dex LD_LIBRARY_PATH=/data/local/tmp/ort taskset f0 app_process $E MtServer $dir 4 $Q" > /tmp/llbench/e2e/mt_$label.log 2>&1 &
  local mtpid=$!
  for i in $(seq 1 60); do $ADB shell "[ -f $Q/mt_ready ] && echo ready" | grep -q ready && break; sleep 1; done
  $ADB shell "cd $E && CLASSPATH=$E/asrtts.dex LD_LIBRARY_PATH=$E/lib taskset f0 app_process $E AsrTts $dir $tag 4 1 $Q $E/out/e2e_$label.jsonl $glob" 2>&1 | tr -d '\r' > /tmp/llbench/e2e/e2e_$label.log
  wait $mtpid 2>/dev/null
  echo "### $label done: $(grep -c E2E_first /tmp/llbench/e2e/e2e_$label.log) фраз"
}
run pt2ru fast    "/data/local/tmp/sh/audio/pt/tat_*.wav" pt2ru_fast
run pt2ru quality "/data/local/tmp/sh/audio/pt/tat_*.wav" pt2ru_quality
run ru2pt quality "/data/local/tmp/sh/audio/ru/tat_*.wav" ru2pt_quality
echo ALL_DONE
