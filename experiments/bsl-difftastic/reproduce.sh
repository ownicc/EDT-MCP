#!/usr/bin/env bash
# Throwaway experiment: creates a new directory and never writes to EDT.
set -euo pipefail

experiment_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
spike_dir="$(mktemp -d /tmp/edt-bsl-difftastic-XXXXXX)"
baseline_difft="$(command -v difft)"

git clone --depth 1 --branch 0.71.0 https://github.com/Wilfred/difftastic.git "$spike_dir/difftastic"
git clone --depth 1 --branch v0.1.7 https://github.com/alkoleft/tree-sitter-bsl.git "$spike_dir/tree-sitter-bsl"
test "$(git -C "$spike_dir/difftastic" rev-parse HEAD)" = b7d119e90ac9f972f03f69508da765dacd302c0c
test "$(git -C "$spike_dir/tree-sitter-bsl" rev-parse HEAD)" = b424b53e72ca0746ea9a7131d80aec1947523a0c
git -C "$spike_dir/difftastic" apply --unidiff-zero --check "$experiment_dir/difftastic-bsl.patch"
git -C "$spike_dir/difftastic" apply --unidiff-zero "$experiment_dir/difftastic-bsl.patch"
(
    cd "$spike_dir/difftastic"
    cargo +1.85.0 build --release --locked --jobs 4
)
python3 "$experiment_dir/run.py" --difft "$spike_dir/difftastic/target/release/difft" \
    --baseline "$baseline_difft" --output "$spike_dir/results" --corpus "$experiment_dir/../../tests"
printf '\nPreview: %s/results/preview.html\nBinary: %s/difftastic/target/release/difft\n' "$spike_dir" "$spike_dir"
