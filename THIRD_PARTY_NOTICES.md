# 第三方数据与许可

本项目的代码和人工编写的读法表按 MIT 许可发布（见 `LICENSE`）。
下面这些数据来自第三方，**仍按它们各自的许可**使用。
安装包里的 `symbols.tsv` 是把这些数据和本仓库的读法表编译在一起的产物，
其中来自第三方的部分同样适用下列许可。

## liblouis 国际音标盲文表 —— LGPL-2.1-or-later

- 用到的文件：`tools/data/symbols-ipa.tsv` 的盲文点位列，以及由它生成、并入
  `android/app/src/main/assets/symbols.tsv` 的音标条目（类别为 `I` 的行）。
- 来源：[liblouis](https://github.com/liblouis/liblouis) 的 `tables/IPA.utb` 与
  `tables/IPA-unicode-range.uti`。
- 版权：liblouis 贡献者，包括 IPA Braille 的作者 Robert Englebretson。
- 许可：GNU Lesser General Public License 2.1 或更新版本，全文见 `LICENSES/LGPL-2.1.txt`。
- 本项目所做的改动：只取点位；英文音理描述用受控词表机械翻译成中文，
  再按固定规则派生简称。修改后的完整数据就是仓库里的 `tools/data/symbols-ipa.tsv`，
  可以直接替换后用 `tools` 重新生成词典。

## Unicode emoji 与 CLDR —— Unicode License V3

- 用到的数据：`emoji-test.txt`（Unicode 17.0）里的 emoji 序列；
  CLDR 46.0.0 的 `cldr-annotations-full` 与 `cldr-annotations-derived-full` 中文标注。
  这些原始文件不入库，由 `tools` 构建时自动下载。
- 编译进 `symbols.tsv` 的 emoji 序列与未被人工重写的中文标注适用此许可。
- 许可全文见 `LICENSES/Unicode-3.0.txt`。

Copyright © 1991-2026 Unicode, Inc.
