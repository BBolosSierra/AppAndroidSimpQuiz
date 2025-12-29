# Simpsons Quiz

![Instalar la app](app/src/main/res/drawable/qr_app.png)

Escanea este código QR con tu móvil para descargar e instalar el APK de la app (asegúrate de tener activada la instalación de aplicaciones de orígenes desconocidos).

---

## Descripción

**Simpsons Quiz** es un juego de preguntas sobre la serie *Los Simpson*, con estética y tipografía inspiradas en la serie.

### Modos de juego

- **Juego solo**
    - Elige tu nombre.
    - Elige el número de preguntas (1–51).
    - Elige la dificultad:
        - **HOMER**: preguntas fáciles.
        - **MARGE**: dificultad media.
        - **LISA**: dificultad alta.
        - **MIXED**: mezcla aleatoria de todas las dificultades.
    - Responde preguntas tipo test y de respuesta escrita.
    - Al final verás tu puntuación total y cuántas preguntas has acertado.

- **Multijugador (2 móviles)**
    - Un jugador crea una sala (host) y el otro se une con el código de la sala.
    - El host elige:
        - Dificultad (HOMER / MARGE / LISA / MIXED).
        - Número de preguntas (1–51).
    - Los turnos van alternando entre los dos jugadores.
    - Al final se muestran las puntuaciones, el ganador, las preguntas jugadas y una imagen distinta según ganes, pierdas o haya empate.

### Características técnicas

- Interfaz construida con **Jetpack Compose**.
- Preguntas cargadas desde un fichero JSON en `assets`.
- Soporte de:
    - Preguntas tipo test (MCQ).
    - Preguntas de respuesta escrita normalizadas (tildes, mayúsculas, espacios, etc.).
- Multijugador online con **Firebase Authentication** (modo anónimo) y **Cloud Firestore**:
    - Salas identificadas por código.
    - Sincronización en tiempo real de preguntas, turnos y puntuaciones.

---