#!/usr/bin/env bash
# Copyright (c) Tailscale Inc & AUTHORS
# SPDX-License-Identifier: BSD-3-Clause
#
# OpenRoute: materializes a patched copy of the tailscale.com module at
# third_party/tailscale.com (referenced by the replace directive in go.mod).
# The pinned upstream version comes from go.mod; patches live in
# patches/tailscale.com and are applied in lexical order.

set -euo pipefail

root=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )/.." &> /dev/null && pwd )
cd "$root"

version=$(awk '$1 == "tailscale.com" && $2 ~ /^v/ { print $2; exit }' go.mod)
if [[ -z "$version" ]]; then
    echo "prepare-tailscale-fork: could not find tailscale.com version in go.mod" >&2
    exit 1
fi

dest="third_party/tailscale.com"
stamp="$dest/.openroute-stamp"
want="$version $(cat patches/tailscale.com/*.patch | shasum -a 256 | cut -d' ' -f1)"

if [[ -f "$stamp" && "$(cat "$stamp")" == "$want" ]]; then
    exit 0
fi

src=$(./tool/go mod download -json "tailscale.com@$version" | sed -n 's/^[[:space:]]*"Dir": "\(.*\)",$/\1/p')
if [[ -z "$src" || ! -d "$src" ]]; then
    echo "prepare-tailscale-fork: go mod download failed for tailscale.com@$version" >&2
    exit 1
fi

rm -rf "$dest"
mkdir -p "$(dirname "$dest")"
cp -R "$src" "$dest"
chmod -R u+w "$dest"

for p in patches/tailscale.com/*.patch; do
    echo "prepare-tailscale-fork: applying $p"
    patch -s -p1 -d "$dest" < "$p"
done

echo "$want" > "$stamp"
