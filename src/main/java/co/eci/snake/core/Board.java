package co.eci.snake.core;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class Board {
  private final int width;
  private final int height;

  private final Set<Position> mice = ConcurrentHashMap.newKeySet();
  private final Set<Position> obstacles = ConcurrentHashMap.newKeySet();
  private final Set<Position> turbo = ConcurrentHashMap.newKeySet();
  private final Map<Position, Position> teleports = new ConcurrentHashMap<>();

  public enum MoveResult {
    MOVED, ATE_MOUSE, HIT_OBSTACLE, ATE_TURBO, TELEPORTED
  }

  public Board(int width, int height) {
    if (width <= 0 || height <= 0)
      throw new IllegalArgumentException("Board dimensions must be positive");
    this.width = width;
    this.height = height;
    for (int i = 0; i < 6; i++)
      mice.add(randomEmpty());
    for (int i = 0; i < 4; i++)
      obstacles.add(randomEmpty());
    for (int i = 0; i < 3; i++)
      turbo.add(randomEmpty());
    createTeleportPairs(2);
  }

  public int width() {
    return width;
  }

  public int height() {
    return height;
  }

  public Set<Position> mice() {
    return mice;
  }

  public Set<Position> obstacles() {
    return obstacles;
  }

  public Set<Position> turbo() {
    return turbo;
  }

  public Map<Position, Position> teleports() {
    return teleports;
  }

  public MoveResult step(Snake snake) {
    Objects.requireNonNull(snake, "snake");
    
    // Sincronizar acceso compartido a las colecciones
    synchronized (this) {
        var head = snake.head();
        var dir = snake.direction();
        Position next = new Position(head.x() + dir.dx, head.y() + dir.dy).wrap(width, height);

        if (obstacles.contains(next))
            return MoveResult.HIT_OBSTACLE;

        boolean teleported = false;
        if (teleports.containsKey(next)) {
            next = teleports.get(next);
            teleported = true;
        }

        boolean ateMouse = mice.remove(next);
        boolean ateTurbo = turbo.remove(next);

        // Mover serpiente fuera del synchronized
        snake.advance(next, ateMouse);

        if (ateMouse) {
            mice.add(randomEmpty());
            obstacles.add(randomEmpty());
            if (ThreadLocalRandom.current().nextDouble() < 0.2)
                turbo.add(randomEmpty());
        }

        if (ateTurbo)
            return MoveResult.ATE_TURBO;
        if (ateMouse)
            return MoveResult.ATE_MOUSE;
        if (teleported)
            return MoveResult.TELEPORTED;
        return MoveResult.MOVED;
    }
  }

  private void createTeleportPairs(int pairs) {
    for (int i = 0; i < pairs; i++) {
      Position a = randomEmpty();
      Position b = randomEmpty();
      teleports.put(a, b);
      teleports.put(b, a);
    }
  }

  private Position randomEmpty() {
    var rnd = ThreadLocalRandom.current();
    Position p;
    int guard = 0;
    // Aumentar intentos para N alto
    int maxAttempts = width * height * 3; 
    
    do {
        p = new Position(rnd.nextInt(width), rnd.nextInt(height));
        guard++;
        if (guard > maxAttempts) {
            // Si no encuentra posición vacía, buscar la primera disponible
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    Position candidate = new Position(x, y);
                    if (!mice.contains(candidate) && !obstacles.contains(candidate) && 
                        !turbo.contains(candidate) && !teleports.containsKey(candidate)) {
                        return candidate;
                    }
                }
            }
            // Si todo está ocupado, devolver cualquier posición
            return new Position(rnd.nextInt(width), rnd.nextInt(height));
        }
    } while (mice.contains(p) || obstacles.contains(p) || turbo.contains(p) || teleports.containsKey(p));
    return p;
  }
  
}
