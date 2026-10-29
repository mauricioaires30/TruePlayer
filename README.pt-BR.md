# TruePlayer

[English](README.md) · **Português (Brasil)**

Player de músicas e editor de tags para Android. Toca os arquivos da sua pasta de músicas, mostra estatísticas do que você ouve, baixa músicas de links do YouTube e do SoundCloud (inclusive playlists), acerta as tags sozinho e pode guardar tudo num repositório do GitHub.

## O que faz
- **Biblioteca e player:** músicas, álbuns, artistas, gêneros e playlists, com fila, aleatório, repetir e controle pelo fone, pela tela de bloqueio e pelo botão de mídia do sistema. A música que está tocando ganha ondas animadas no item e uma barra de progresso ondulada.
- **Editor de tags:** título, artista, álbum, capa, ano, faixa, gênero e mais.
- **Downloads por link:** compartilhe um link do YouTube ou do SoundCloud para o TruePlayer. Link de playlist vira uma playlist do app, com as músicas na ordem original, baixadas uma por vez.
- **Ajuste automático de tags:** depois do download, procura a música no Apple Music, no Deezer e no Genius e preenche título, artista, álbum e capa. Se não achar com certeza, não mexe nas tags.
- **Estatísticas:** tempo ouvido e vezes tocadas por música, álbum e artista (semana, mês, 6 meses, ano e sempre).
- **Pasta organizada e portátil:** tudo fica na pasta que você escolher (`songs/` para músicas, `playlists/` para as playlists em `.m3u`, `.trueplayer/` para estatísticas e configurações). Trocar de celular não perde nada.
- **Git:** guarda a pasta num repositório do GitHub. Os botões Commitar e Enviar ficam separados, o envio roda em segundo plano (com andamento na notificação) e vai em partes, continuando de onde parou se falhar. Junto vão as estatísticas, as configurações e as capas dos álbuns. "Baixar do GitHub" restaura tudo num celular novo.
- **Atualização automática:** confere as versões novas em [Releases](https://github.com/mauricioaires30/TruePlayer/releases), mostra as novidades e instala depois da sua confirmação.

## Instalar
1. Abra a aba [Releases](https://github.com/mauricioaires30/TruePlayer/releases) no celular e baixe o `.apk` mais recente.
2. Abra o arquivo. O Android vai pedir para permitir "instalar apps desconhecidos" para o app que você usou para abrir (navegador ou gerenciador de arquivos).
3. Na primeira abertura, escolha a pasta de músicas e conceda as permissões que o app pedir.

Requer Android 8.0 ou mais novo, em celulares de 64 bits (arm64). Depois da primeira instalação, o app se atualiza sozinho.

## Permissões
| Permissão | Para que serve |
|---|---|
| Arquivos de áudio | Listar e tocar as músicas da pasta |
| Notificações | Controles do player, progresso dos downloads e aviso de atualização |
| Internet | Baixar músicas, buscar tags e capas, conferir atualizações e enviar ao GitHub |
| Instalar apps desconhecidos | Instalar as atualizações baixadas pelo próprio app |
| Acesso a todos os arquivos | **Só** para o recurso de Git, que precisa trabalhar direto nos arquivos da pasta |
| Rodar em segundo plano (sem otimização de bateria) | Opcional: downloads, envio ao GitHub e avisos de atualização continuam com o app fechado |

## Privacidade
Não há conta, cadastro, anúncio nem rastreamento. Sua biblioteca, playlists e estatísticas ficam no seu celular, na pasta que você escolheu. O app só acessa a internet quando você usa um recurso que precisa dela:
- downloads (YouTube/SoundCloud);
- busca de tags e capas (Apple Music, Deezer e Genius), que recebe só o nome da música e do artista;
- conferência de atualizações (GitHub);
- envio ao GitHub, quando você usa o Git (o token fica cifrado e guardado só no celular).

## Estrutura do repositório
```
TruePlayer-android/   projeto Android (Kotlin + Jetpack Compose)
  app/                código do aplicativo
  gradle/             catálogo de versões das dependências
LICENSE               licença (GNU GPL v3)
RELEASE_NOTES.md      novidades da versão (viram o texto da release)
.github/workflows/    publicação automática das releases
```
O APK de cada versão não fica no repositório: o GitHub Actions compila, assina e publica em Releases a cada push com versão nova.

## Compilar
Requer JDK 21 e o Android SDK 36.

```
cd TruePlayer-android
gradle :app:assembleDebug
gradle :app:testDebugUnitTest
```

O APK sai em `TruePlayer-android/app/build/outputs/apk/debug/`. Esse APK é assinado com a chave de debug da sua máquina, então **não** atualiza por cima do APK oficial de Releases (a assinatura é diferente).

## Direitos e licença
Copyright © 2026 mauricioaires30. Todos os direitos autorais do código do TruePlayer pertencem ao autor.

O TruePlayer é distribuído sob a **GNU General Public License, versão 3** (veja o arquivo [LICENSE](LICENSE)). Na prática:
- você pode usar, estudar, copiar e modificar o app, inclusive compartilhar suas modificações, **desde que** mantenha o aviso de copyright e distribua o resultado também sob a GPL-3.0, com o código-fonte disponível;
- não há garantia de nenhum tipo (veja as seções 15 e 16 da licença).

**Por que GPL-3.0 e não "todos os direitos reservados":** o TruePlayer usa a biblioteca `youtubedl-android` e o FFmpeg que ela traz, que são GPL-3.0. Código que os incorpora e é distribuído precisa seguir a mesma licença. Isso não tira a autoria do código original, mas impede restringir quem recebe o app.

### Componentes de terceiros
Cada componente continua sob a licença dele:

| Componente | Licença |
|---|---|
| [youtubedl-android](https://github.com/JunkFood02/youtubedl-android) e FFmpeg (embutido por ela) | GPL-3.0 |
| [yt-dlp](https://github.com/yt-dlp/yt-dlp) | Unlicense |
| [jaudiotagger](https://github.com/ijabz/jaudiotagger) | LGPL |
| [JGit](https://github.com/eclipse-jgit/jgit) | BSD-3-Clause (Eclipse Distribution License) |
| Kotlin, Jetpack Compose, AndroidX (WorkManager e outros), Ktor, kotlinx.* | Apache-2.0 |

O código-fonte completo está neste repositório, o que também atende a exigência da LGPL de permitir trocar a biblioteca jaudiotagger.

### Aviso sobre conteúdo
O TruePlayer é uma ferramenta. **Você é o responsável** por baixar apenas conteúdo que tem o direito de baixar e por respeitar os termos de uso dos serviços de onde vem o link e os direitos autorais dos artistas. O projeto não é afiliado, associado nem endossado por YouTube, Google, SoundCloud, Apple, Deezer, Genius ou GitHub. Esses nomes pertencem aos respectivos donos.

Este texto descreve a intenção do projeto e não é aconselhamento jurídico. Em caso de dúvida sobre licenças, consulte um profissional.
