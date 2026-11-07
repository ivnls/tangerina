# Tangerina — GPS com interface TUI (laranja)

App Android de GPS com interface de terminal (TUI) em paleta laranja/âmbar estilo CRT,
feito para funcionar **em conjunto com o Organic Maps**:

- O mapa é desenhado em caracteres (box-drawing) a partir de dados vetoriais do
  **OpenStreetMap**, a mesma base do Organic Maps. Os dados vêm da Overpass API e ficam em cache no aparelho para uso offline.
- Rotas e navegação curva-a-curva offline são entregues ao **Organic Maps** pela
  API de URL `om://` (`om://route`, `om://map`, `om://search`).

## Telas
| Aba | Função |
|---|---|
| MAPA | Mapa ASCII: ruas (─ ━ ═), trilhos (╪), rios (≈), água (~), parques ("), prédios, POIs (H + F P R C $…), sua posição (▲ com direção), círculo de precisão, trilha, waypoints (◆) e alvo (⊕). Arraste para mover, pinça para zoom, toque = cursor, **toque longo = definir alvo**. |
| GPS | Lat/Lon (graus decimais e GMS), altitude, precisão, proa, velocidade em dígitos grandes, odômetro, velocidade máxima, tempo em movimento, gravação de trilha e bússola. |
| SAT | Gráfico do céu (azimute/elevação) e barras de sinal por satélite (GPS/GLONASS/Galileo/BeiDou…). |
| WPT | Waypoints: criar, renomear, apagar, navegar, abrir no Organic Maps. |
| NAV | Distância grande até o alvo, rumo, ângulo relativo, ETA e bússola que aponta para o alvo. Botão **ROTA OM** abre a rota no Organic Maps (carro/a pé/bike/transporte). |
| BUSCA | Busca de endereços/lugares no OSM (Nominatim), ou repassa a busca ao Organic Maps. |
| CFG | Fonte, unidades, modo noite, scanlines, brilho CRT, tela ligada, download automático, cache, instalar Organic Maps. |

Extras: exportar GPX (trilha + waypoints), abrir links `geo:` vindos de outros apps,
atalhos de teclado físico (1–7 abas, +/− zoom, setas movem o mapa).

## Compilar
Requer JDK 17+ e Android SDK (platform 35).
```bash
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```
O release é assinado com a chave debug para instalar direto. Troque pela sua chave antes de publicar.

## Limitações
- A gravação de trilha só funciona com o app aberto (não há serviço em segundo plano).
- Os dados do mapa precisam de internet na primeira vez. Use **BAIXAR** no mapa para salvar uma área para uso offline.
- Use os servidores públicos Overpass/Nominatim com moderação.

Dados do mapa © colaboradores do OpenStreetMap (ODbL).
