#!/usr/bin/env bash
# 正式版签名密钥：第一次运行时生成，并存进 GitHub 仓库的 Secrets。
#
# 用法（在仓库根目录，已经 gh auth login 过）：
#   bash tools/setup-release-signing.sh
#
# 已经有密钥时不会重新生成，只把现有的这一把重新传到 GitHub——
# 上传中途失败了，修好后直接重跑就行。
#
# 密钥和密码写在你的用户目录下，不进仓库。**务必另外备份这两个文件**：
# 丢了它们，以后发的新版本签名对不上，所有用户都只能卸载重装。
set -euo pipefail
trap 'echo "出错了，停在第 $LINENO 行。修好后直接重跑即可，已有的密钥不会被覆盖。" >&2' ERR

dir="$HOME/ttsproxy-signing"
jks="$dir/release.jks"
pwfile="$dir/password.txt"
alias_name="ttsproxy"

command -v keytool >/dev/null || { echo "找不到 keytool，需要先装 JDK 17"; exit 1; }
command -v gh >/dev/null || { echo "找不到 gh，需要先装 GitHub CLI 并登录"; exit 1; }

# 这次运行的输出同时记一份，出错时不用再靠耳朵抓那一闪而过的报错。脚本从不打印密码和密钥。
mkdir -p "$dir"
log="$dir/last-run.log"
exec > >(tee "$log") 2>&1
echo "运行时间：$(date)  用户：$(whoami)  目录：$(pwd)"

# 明确指定仓库，不依赖 gh 自己从当前目录去猜
if ! repo=$(gh repo view --json nameWithOwner --jq .nameWithOwner 2>&1); then
  echo "查不到 GitHub 仓库。gh 的原话是："
  echo "$repo"
  echo "---- 诊断 ----"
  gh auth status 2>&1 || true
  git remote -v 2>&1 || true
  git status --short 2>&1 | head -3 || true
  echo "完整输出已记在 $log"
  exit 1
fi
echo "目标仓库：$repo"

if [ -e "$jks" ]; then
  [ -s "$pwfile" ] || { echo "有密钥但找不到密码文件 $pwfile，没法继续"; exit 1; }
  password=$(tr -d '\r\n' < "$pwfile")
  echo "已经有密钥了，不重新生成，只把它重新传到 GitHub。"
else
  mkdir -p "$dir"
  # 只用字母数字，免得不同 shell 转义出问题。
  # head 取够就关管道，tr 会因此收到 SIGPIPE；这一行得单独关掉 pipefail，否则脚本在这里一声不吭地退出
  password=$(set +o pipefail; LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 32)
  if [ ${#password} -ne 32 ]; then
    echo "生成密码失败" >&2
    exit 1
  fi
  keytool -genkeypair -v \
    -keystore "$jks" -storetype PKCS12 \
    -alias "$alias_name" -keyalg RSA -keysize 4096 -validity 36500 \
    -storepass "$password" -keypass "$password" \
    -dname "CN=Unicode TTS Proxy"
  printf '%s\n' "$password" > "$pwfile"
  echo "密钥已生成。"
fi

# 先确认密码和密钥对得上，别把一对不匹配的东西传上去
keytool -list -keystore "$jks" -storepass "$password" -alias "$alias_name" >/dev/null

# 用 --body 直接给值，不走标准输入：在交互式终端里，gh 从管道读值不可靠
keystore_b64=$(base64 < "$jks" | tr -d '\r\n')
upload() {
  local name="$1" value="$2" n="$3"
  local out
  if ! out=$(gh secret set "$name" --repo "$repo" --body "$value" 2>&1); then
    echo "第 $n 项 $name 没存上。GitHub 的原话是：" >&2
    echo "$out" >&2
    exit 1
  fi
  echo "已存好第 $n 项，共 4 项：$name"
}
upload TTSPROXY_KEYSTORE_BASE64 "$keystore_b64" 1
upload TTSPROXY_KEYSTORE_PASSWORD "$password" 2
upload TTSPROXY_KEY_PASSWORD "$password" 3
upload TTSPROXY_KEY_ALIAS "$alias_name" 4

echo
echo "完成。密钥在 $jks，密码在 $pwfile。"
echo "请现在就把整个 $dir 目录备份到别处（U 盘、网盘），丢了就再也发不了能直接升级的新版。"
