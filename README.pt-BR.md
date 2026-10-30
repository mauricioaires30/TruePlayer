# TruePlayer

[English](README.md) · **Português (Brasil)**

Player de músicas e editor de tags para Android. Toca os arquivos da sua pasta de músicas, baixa músicas por link, acerta as tags sozinho e pode guardar tudo num repositório do GitHub. Sem conta, sem anúncios, sem rastreamento.

## Recursos
- **Player:** biblioteca, álbuns, artistas, gêneros, playlists, fila, aleatório, repetir, controles na tela de bloqueio e nos fones.
- **Editor de tags:** título, artista, álbum, capa, ano, faixa, gênero e mais.
- **Download por link:** compartilhe um link do YouTube, SoundCloud ou Spotify (música, álbum ou playlist). O áudio do Spotify é protegido, então cada música é achada no YouTube Music, no YouTube ou no SoundCloud (até 100 músicas por playlist do Spotify). Dá para cancelar uma música ou a playlist inteira pela notificação.
- **Tags automáticas:** depois do download, procura a música no Apple Music, Deezer e Genius e preenche tags e capa. Se não tiver certeza, deixa como está.
- **Estatísticas:** músicas, álbuns e artistas mais ouvidos por minutos ou por vezes tocadas, na semana, no mês, em 6 meses, no ano ou em todo o tempo.
- **Pasta portátil:** tudo fica na pasta que você escolhe (`songs/`, `playlists/` e `.trueplayer/` com estatísticas e configurações). Trocar de celular não perde nada.
- **Git:** commit e envio da pasta para o GitHub, em segundo plano, retomando se falhar.
- **Tema claro e escuro, 21 idiomas** (seguindo o celular ou a sua escolha). As traduções não foram revisadas por falantes nativos, correções são bem-vindas.
- **Atualização automática** pelas [Releases](https://github.com/mauricioaires30/TruePlayer/releases).

## Instalação
1. No celular, baixe o `.apk` mais recente em [Releases](https://github.com/mauricioaires30/TruePlayer/releases).
2. Abra o arquivo e permita "instalar apps desconhecidos" para o app com que abriu.
3. Na primeira abertura, escolha a pasta de músicas e conceda as permissões pedidas.

Precisa do Android 8.0 ou mais novo, em celular de 64 bits (arm64). Depois da primeira instalação o app se atualiza sozinho.

## Permissões e privacidade
Arquivos de áudio (tocar as músicas), notificações (player, downloads, atualizações), internet (downloads, busca de tags, atualizações, GitHub), instalar apps desconhecidos (atualização do app) e acesso a todos os arquivos **só** para o Git. Desligar a otimização de bateria é opcional.

Biblioteca, playlists e estatísticas ficam no seu celular. O app só usa a internet nos recursos que precisam: downloads, busca de tags e capas (só vão o nome da música e do artista), verificação de atualizações e GitHub (o token fica criptografado no celular).

## Compilar
Precisa de JDK 21 e Android SDK 36.

```
cd TruePlayer-android
gradle :app:assembleDebug
gradle :app:testDebugUnitTest
```

O APK de debug é assinado com a chave da sua máquina, então não atualiza por cima do oficial. Os APKs oficiais são compilados, assinados e publicados pelo GitHub Actions (`.github/workflows/`) a partir do `RELEASE_NOTES.md`.

## Licença
Copyright © 2026 mauricioaires30. Distribuído sob a **GNU GPL v3** (veja [LICENSE](LICENSE)): você pode usar, modificar e compartilhar, desde que o resultado continue em GPL-3.0 com o código-fonte disponível. Sem garantia. A GPL é necessária porque o app inclui o [youtubedl-android](https://github.com/JunkFood02/youtubedl-android) e o FFmpeg (GPL-3.0).

Os outros componentes mantêm suas licenças: [yt-dlp](https://github.com/yt-dlp/yt-dlp) (Unlicense), [jaudiotagger](https://github.com/ijabz/jaudiotagger) (LGPL), [JGit](https://github.com/eclipse-jgit/jgit) (BSD-3-Clause), e Kotlin, Jetpack Compose, AndroidX, Ktor, kotlinx.* (Apache-2.0).

**Aviso sobre conteúdo:** o TruePlayer é uma ferramenta. Você é responsável por baixar apenas conteúdo que tem o direito de baixar e por seguir os termos dos serviços de onde vêm os links. O projeto não é afiliado nem endossado por YouTube, Google, SoundCloud, Spotify, Apple, Deezer, Genius ou GitHub. Este texto não é aconselhamento jurídico.
