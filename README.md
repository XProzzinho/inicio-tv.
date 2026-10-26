# Início TV

Tela inicial limpa, estilo Apple TV, para Android TV (feita para uma TCL com Android TV 11).

- Relógio e data no topo, nome do app em foco em destaque
- Favoritos no topo, depois todos os apps, sem anúncios nem recomendações
- Segure **OK** (ou aperte **Menu**) num app para: favoritar, mudar de posição, ocultar ou ver informações
- Apps ocultos ficam em **Sistema → Apps ocultos**, para mostrar de novo quando quiser

O APK é gerado automaticamente pelo GitHub a cada mudança no código. A versão mais nova fica sempre em:

```
https://github.com/XProzzinho/inicio-tv./releases/latest/download/inicio-tv.apk
```

## 1. Instalar na TV (sem PC)

1. Na TV, abra a Play Store e instale o app **Downloader** (ícone laranja, da AFTVnews).
2. Em **Configurações → Preferências do dispositivo → Segurança e restrições → Fontes desconhecidas**, ative o **Downloader**.
3. Abra o Downloader, digite o link acima e confirme. Quando terminar, toque em **Instalar**.

Para atualizar depois, é só repetir o passo 3: instala por cima e mantém seus favoritos.

## 2. Deixar o Início como tela padrão

No Android TV 11 o botão Home sempre volta para a tela do Google. Para trocar, é preciso desativar essa tela usando ADB, que dá para fazer pelo celular:

1. **Na TV:** Configurações → Preferências do dispositivo → Sobre → clique 7 vezes em **Build** (ou "Versão da compilação") até aparecer "Você é um desenvolvedor".
2. Volte em Preferências do dispositivo → **Opções do desenvolvedor** → ative **Depuração USB** e **Depuração por rede/ADB pela rede** (o nome varia).
3. Veja o IP da TV em Configurações → Rede → (sua rede).
4. **No celular (na mesma rede Wi-Fi):** instale o app **Bugjaeger** (ou outro app de ADB), conecte no IP da TV, e aceite o aviso "Permitir depuração" que aparece na TV.
5. No terminal (shell) do app, rode:

```
pm disable-user --user 0 com.google.android.tvlauncher
```

6. Aperte Home no controle. Se perguntar qual app usar, escolha **Início → Sempre**.

### Desfazer (voltar a tela original)

```
pm enable com.google.android.tvlauncher
```

## 3. Limpar a TV (apps desnecessários)

Primeiro liste o que está instalado:

```
pm list packages
```

Para cada app que quiser tirar, use **desativar** (dá para voltar atrás), nunca desinstalar de sistema:

```
pm disable-user --user 0 NOME.DO.PACOTE
```

Para reativar: `pm enable NOME.DO.PACOTE`

**Não desative:** nada com `settings`, `packageinstaller`, `inputmethod` (teclado), `tv.settings`, `bluetooth`, `systemui`, `tcl.tvinput`/`tvinput`/`livetv` (entradas HDMI e canais) nem a Play Store/Google Play Services se você usa a loja.
