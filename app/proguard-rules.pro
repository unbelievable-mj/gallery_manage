# SwipeGallery R8 混淆规则
#
# 说明：AndroidX / Hilt / Media3 等库自带 consumer rules，通常无需手工 keep。
# 这里只保留崩溃定位所需的最小信息，其余等实际触发 R8 报错时再补。

# 保留行号，便于从崩溃堆栈定位到源码行
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 这些注解只在编译期存在，运行时缺失属正常，抑制 R8 警告
-dontwarn org.jetbrains.annotations.**
-dontwarn kotlinx.coroutines.**
