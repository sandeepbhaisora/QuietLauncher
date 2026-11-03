#!/usr/bin/env bash
# Materialize the verified Android JNI runtime and real Supertonic 3 model.
# Used by CI and local Android Studio builds; generated binaries are gitignored.
set -euo pipefail
cd "$(dirname "$0")/.."

SHERPA_VERSION="2.0.1"
RUNTIME_SHA256="a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7"
MODEL_NAME="sherpa-onnx-supertonic-3-tts-int8-2026-05-11"
MODEL_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/${MODEL_NAME}.tar.bz2"
AAR_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${SHERPA_VERSION}/sherpa-onnx-${SHERPA_VERSION}.aar"
AAR="app/libs/sherpa-onnx-${SHERPA_VERSION}.aar"
ASSETS="app/src/main/assets/quiet_voice"
mkdir -p app/libs "$ASSETS"

if [[ ! -f "$AAR" ]] || ! printf '%s  %s\n' "$RUNTIME_SHA256" "$AAR" | sha256sum --check --status; then
  echo "Fetching sherpa-onnx Android runtime v$SHERPA_VERSION"
  curl --fail --location --retry 5 --retry-delay 3 --output "$AAR.download" "$AAR_URL"
  printf '%s  %s\n' "$RUNTIME_SHA256" "$AAR.download" | sha256sum --check
  mv "$AAR.download" "$AAR"
fi

required=(duration_predictor.int8.onnx text_encoder.int8.onnx vector_estimator.int8.onnx vocoder.int8.onnx tts.json unicode_indexer.bin voice.bin)
ready=true
for name in "${required[@]}"; do
  if [[ ! -s "$ASSETS/$name" ]]; then ready=false; fi
done
if [[ "$ready" == true ]]; then
  echo "Supertonic 3 assets already installed"
  exit 0
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
echo "Downloading actual Supertonic 3 Hindi-capable voice model"
curl --fail --location --retry 5 --retry-delay 3 --output "$tmp/model.tar.bz2" "$MODEL_URL"
# Prevent arbitrary files/path traversal: select only the known model files.
for name in "${required[@]}"; do
  tar -xjf "$tmp/model.tar.bz2" -C "$tmp" "$MODEL_NAME/$name"
  test -s "$tmp/$MODEL_NAME/$name"
done
for name in "${required[@]}"; do
  cp "$tmp/$MODEL_NAME/$name" "$ASSETS/$name"
done
# Both the model and runtime are third-party assets. Preserve upstream licenses.
printf 'sherpa-onnx v%s, Apache-2.0: https://github.com/k2-fsa/sherpa-onnx\n' "$SHERPA_VERSION" > "$ASSETS/THIRD_PARTY.txt"
printf 'Supertonic 3, OpenRAIL-M: https://huggingface.co/Supertone/supertonic-3\n' >> "$ASSETS/THIRD_PARTY.txt"
du -sh "$ASSETS" "$AAR"
