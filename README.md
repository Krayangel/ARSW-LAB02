# Snake Race — ARSW Lab #2 (Java 21, Virtual Threads)

**Escuela Colombiana de Ingeniería – Arquitecturas de Software**  
Laboratorio de programación concurrente: condiciones de carrera, sincronización y colecciones seguras.

---

## Requisitos

- **JDK 21** (Temurin recomendado)
- **Maven 3.9+**
- SO: Windows, macOS o Linux

---

## Cómo ejecutar

```bash
mvn clean verify
mvn -q -DskipTests exec:java -Dsnakes=4
```

- `-Dsnakes=N` → inicia el juego con **N** serpientes (por defecto 2).
- **Controles**:
  - **Flechas**: serpiente **0** (Jugador 1).
  - **WASD**: serpiente **1** (si existe).
  - **Espacio** o botón **Action**: Pausar / Reanudar.

---

## Reglas del juego (resumen)

- **N serpientes** corren de forma autónoma (cada una en su propio hilo).
- **Ratones**: al comer uno, la serpiente **crece** y aparece un **nuevo obstáculo**.
- **Obstáculos**: si la cabeza entra en un obstáculo hay **rebote**.
- **Teletransportadores** (flechas rojas): entrar por uno te **saca por su par**.
- **Rayos (Turbo)**: al pisarlos, la serpiente obtiene **velocidad aumentada** temporal.
- Movimiento con **wrap-around** (el tablero “se repite” en los bordes).

---

## Arquitectura (carpetas)

```
co.eci.snake
├─ app/                 # Bootstrap de la aplicación (Main)
├─ core/                # Dominio: Board, Snake, Direction, Position
├─ core/engine/         # GameClock (ticks, Pausa/Reanudar)
├─ concurrency/         # SnakeRunner (lógica por serpiente con virtual threads)
└─ ui/legacy/           # UI estilo legado (Swing) con grilla y botón Action
```

---

# Actividades del laboratorio

## Parte I — (Calentamiento) `wait/notify` en un programa multi-hilo

1. Toma el programa [**PrimeFinder**](https://github.com/ARSW-ECI/wait-notify-excercise).

  -> El programa se instalo correctamente y descargo como repositorio  

  ![](img/parte_1_a.png)

2. Modifícalo para que **cada _t_ milisegundos**:
   - Se **pausen** todos los hilos trabajadores.

      Esto ocurre en la clase 'Control' dentro del hilo controlador que duerme TMILISECONDS, y luego, solicita la pausa, el Control es el único que decide cuándo pausar, por eso centraliza el tiempo y no los hilos trabajadores, al cumplirse el intervalo, el controlador cambia una condición compartida de paused = true protegida por un monitor común y fuerza a que los hilos trabajadores entren en espera.

   - Se **muestre** cuántos números primos se han encontrado.

      Esto sucede en Control, justo después de pausar los hilos, el controlador recorre el arreglo de PrimeFinderThread y consulta el tamaño de la lista de primos encontrada por cada hilo, como los hilos ya están pausados, la lectura es consistente y no hay condiciones de carrera, la suma total se imprime antes de pedir ENTER.

   - El programa **espere ENTER** para **reanudar**.

      Esto ocurre en Control, usando un Scanner o System.in.read(), el hilo controlador se bloquea esperando la entrada del usuario mientras los hilos trabajadores permanecen detenidos en wait(), no hay espera activa, el programa está completamente suspendido hasta que el usuario presiona ENTER.

3. La sincronización debe usar **`synchronized`**, **`wait()`**, **`notify()` / `notifyAll()`** sobre el **mismo monitor** (sin _busy-waiting_).
4. Entrega en el reporte de laboratorio **las observaciones y/o comentarios** explicando tu diseño de sincronización (qué lock, qué condición, cómo evitas _lost wakeups_).

> Objetivo didáctico: practicar suspensión/continuación **sin** espera activa y consolidar el modelo de monitores en Java.

---

## Parte II — SnakeRace concurrente (núcleo del laboratorio)

### 1) Análisis de concurrencia

- Explica **cómo** el código usa hilos para dar autonomía a cada serpiente.

    El código usa hilos para dar autonomía a cada serpiente mediante la clase SnakeRunner del paquete concurrency, cada instancia corre en su propio hilo, lo que permite que el movimiento y decisiones de cada serpiente sean independientes del resto y del hilo principal, coordinándose solo con el GameClock, así, el paralelismo facilita simular múltiples entidades activas en el tablero de forma concurrente, mejorando la escalabilidad y claridad del diseño.

- **Identifica** y documenta en **`el reporte de laboratorio`**:
  - Posibles **condiciones de carrera**.

        Existen posibles condiciones de carrera en el acceso a estructuras compartidas como el tablero (Board) y el estado interno de cada serpiente (Snake), aunque cada serpiente es gestionada por su propio hilo estas pueden interactuar con recursos comunes, por ejemplo al consultar límites del tablero, posiciones ocupadas o al momento de renderizar el estado en la interfaz gráfica, además en la clase Snake el cuerpo no está protegido explícitamente por sincronización lo que puede generar inconsistencias si es leído por la UI o por otra parte del sistema mientras el hilo de la serpiente lo está modificando, el uso de volatile en la dirección mitiga parcialmente problemas de visibilidad pero no elimina por completo el riesgo de accesos concurrentes no coordinados sobre el estado interno.

  - **Colecciones** o estructuras **no seguras** en contexto concurrente.

        La clase Snake usa un ArrayDeque para el cuerpo de la serpiente, una estructura que no es thread-safe, y aunque se supone que solo el hilo de la serpiente lo modifica, otras partes del sistema como la interfaz gráfica o el motor del juego pueden acceder a copias del estado mediante métodos como snapshot(), lo que puede generar lecturas inconsistentes si no se coordina con las modificaciones, además cualquier colección compartida dentro de Board sin sincronización explícita se convierte en un punto crítico en un entorno concurrente.

  - Ocurrencias de **espera activa** (busy-wait) o de sincronización innecesaria.

        El diseño reduce la espera activa gracias a GameClock, que centraliza el control del tiempo y coordina la pausa y reanudación de las serpientes, sin embargo puede surgir riesgo de espera activa si los hilos consultan repetidamente el estado del juego sin usar mecanismos como wait() y notify(), si el reloj emplea sincronización con monitores y notifica solo en cada tick o cambio de estado la solución es eficiente y evita el busy-waiting, en cambio cualquier bucle que revise constantemente el estado sin bloquear el hilo sería una espera activa innecesaria y debería corregirse para optimizar el uso de CPU.

### 2) Correcciones mínimas y regiones críticas

- **Elimina** esperas activas reemplazándolas por **señales** / **estados** o mecanismos de la librería de concurrencia.
- Protege **solo** las **regiones críticas estrictamente necesarias** (evita bloqueos amplios).
- Justifica en **`el reporte de laboratorio`** cada cambio: cuál era el riesgo y cómo lo resuelves.

### 3) Control de ejecución seguro (UI)

- Implementa la **UI** con **Iniciar / Pausar / Reanudar** (ya existe el botón _Action_ y el reloj `GameClock`).
- Al **Pausar**, muestra de forma **consistente** (sin _tearing_):
  - La **serpiente viva más larga**.
  - La **peor serpiente** (la que **primero murió**).
- Considera que la suspensión **no es instantánea**; coordina para que el estado mostrado no quede “a medias”.

### 4) Robustez bajo carga

- Ejecuta con **N alto** (`-Dsnakes=20` o más) y/o aumenta la velocidad.
- El juego **no debe romperse**: sin `ConcurrentModificationException`, sin lecturas inconsistentes, sin _deadlocks_.
- Si habilitas **teleports** y **turbo**, verifica que las reglas no introduzcan carreras.

> Entregables detallados más abajo.

---

## Entregables

1. **Código fuente** funcionando en **Java 21**.
2. Todo de manera clara en **`**el reporte de laboratorio**`** con:
   - Data races encontradas y su solución.
   - Colecciones mal usadas y cómo se protegieron (o sustituyeron).
   - Esperas activas eliminadas y mecanismo utilizado.
   - Regiones críticas definidas y justificación de su **alcance mínimo**.
3. UI con **Iniciar / Pausar / Reanudar** y estadísticas solicitadas al pausar.

---

## Criterios de evaluación (10)

- (3) **Concurrencia correcta**: sin data races; sincronización bien localizada.
- (2) **Pausa/Reanudar**: consistencia visual y de estado.
- (2) **Robustez**: corre **con N alto** y sin excepciones de concurrencia.
- (1.5) **Calidad**: estructura clara, nombres, comentarios; sin _code smells_ obvios.
- (1.5) **Documentación**: **`reporte de laboratorio`** claro, reproducible;

---

## Tips y configuración útil

- **Número de serpientes**: `-Dsnakes=N` al ejecutar.
- **Tamaño del tablero**: cambiar el constructor `new Board(width, height)`.
- **Teleports / Turbo**: editar `Board.java` (métodos de inicialización y reglas en `step(...)`).
- **Velocidad**: ajustar `GameClock` (tick) o el `sleep` del `SnakeRunner` (incluye modo turbo).

---

## Cómo correr pruebas

```bash
mvn clean verify
```

Incluye compilación y ejecución de pruebas JUnit. Si tienes análisis estático, ejecútalo en `verify` o `site` según tu `pom.xml`.

---

## Créditos

Este laboratorio es una adaptación modernizada del ejercicio **SnakeRace** de ARSW. El enunciado de actividades se conserva para mantener los objetivos pedagógicos del curso.

**Base construida por el Ing. Javier Toquica.**
