# الحفاظ على فئات Room
-keep class androidx.room.** { *; }
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.**

# الحفاظ على osmdroid
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# الحفاظ على MPAndroidChart
-keep class com.github.mikephil.charting.** { *; }
-dontwarn com.github.mikephil.charting.**

# الحفاظ على Coroutines
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# الحفاظ على جميع فئات التطبيق
-keep class com.example.cellularmapper.** { *; }
