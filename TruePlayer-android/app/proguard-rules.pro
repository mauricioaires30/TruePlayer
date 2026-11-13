# Regras do R8 (encolhe o APK de release). O código do app e as bibliotecas comuns (Compose, WorkManager)
# já trazem as próprias regras; aqui ficam as bibliotecas que usam reflexão e quebrariam se o R8 removesse
# ou renomeasse classes delas.

# Mensagens de erro e stack traces legíveis nos relatos de falha.
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*

# Código do próprio app: pequeno, e mantê-lo inteiro evita surpresas (nomes de classe em logs, serialização).
-keep class dev.truetagger.** { *; }

# Rede e JSON da busca de tags/letras (Apple, Deezer, Genius, LRCLIB). Uma falha aqui é silenciosa
# (vira "música sem tags"), então ficam inteiros: custam pouco no tamanho.
-keep class io.ktor.** { *; }
-keep class kotlinx.serialization.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-dontwarn io.ktor.**
-dontwarn org.slf4j.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

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
-dontwarn javax.management.**
-dontwarn java.lang.management.**
-dontwarn org.ietf.jgss.**
-dontwarn com.jcraft.jsch.**
