# 豆包TTS 接入与重构技术方案 (TTSDouBaoAloudService)

## 目录
1. [背景与旧架构痛点](#1-背景与旧架构痛点)
2. [改版前后的核心差异 (相对 Master 分支)](#2-改版前后的核心差异-相对-master-分支)
3. [核心实现逻辑分析](#3-核心实现逻辑分析)
   - [3.1 音频按段落独立生成](#31-音频按段落独立生成)
   - [3.2 ExoPlayer 媒体序列管理](#32-exoplayer-媒体序列管理)
   - [3.3 预加载策略优化](#33-预加载策略优化)
4. [解决的代码语法与编译踩坑](#4-解决的代码语法与编译踩坑)
5. [后续集成建议](#5-后续集成建议)

---

## 1. 背景与旧架构痛点
在 `master` 主分支中，豆包TTS在渲染长文本时采用的是**粗粒度文本批量合流**策略，即将多个段落拼接为一大段全文本请求网络端，在得到长段音频后交由 `ExoPlayer` 整体消费。
这种处理方式直接导致了两个致命的阅读体验问题：
- **高亮错位（漂移）：** 安卓 UI 需要基于“朗读进度 / 字符长度”计算当前念到了哪个字以同步进行字符高亮翻页。一大段音频由于包含长短不一的静音、停顿，通过总体长宽比计算单字符朗读耗时极不准确，最终高亮文本越读越错位。
- **预加载失控与 OOM 风险：** 大批量合流容易造成音频超大，拉流过慢甚至请求端抛弃。

---

## 2. 改版前后的核心差异 (相对 Master 分支)

在 `fix-doubao` 分支中，我们对 `TTSDouBaoAloudService.kt` 进行了拆帧化改造：

| 维度 | Master 分支 (旧版) | 当前分支 (新版) |
| --- | --- | --- |
| **音频生成粒度** | 批量抓文本，全片段合并为一个长 Audio 流。 | **一一对应，单段落切割：** 以单行为单位独立截取，每段独立生成一个小的纯净音频文件。 |
| **ExoPlayer 调度** | 只挂载少量的 `MediaItem`，内部用大段文本均摊进度。 | 循环添加多个 `MediaItem`（即**媒体序列**），ExoPlayer 本身负责不同音频段的调度无缝衔接。 |
| **高亮进度游标** | 整段长耗时 / 总长度，静音误差会无限积压。 | 每次触发 `onMediaItemTransition` 后更新新段落偏移量 `nextPos`，当前游标计算只针对**单个句子的实际发音时长**，消除了累积误差。 |
| **预下载 (PreDownload)** | 大段吞吐，不可控。 | 仿照 `EdgeTTS` 的成熟标准，切割后通过 `take(10)` 仅平滑预读未来 10 段的数据。 |

---

## 3. 核心实现逻辑分析

### 3.1 音频按段落独立生成
在新代码 `downloadAndPlayAudios()` 内部，我们使用了按 `contentList.indices` 的增量遍历迭代：
```kotlin
for (index in contentList.indices) {
    var content = contentList[index]
    val speakText = content.replace(AppPattern.notReadAloudRegex, "")
    // 单段计算唯一 MD5 指纹缓存
    val fileName = md5SpeakFileName(content)
    if (!isCached(fileName)) {
        getSpeakStream(speakText, fileName)
    }
}
```
这样做确保了 TTS 服务端的并发小包通讯，提高响应速度也降低了拉取失败重试的资源代价。

### 3.2 ExoPlayer 媒体序列管理
每获得一个小段音频，我们就在主线程 `Launch(Main)` 中把它作为一个独立轨道对象增量压入队列：
```kotlin
val mediaItem = MediaItem.Builder()
    .setUri("memory://media/$fileName".toUri())
    .setMediaId(fileName)
    .build()
exoPlayer.addMediaItem(mediaItem)
```

这种分离队列极大地规避了播放完毕清空的 Bug。现在我们在媒体流结束 `Player.STATE_ENDED` 和切歌 `onMediaItemTransition` 之间分开了处理逻辑。只有真正播放完毕才会执行 `exoPlayer.stop()`。

### 3.3 预加载策略优化
针对 `preDownloadAudios()`，通过集合管道流 `splitToSequence` 按行分割，严格过滤后拦截前十条进入下载并发任务：
```kotlin
val preContentList = textChapter.getNeedReadAloud(0, readAloudByPage, 0, 1)...
    .filter { it.isNotEmpty() }
    .take(10) // 参考 EdgeTTS，严格限制前 10 段，防止触发网关限流或 OOM
```

---

## 4. 解决的代码语法与编译踩坑

在调整以上方案并完成 Kotlin 层编译验证中，我们发现并彻底移除了在修改过程中因为漏改引入的原语级语法 Bug：

1. **非法变量命名 (`play:IndexJob` -> `playIndexJob`):**
   批量替换失误导致的语法识别错乱，重新恢复了安全的骆驼拼写法。
2. **越界与边界逻辑错误:**
   错误地对 Int 使用了 In 检测 (`in contentList.size`) 并遍历了 Int 数值 (`in preContentList.size`)，已统一修正为 `in contentList.indices` 及 `< contentList.size`。
3. **Kotlin String Interpolation 高维陷阱:**
   我们在调试插值时触发了 `$index失败` 无法识别抛红：由于 JVM Kotlin 的编译机制视中文字符为合法标志符，导致其实际找寻的是名唤 `index失败` 的对象。已强制改写为 `${index}失败` 隔绝强解析。
4. **强类型捕获参数 (`catch(e.e)`)** 降级恢复为标准的 `e: Exception` 操作。

---

## 5. 后续集成建议

既然编译在 JDK 17 及最新 Android 环境已经 100% SUCCESSFUL，我们推荐：
1. **清理环境缓存：** 如果用户再次由于分支切换面临语法飘红，第一时间执行 `Reload All from Disk` 解决 IDE 本地缓存滞后；
2. **Cookie 热插拔管理：** 对于第 132 行抛出的 `没找到cookie`，目前使用的是 SharedPreferences，推荐后续加入 UI 层的直链刷新，以便 Cookie 过期自动提醒跳转填写。
