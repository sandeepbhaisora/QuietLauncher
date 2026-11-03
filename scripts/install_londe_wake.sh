#!/usr/bin/env bash
# Download sherpa-onnx's small open-vocabulary KWS model and tokenize HEY LONDE.
# Packaged into Quiet APK; keyword detection never sends microphone audio online.
set -euo pipefail
cd "$(dirname "$0")/.."
NAME="sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
DEST="app/src/main/assets/londe_wake"
mkdir -p "$DEST"
if [[ -s "$DEST/encoder.onnx" && -s "$DEST/keywords.txt" && -s "$DEST/tokens.txt" ]]; then
  exit 0
fi
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
curl --fail --location --retry 4 --output "$tmp/kws.tar.bz2" \
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/${NAME}.tar.bz2"
for file in encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx \
            decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx \
            joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx \
            tokens.txt bpe.model; do
  tar -xjf "$tmp/kws.tar.bz2" -C "$tmp" "$NAME/$file"
done
cp "$tmp/$NAME/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx" "$DEST/encoder.onnx"
cp "$tmp/$NAME/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx" "$DEST/decoder.onnx"
cp "$tmp/$NAME/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx" "$DEST/joiner.onnx"
cp "$tmp/$NAME/tokens.txt" "$DEST/tokens.txt"
python3 -m pip install --quiet 'sentencepiece==0.2.1'
python3 - "$tmp/$NAME/bpe.model" "$DEST/tokens.txt" "$DEST/keywords.txt" <<'PY'
import sys
import sentencepiece as spm
bpe, token_path, output = sys.argv[1:]
sp = spm.SentencePieceProcessor(model_file=bpe)
vocab = {line.rsplit(' ', 1)[0] for line in open(token_path, encoding='utf-8') if line.strip()}
# Multiple *full* phrases, not a permissive standalone "Londe" keyword.
# Pronunciation varies across English and Hinglish speakers.
phrases = [("HEY LONDE", "HEY_LONDE"),
           ("HEY LONDA", "HEY_LONDA"),
           ("HEY LAUNDE", "HEY_LAUNDE")]
lines = []
for phrase, label in phrases:
    pieces = sp.encode(phrase, out_type=str)
    if not pieces or any(piece not in vocab for piece in pieces):
        raise SystemExit("Unsupported wake tokens: " + repr((phrase, pieces)))
    lines.append(" ".join(pieces) + " :1.8 #0.28 @" + label)
with open(output, "w", encoding="utf-8") as f:
    f.write("\n".join(lines) + "\n")
print("Generated wake phrases:", [p for p, _ in phrases])
PY
test -s "$DEST/keywords.txt"
printf 'sherpa-onnx gigaspeech KWS model; see https://k2-fsa.github.io/sherpa/onnx/kws/pretrained_models/index.html\n' > "$DEST/ORIGIN.txt"
du -sh "$DEST"
