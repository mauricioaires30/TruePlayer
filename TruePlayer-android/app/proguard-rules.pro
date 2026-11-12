# Regras do R8 (encolhe o APK de release). O código do app e as bibliotecas comuns (Compose, Ktor,
# kotlinx.serialization, WorkManager, OkHttp) já trazem as próprias regras; aqui ficam as bibliotecas
# que usam reflexão e quebrariam se o R8 removesse ou renomeasse classes delas.

# Mensagens de erro e stack traces legíveis nos relatos de falha.
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*

# yt-dlp/ffmpeg (youtubedl-android): lê o JSON do yt-dlp com Jackson por reflexão.
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**
-dontwarn org.apache.commons.compress.**

# Leitor/gravador de tags: escolhe classes por formato de arquivo via reflexão.
-keep class org.jaudiotagger.** { *; }
-dontwarn org.jaudiotagger.**
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn javax.swing.**

# JGit: carrega transportes, filtros e mensagens por nome.
-keep class org.eclipse.jgit.** { *; }
-dontwarn org.eclipse.jgit.**
-dontwarn org.slf4j.**
-dontwarn javax.management.**
-dontwarn java.lang.management.**
-dontwarn org.ietf.jgss.**
-dontwarn com.jcraft.jsch.**
-dontwarn org.bouncycastle.**
