## Novidades do TruePlayer 1.6.4

- **Atualização:** o app confere se há versão nova toda vez que você entra nele (não só quando abre do zero). Se você tocar em "Agora não", o aviso daquela versão não volta até abrir o app de novo.
- **Baixar do GitHub:** novo botão em Configurações (com confirmação). Traz para a pasta o que só existe no GitHub e atualiza o que você não mexeu. O que você mudou nunca é sobrescrito. Serve para restaurar músicas, playlists, estatísticas e configurações num celular novo.
- **Envio ao GitHub:** mostra "Aguardando o GitHub confirmar…" depois de chegar a 100%, tem botão Cancelar e desiste se o GitHub não responder em 15 minutos. Envios grandes são divididos em partes (o GitHub recusa mais de ~2 GB de uma vez) e mandados uma de cada vez.
- **Commit mais robusto:** um arquivo que não puder ser lido não derruba mais o commit inteiro; ele fica de fora e o app avisa qual. As mensagens de erro do Git agora mostram a causa real.
