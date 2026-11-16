# AirTouch 客户端混淆规则（当前 release 未开启 minify，此处保留规则以备启用）
-keep class com.airtouch.app.** { *; }
-keep class org.json.** { *; }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
