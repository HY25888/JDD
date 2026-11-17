# gkd大脑 ProGuard/R8 规则
# 无障碍服务等组件在 AndroidManifest 中声明，R8 会自动保留，无需额外 keep。
# 后续模块若使用反射/序列化，再按需补充 keep 规则。

# kotlinx.serialization 反序列化快照（模块2）：保留 snapshot 包所有类与成员，
# 防止 R8 混淆/裁剪破坏序列化器（属性名由 serializer 硬编码，但需保留类与构造器）。
-keep class com.gkdbrain.app.snapshot.** { *; }

# kotlinx.serialization 持久化历史记录：保留 history 包所有类与成员，
# 防止 R8 混淆/裁剪破坏序列化器（HistoryEntry/LearnState 会写 SharedPreferences）。
-keep class com.gkdbrain.app.history.** { *; }
