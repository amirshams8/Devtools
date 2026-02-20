#!/data/data/com.termux/files/usr/bin/bash
#
# build_runner.sh — triggered by TermuxBridgeModule via com.termux.RUN_COMMAND
#
# Delivery chain:
#   1. Copy ai-output.txt into JarvisMini repo + commit + push
#      → triggers Ai-codegen.yml ("AI Code Generator")
#   2. Ai-codegen.yml completes
#      → triggers main.yml ("Extract Repo Source to TXT")
#   3. main.yml completes
#      → triggers Apk.yml ("Build APK")
#   4. Wait for Apk.yml to finish via: gh run watch
#   5. git pull — downloads build_error_logs/error_summary.txt and
#      error_files.txt if Apk.yml committed them on failure
#   6. Touch build_complete.flag so TermuxBridgeModule.pollForCompletion() returns
#
# Prerequisites:
#   pkg install git gh
#   gh auth login
#   Repo cloned at $REPO_DIR with push access configured
#
# Deploy to device:
#   adb push build_runner.sh /sdcard/ai-automation/scripts/build_runner.sh
#   adb shell "run-as com.termux chmod +x /sdcard/ai-automation/scripts/build_runner.sh"
#   OR simply: copy the file in Termux and chmod +x it

set -euo pipefail

REPO_DIR="/data/data/com.termux/files/home/jarvis"   # ← adjust to your repo clone path
AI_OUTPUT="/sdcard/ai-automation/ai-output.txt"
COMPLETE_FLAG="/sdcard/ai-automation/build_complete.flag"
LOG_DIR="/sdcard/ai-automation/logs"

mkdir -p "$LOG_DIR"
exec >> "$LOG_DIR/build_runner.log" 2>&1

echo ""
echo "=== build_runner.sh START $(date) ==="

# Safety: clear the completion flag so the app doesn't read a stale one
rm -f "$COMPLETE_FLAG"

# 1. Copy ai-output.txt into repo and push
cp "$AI_OUTPUT" "$REPO_DIR/ai-output.txt"
cd "$REPO_DIR"
git add ai-output.txt
git commit -m "AutoBuild: update ai-output.txt" || echo "[skip] Nothing to commit"
git push origin main
echo "[done] Pushed ai-output.txt → Ai-codegen.yml will trigger"

# 2. Wait for Ai-codegen.yml ("AI Code Generator") to start and finish
echo "[wait] Waiting 20s for Ai-codegen.yml to be queued…"
sleep 20
CODEGEN_RUN_ID=$(gh run list --workflow="AI Code Generator" --limit=1 \
    --json databaseId -q '.[0].databaseId')
echo "[watch] Watching Ai-codegen.yml run $CODEGEN_RUN_ID"
gh run watch "$CODEGEN_RUN_ID" --exit-status || echo "[warn] Ai-codegen.yml may have failed"

# 3. Wait for main.yml ("Extract Repo Source to TXT")
echo "[wait] Waiting 15s for main.yml to be queued…"
sleep 15
MAIN_RUN_ID=$(gh run list --workflow="Extract Repo Source to TXT" --limit=1 \
    --json databaseId -q '.[0].databaseId')
echo "[watch] Watching main.yml run $MAIN_RUN_ID"
gh run watch "$MAIN_RUN_ID" --exit-status || echo "[warn] main.yml may have failed"

# 4. Wait for Apk.yml ("Build APK")
echo "[wait] Waiting 15s for Apk.yml to be queued…"
sleep 15
APK_RUN_ID=$(gh run list --workflow="Build APK" --limit=1 \
    --json databaseId -q '.[0].databaseId')
echo "[watch] Watching Apk.yml run $APK_RUN_ID"
gh run watch "$APK_RUN_ID" || true   # don't exit on build failure — we want to pull logs

# 5. Pull results (error_summary.txt + error_files.txt if Apk.yml committed them)
echo "[pull] git pull origin main"
git pull origin main
echo "[done] git pull complete"

# 6. Signal completion to TermuxBridgeModule.pollForCompletion()
touch "$COMPLETE_FLAG"
echo "=== build_runner.sh DONE $(date) ==="
