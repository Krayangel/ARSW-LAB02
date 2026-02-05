package co.eci.snake.concurrency;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

import co.eci.snake.core.Board;
import co.eci.snake.core.Direction;
import co.eci.snake.core.Snake;

public final class SnakeRunner implements Runnable {
    private final Snake snake;
    private final Board board;
    private final java.util.function.Supplier<Boolean> isPausedSupplier;
    private final Object pauseLock;
    private final Runnable onDeathCallback;
    private final Runnable onLengthUpdateCallback;
    private final int baseSleepMs = 80;
    private final int turboSleepMs = 40;
    private int turboTicks = 0;
    private final AtomicBoolean isDead = new AtomicBoolean(false);
    private volatile boolean waitingForPause = false;

    public SnakeRunner(Snake snake, Board board,
            java.util.function.Supplier<Boolean> isPausedSupplier,
            Object pauseLock,
            Runnable onDeathCallback,
            Runnable onLengthUpdateCallback) {
        this.snake = snake;
        this.board = board;
        this.isPausedSupplier = isPausedSupplier;
        this.pauseLock = pauseLock;
        this.onDeathCallback = onDeathCallback;
        this.onLengthUpdateCallback = onLengthUpdateCallback;
    }

    @Override
    public void run() {
        try {
            while (!Thread.currentThread().isInterrupted() && !isDead.get()) {
                // Verificar pausa de manera eficiente
                if (isPausedSupplier.get()) {
                    waitingForPause = true;
                    synchronized (pauseLock) {
                        while (isPausedSupplier.get()) {
                            pauseLock.wait();
                        }
                    }
                    waitingForPause = false;
                    continue; // Revisar condiciones después de pausa
                }

                // Solo mover si no está pausado
                maybeTurn();

                // Mover la serpiente
                var res = board.step(snake);

                // Actualizar longitud máxima
                onLengthUpdateCallback.run();

                // Manejar resultado del movimiento
                if (res == Board.MoveResult.HIT_OBSTACLE) {
                    // Registrar muerte
                    if (isDead.compareAndSet(false, true)) {
                        onDeathCallback.run();
                        break; // Salir del loop si la serpiente murió
                    }
                } else if (res == Board.MoveResult.ATE_TURBO) {
                    turboTicks = 100;
                }

                // Calcular tiempo de espera (igual que el original)
                int sleep = (turboTicks > 0) ? turboSleepMs : baseSleepMs;
                if (turboTicks > 0)
                    turboTicks--;

                // Pequeña pausa entre movimientos
                Thread.sleep(sleep);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private void maybeTurn() {
        // Verificar pausa rápidamente
        if (isPausedSupplier.get())
            return;

        double p = (turboTicks > 0) ? 0.05 : 0.10;
        if (ThreadLocalRandom.current().nextDouble() < p)
            randomTurn();
    }

    private void randomTurn() {
        // Verificar pausa rápidamente
        if (isPausedSupplier.get())
            return;

        var dirs = Direction.values();
        snake.turn(dirs[ThreadLocalRandom.current().nextInt(dirs.length)]);
    }

    public boolean isDead() {
        return isDead.get();
    }

    public boolean isWaitingForPause() {
        return waitingForPause;
    }
}