# TruePlayer

Player de músicas e editor de tags (ID3) para Android, com download de links do YouTube e SoundCloud.

## Instalar
Baixe o `.apk` mais recente na aba [Releases](https://github.com/mauricioaires30/TruePlayer/releases) e instale no celular (o Android pede para permitir "instalar apps desconhecidas" para o navegador/gerenciador de arquivos).

## Atualizações
O app confere sozinho se existe uma versão nova em Releases (ao abrir, quando a internet volta e uma vez por dia em segundo plano), avisa por notificação e mostra as novidades. Para instalar, o Android pede para permitir que o TruePlayer instale apps e depois confirma a instalação. Músicas, playlists e estatísticas ficam na pasta de músicas e não são alteradas.

Também dá para conferir em Configurações > Verificar atualização.

## Estrutura do repositório
```
TruePlayer-android/   projeto Android (Kotlin + Jetpack Compose)
  app/                código do aplicativo
  gradle/             catálogo de versões das dependências
```
O APK de cada versão não fica no repositório: é publicado em Releases.

## Compilar (para quem clonar o código)
Requer JDK 21 e o Android SDK 36.

```
cd TruePlayer-android
gradle :app:assembleDebug
```

O APK sai em `TruePlayer-android/app/build/outputs/apk/debug/`. Esse APK é assinado com a chave de debug da sua máquina, então **não** atualiza por cima do APK oficial da aba Releases (a assinatura é diferente).
