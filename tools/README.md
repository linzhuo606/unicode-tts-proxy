# 词典生成器

把 Unicode 官方数据和人工校对过的理科读法，编译成 APK assets 里的 `symbols.tsv`。

## 数据来源

| 数据 | 来源 | 版本 | 许可 |
|---|---|---|---|
| emoji 序列全集 | `unicode.org/Public/emoji/<ver>/emoji-test.txt` | 17.0 | Unicode License |
| emoji 中文名 | cldr-json `cldr-annotations-full/annotations/zh` | 46.0.0 | Unicode License |
| ZWJ / 肤色组合名 | cldr-json `cldr-annotations-derived-full` | 46.0.0 | Unicode License |
| 人工读法表 | `data/symbols-*.tsv`（数学、理化、emoji、音标、盲文、制表符等） | 人工校对 | 本仓库；音标点位为 LGPL-2.1+，见 `THIRD_PARTY_NOTICES.md` |
| 音标补充包 | `data/ipa-letters.tsv`，**不编进内置词典**，给用户按需导入 | 人工校对 | 同上 |

emoji-test.txt 用来做**分词**：它列出了所有合法的 emoji 序列，据此建 Trie 做最长匹配，
就不必依赖设备上 `android.icu` 的 Unicode 版本——那个版本随 Android 版本浮动，
老机器切不对新 emoji。

## 运行

    ./gradlew run --args="<数据目录> <人工词表目录> <输出文件>"

数据目录里缺文件时会自动按上表的固定版本下载。

## 输出格式

每行 `码位序列<TAB>类别<TAB>简洁读法<TAB>详细读法`，类别是 E（emoji）/ M（数学）/ P（理化）/ S（其他）/ B（盲文）/ I（音标）。
