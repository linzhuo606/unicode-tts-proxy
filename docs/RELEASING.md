# 发布正式版

正式版由 GitHub Actions 自动构建、签名，并挂到仓库的 Releases 页面。
普通用户从 Releases 下载安装，以后每一版都能直接覆盖升级，自定义词典和设置都保留。

## 第一次：准备签名密钥

只做一次。在仓库根目录运行：

```bash
bash tools/setup-release-signing.sh
```

它会在你的用户目录下建 `ttsproxy-signing` 文件夹，里面是密钥和密码，
同时把它们存进 GitHub 仓库的 Secrets（名字都以 `TTSPROXY_` 开头）。

**做完立刻把 `ttsproxy-signing` 整个文件夹备份到别处。** 这把密钥丢了，
以后发的版本签名对不上，所有用户都只能卸载重装，自定义词典和设置全丢。

密钥绝不能放进仓库：拿到它的人能做出一个「升级包」替换掉这个引擎，
而读屏引擎听得到用户锁屏时输入的每一个字。

## 每次发版

1. 改 `android/app/build.gradle.kts` 里的两个数字：
   `versionCode` 加一，`versionName` 改成新版本号（比如 `0.2.0`）。
2. 提交并推送。
3. 打一个和版本号一致的标签并推送：

   ```bash
   git tag v0.2.0
   git push origin v0.2.0
   ```

Actions 会先跑 core 测试，再构建签名的正式版，建好 Release 并附上
`unicode-tts-proxy-v0.2.0.apk`。标签和 `versionName` 对不上时会直接报错，不会发出去。

## 本机测试版和正式版

本机编译的包（连 debug 包）现在都用 `~/ttsproxy-signing` 里的正式密钥签名，和 Releases 上的
正式版可以互相覆盖安装。只有从更早的调试签名版本换过来时才需要卸载一次，
卸载前在应用里的「自定义词典」导出一份，装好再导入。

**卸载重装之后，华为等系统会把「应用启动管理」清回自动管理，开机后朗读会读到一半没声。**
装好后在本应用设置里点「检查系统的应用启动管理」，改成手动管理并打开三项允许。
