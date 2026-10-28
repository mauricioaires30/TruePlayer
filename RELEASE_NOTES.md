## Novidades do TruePlayer 1.6.2

- **Git só envia:** nada do GitHub vem para a pasta. Se a branch já existir lá com outro histórico, o app pergunta antes de substituir pelo conteúdo da pasta. Também tem "Trocar repositório" e o botão "Enviar" para quando não há nada novo para commitar.
- **Estatísticas, configurações e capas no GitHub:** o commit leva `.trueplayer/stats.json`, `settings.json` (todas as opções, volume e ordenação) e as capas dos álbuns em `.trueplayer/covers/`.
- **Memória no Git:** o envio de músicas grandes não estoura mais a memória do app.
- **Desfazer a cópia do GitHub:** se a ligação da versão anterior copiou músicas do GitHub para a pasta, um botão em Configurações move esses arquivos para a `_lixeira`.
- **Botão de mídia:** fone e botão do sistema voltam a retomar a última música, mesmo com o app fechado.
- **Tutorial de primeira abertura:** quem acabou de instalar vê um passo a passo curto. Quem só atualizou não vê.
- **Atualização ao abrir:** o app confere se há versão nova toda vez que abre e só avisa se houver.
- **Notificações:** o download mostra o nome da música, a porcentagem e a posição ("Música 2 de 5", "Playlist X • 3 de 10"). O aviso de atualização traz as novidades. O envio ao Git mostra etapa, contagem e porcentagem.
- **Telas:** ficha da música mais compacta, editor de tags com a contagem do álbum, botões padronizados e a permissão de instalar atualizações em Configurações.
