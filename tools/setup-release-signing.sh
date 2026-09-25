#!/usr/bin/env bash
# 一次性：生成正式版签名密钥，并存进 GitHub 仓库的 Secrets。
#
# 用法（在仓库根目录，已经 gh auth login 过）：
#   bash tools/setup-release-signing.sh
#
# 密钥和密码写在你的用户目录下，不进仓库。**务必另外备份这两个文件**：
# 丢了它们，以后发的新版本签名对不上，所有用户都只能卸载重装。
set -euo pipefail

dir="$HOME/ttsproxy-signing"
jks="$dir/release.jks"
pwfile="$dir/password.txt"
alias_name="ttsproxy"

if [ -e "$jks" ]; then
  echo "已经有密钥了：$jks"
  echo "不会覆盖它：换一把新密钥，已经装了的用户就没法直接升级了。"
  exit 1
fi

command -v keytool >/dev/null || { echo "找不到 keytool，需要先装 JDK 17"; exit 1; }
command -v gh >/dev/null || { echo "找不到 gh，需要先装 GitHub CLI 并登录"; exit 1; }

mkdir -p "$dir"
# 只用字母数字，免得不同 shell 转义出问题
password=$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 32)

keytool -genkeypair -v \
  -keystore "$jks" -storetype PKCS12 \
  -alias "$alias_name" -keyalg RSA -keysize 4096 -validity 36500 \
  -storepass "$password" -keypass "$password" \
  -dname "CN=Unicode TTS Proxy"

printf '%s\n' "$password" > "$pwfile"

base64 < "$jks" | tr -d '\n' | gh secret set TTSPROXY_KEYSTORE_BASE64
printf '%s' "$password" | gh secret set TTSPROXY_KEYSTORE_PASSWORD
printf '%s' "$password" | gh secret set TTSPROXY_KEY_PASSWORD
printf '%s' "$alias_name" | gh secret set TTSPROXY_KEY_ALIAS

echo
echo "完成。密钥在 $jks，密码在 $pwfile。"
echo "请现在就把整个 $dir 目录备份到别处（U 盘、网盘），丢了就再也发不了能直接升级的新版。"
