package co.eci.snake.ui.legacy;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

import co.eci.snake.concurrency.SnakeRunner;
import co.eci.snake.core.Board;
import co.eci.snake.core.Direction;
import co.eci.snake.core.Position;
import co.eci.snake.core.Snake;
import co.eci.snake.core.engine.GameClock;

public final class SnakeApp extends JFrame {

    private final Board board;
    private final GamePanel gamePanel;
    private final JButton startButton;
    private final JButton pauseButton;
    private final JLabel statusLabel;
    private final JLabel clockLabel;
    private final GameClock clock;
    private final java.util.List<Snake> snakes = new java.util.ArrayList<>();
    private final ExecutorService snakeExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final java.util.List<SnakeRunner> runners = new java.util.ArrayList<>();
    private final Map<Snake, AtomicInteger> deathOrder = new ConcurrentHashMap<>();
    private final Map<Snake, Integer> snakeMaxLengths = new ConcurrentHashMap<>();
    private int nextDeathIndex = 1;
    private volatile boolean isPaused = false;
    private volatile boolean isRunning = false;
    private final Object pauseLock = new Object();
    private long startTime = 0;
    private long pausedTime = 0;
    private long totalPausedTime = 0;
    private int frameCount = 0;
    private long lastFpsUpdate = 0;

    public SnakeApp() {
        super("The Snake Race");
        this.board = new Board(35, 28);

        int N = Integer.getInteger("snakes", 2);
        for (int i = 0; i < N; i++) {
            int x = 2 + (i * 3) % board.width();
            int y = 2 + (i * 2) % board.height();
            var dir = Direction.values()[i % Direction.values().length];
            Snake snake = Snake.of(x, y, dir);
            snakes.add(snake);
            deathOrder.put(snake, new AtomicInteger(0));
            snakeMaxLengths.put(snake, snake.snapshot().size());
        }

        this.gamePanel = new GamePanel(board, () -> snakes, this::getStatusText, this::getElapsedTime);
        this.startButton = new JButton("Start Game");
        this.pauseButton = new JButton("Pause");
        this.pauseButton.setEnabled(false);
        this.statusLabel = new JLabel("Ready to start", SwingConstants.CENTER);
        this.clockLabel = new JLabel("Time: 00:00", SwingConstants.CENTER);

        statusLabel.setFont(new Font("Arial", Font.BOLD, 14));
        clockLabel.setFont(new Font("Arial", Font.BOLD, 16));
        clockLabel.setForeground(Color.BLUE);

        // Panel superior con reloj y estado
        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.add(clockLabel, BorderLayout.NORTH);
        topPanel.add(statusLabel, BorderLayout.SOUTH);

        // Panel inferior con botones
        JPanel buttonPanel = new JPanel(new FlowLayout());
        buttonPanel.add(startButton);
        buttonPanel.add(pauseButton);

        setLayout(new BorderLayout());
        add(topPanel, BorderLayout.NORTH);
        add(gamePanel, BorderLayout.CENTER);
        add(buttonPanel, BorderLayout.SOUTH);

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        pack();
        setLocationRelativeTo(null);

        // Inicializar GameClock a 60 FPS para fluidez visual
        this.clock = new GameClock(16, () -> {
            if (isRunning) {
                frameCount++;
                long currentTime = System.currentTimeMillis();

                // Actualizar FPS cada segundo
                if (currentTime - lastFpsUpdate >= 1000) {
                    lastFpsUpdate = currentTime;
                    frameCount = 0;
                }

                SwingUtilities.invokeLater(() -> {
                    if (!isPaused) {
                        updateClock();
                    }
                    gamePanel.repaint();
                });
            }
        });

        startButton.addActionListener((ActionEvent e) -> startGame());
        pauseButton.addActionListener((ActionEvent e) -> togglePause());

        // Configurar tecla ESPACIO para pausar/reanudar
        gamePanel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("SPACE"), "pause");
        gamePanel.getActionMap().put("pause", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (isRunning) {
                    togglePause();
                }
            }
        });

        // Configurar controles de teclado para jugadores
        setupKeyboardControls();

        setVisible(true);
    }

    private void setupKeyboardControls() {
        var player = snakes.get(0);
        InputMap im = gamePanel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = gamePanel.getActionMap();

        im.put(KeyStroke.getKeyStroke("LEFT"), "left");
        im.put(KeyStroke.getKeyStroke("RIGHT"), "right");
        im.put(KeyStroke.getKeyStroke("UP"), "up");
        im.put(KeyStroke.getKeyStroke("DOWN"), "down");

        am.put("left", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (isRunning && !isPaused) {
                    player.turn(Direction.LEFT);
                }
            }
        });
        am.put("right", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (isRunning && !isPaused) {
                    player.turn(Direction.RIGHT);
                }
            }
        });
        am.put("up", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (isRunning && !isPaused) {
                    player.turn(Direction.UP);
                }
            }
        });
        am.put("down", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (isRunning && !isPaused) {
                    player.turn(Direction.DOWN);
                }
            }
        });

        if (snakes.size() > 1) {
            var p2 = snakes.get(1);
            im.put(KeyStroke.getKeyStroke('A'), "p2-left");
            im.put(KeyStroke.getKeyStroke('D'), "p2-right");
            im.put(KeyStroke.getKeyStroke('W'), "p2-up");
            im.put(KeyStroke.getKeyStroke('S'), "p2-down");

            am.put("p2-left", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    if (isRunning && !isPaused) {
                        p2.turn(Direction.LEFT);
                    }
                }
            });
            am.put("p2-right", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    if (isRunning && !isPaused) {
                        p2.turn(Direction.RIGHT);
                    }
                }
            });
            am.put("p2-up", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    if (isRunning && !isPaused) {
                        p2.turn(Direction.UP);
                    }
                }
            });
            am.put("p2-down", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    if (isRunning && !isPaused) {
                        p2.turn(Direction.DOWN);
                    }
                }
            });
        }
    }

    private void startGame() {
        if (!isRunning) {
            isRunning = true;
            startTime = System.currentTimeMillis();
            totalPausedTime = 0;
            lastFpsUpdate = System.currentTimeMillis();

            // Iniciar runners para cada serpiente (virtual threads como el original)
            for (Snake snake : snakes) {
                SnakeRunner runner = new SnakeRunner(snake, board,
                        () -> isPaused,
                        pauseLock,
                        () -> recordSnakeDeath(snake),
                        () -> updateSnakeMaxLength(snake));
                runners.add(runner);
                snakeExecutor.submit(runner);
            }

            // Iniciar GameClock para repintado fluido (60 FPS)
            clock.start();

            // Actualizar UI
            startButton.setEnabled(false);
            pauseButton.setEnabled(true);
            statusLabel.setText("Game Running");
            statusLabel.setForeground(Color.GREEN);
            updateClock();

            // Forzar repaint inicial
            gamePanel.repaint();
        }
    }

    private void togglePause() {
        synchronized (pauseLock) {
            if (!isRunning)
                return;

            isPaused = !isPaused;

            if (isPaused) {
                // Guardar tiempo de pausa
                pausedTime = System.currentTimeMillis();
                pauseButton.setText("Resume");
                clock.pause();
                statusLabel.setText("Game Paused");
                statusLabel.setForeground(Color.RED);

                // Notificar a todos los runners que están en pausa
                pauseLock.notifyAll();

                // Pequeña espera para asegurar sincronización
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                // Actualizar estado con información consistente
                updateStatus();
                gamePanel.repaint();
            } else {
                // Actualizar tiempo total pausado
                totalPausedTime += (System.currentTimeMillis() - pausedTime);
                pauseButton.setText("Pause");
                clock.resume();
                statusLabel.setText("Game Running");
                statusLabel.setForeground(Color.GREEN);

                // Notificar a todos los runners que pueden continuar
                pauseLock.notifyAll();

                updateStatus();
            }
        }
    }

    private void recordSnakeDeath(Snake snake) {
        deathOrder.get(snake).compareAndSet(0, nextDeathIndex++);
    }

    private void updateSnakeMaxLength(Snake snake) {
        int currentLength = snake.snapshot().size();
        snakeMaxLengths.merge(snake, currentLength, Math::max);
    }

    private void updateStatus() {
        if (!isRunning) {
            statusLabel.setText("Ready to start");
            statusLabel.setForeground(Color.BLACK);
        } else if (isPaused) {
            String status = getStatusText();
            statusLabel.setText(status);
            statusLabel.setForeground(Color.RED);
        } else {
            statusLabel.setText("Game Running");
            statusLabel.setForeground(Color.GREEN);
        }
    }

    private void updateClock() {
        if (startTime > 0) {
            long elapsed = System.currentTimeMillis() - startTime - totalPausedTime;
            long seconds = elapsed / 1000;
            long minutes = seconds / 60;
            seconds = seconds % 60;

            String timeStr = String.format("Time: %02d:%02d", minutes, seconds);
            clockLabel.setText(timeStr);
        }
    }

    private long getElapsedTime() {
        if (startTime == 0)
            return 0;

        if (isPaused) {
            return pausedTime - startTime - totalPausedTime;
        } else {
            return System.currentTimeMillis() - startTime - totalPausedTime;
        }
    }

    private String getStatusText() {
        if (!isRunning) {
            return "Ready to start";
        }

        if (!isPaused) {
            return "Game Running";
        }

        // Encontrar la serpiente viva más larga
        Snake longestLiveSnake = null;
        int maxLiveLength = 0;

        // Encontrar la peor serpiente (la que murió primero)
        Snake worstSnake = null;
        int minDeathOrder = Integer.MAX_VALUE;

        for (Snake snake : snakes) {
            int deathOrderValue = deathOrder.get(snake).get();

            if (deathOrderValue == 0) { // Serpiente viva
                int currentLength = snake.snapshot().size();
                if (currentLength > maxLiveLength) {
                    maxLiveLength = currentLength;
                    longestLiveSnake = snake;
                }
            } else { // Serpiente muerta
                if (deathOrderValue < minDeathOrder) {
                    minDeathOrder = deathOrderValue;
                    worstSnake = snake;
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<html><center><b>GAME PAUSED</b><br>");

        if (longestLiveSnake != null) {
            int snakeIndex = snakes.indexOf(longestLiveSnake) + 1;
            sb.append("Longest Live: <font color='green'><b>Snake ").append(snakeIndex)
                    .append("</b></font> (Length: ").append(maxLiveLength).append(")<br>");
        }

        if (worstSnake != null) {
            int snakeIndex = snakes.indexOf(worstSnake) + 1;
            int worstLength = snakeMaxLengths.getOrDefault(worstSnake, 0);
            sb.append("First Dead: <font color='red'><b>Snake ").append(snakeIndex)
                    .append("</b></font> (Max: ").append(worstLength).append(")");
        }

        if (longestLiveSnake == null && worstSnake == null) {
            sb.append("All snakes alive and equal");
        }

        sb.append("</center></html>");

        return sb.toString();
    }

    @Override
    public void dispose() {
        isRunning = false;
        isPaused = false;

        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }

        snakeExecutor.shutdownNow();
        clock.close();
        super.dispose();
    }

    public static final class GamePanel extends JPanel {
        private final Board board;
        private final Supplier snakesSupplier;
        private final java.util.function.Supplier<String> statusSupplier;
        private final java.util.function.Supplier<Long> timeSupplier;
        private final int cell = 20;

        @FunctionalInterface
        public interface Supplier {
            java.util.List<Snake> get();
        }

        public GamePanel(Board board, Supplier snakesSupplier,
                java.util.function.Supplier<String> statusSupplier,
                java.util.function.Supplier<Long> timeSupplier) {
            this.board = board;
            this.snakesSupplier = snakesSupplier;
            this.statusSupplier = statusSupplier;
            this.timeSupplier = timeSupplier;
            setPreferredSize(new Dimension(board.width() * cell + 1, board.height() * cell + 100));
            setBackground(Color.WHITE);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            var g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // Dibujar información de tiempo en la parte superior
            long elapsedMs = timeSupplier.get();
            long seconds = elapsedMs / 1000;
            long minutes = seconds / 60;
            seconds = seconds % 60;
            String timeStr = String.format("Time: %02d:%02d", minutes, seconds);

            g2.setColor(Color.BLUE);
            g2.setFont(new Font("Arial", Font.BOLD, 14));
            g2.drawString(timeStr, 10, 20);

            // Dibujar estado del juego
            String status = statusSupplier.get();
            g2.setFont(new Font("Arial", Font.PLAIN, 12));

            if (status.contains("PAUSED")) {
                g2.setColor(new Color(255, 240, 240));
                g2.fillRect(0, 25, getWidth(), 50);
                g2.setColor(Color.RED);
            } else if (status.contains("Running")) {
                g2.setColor(Color.GREEN);
            } else {
                g2.setColor(Color.BLACK);
            }

            // Dibujar líneas de cuadrícula
            g2.setColor(new Color(220, 220, 220));
            for (int x = 0; x <= board.width(); x++)
                g2.drawLine(x * cell, 75, x * cell, board.height() * cell + 75);
            for (int y = 0; y <= board.height(); y++)
                g2.drawLine(0, y * cell + 75, board.width() * cell, y * cell + 75);

            // Obstáculos
            g2.setColor(new Color(255, 102, 0));
            for (var p : board.obstacles()) {
                int x = p.x() * cell, y = p.y() * cell + 75;
                g2.fillRect(x + 2, y + 2, cell - 4, cell - 4);
                g2.setColor(Color.RED);
                g2.drawLine(x + 4, y + 4, x + cell - 6, y + 4);
                g2.drawLine(x + 4, y + 8, x + cell - 6, y + 8);
                g2.drawLine(x + 4, y + 12, x + cell - 6, y + 12);
                g2.setColor(new Color(255, 102, 0));
            }

            // Ratones
            g2.setColor(Color.BLACK);
            for (var p : board.mice()) {
                int x = p.x() * cell, y = p.y() * cell + 75;
                g2.fillOval(x + 4, y + 4, cell - 8, cell - 8);
                g2.setColor(Color.WHITE);
                g2.fillOval(x + 8, y + 8, cell - 16, cell - 16);
                g2.setColor(Color.BLACK);
            }

            // Teleports (flechas rojas)
            java.util.Map<Position, Position> tp = board.teleports();
            g2.setColor(Color.RED);
            for (var entry : tp.entrySet()) {
                Position from = entry.getKey();
                int x = from.x() * cell, y = from.y() * cell + 75;
                int[] xs = { x + 4, x + cell - 4, x + cell - 10, x + cell - 10, x + 4 };
                int[] ys = { y + cell / 2, y + cell / 2, y + 4, y + cell - 4, y + cell / 2 };
                g2.fillPolygon(xs, ys, xs.length);
            }

            // Turbo (rayos)
            g2.setColor(Color.BLACK);
            for (var p : board.turbo()) {
                int x = p.x() * cell, y = p.y() * cell + 75;
                int[] xs = { x + 8, x + 12, x + 10, x + 14, x + 6, x + 10 };
                int[] ys = { y + 2, y + 2, y + 8, y + 8, y + 16, y + 10 };
                g2.fillPolygon(xs, ys, xs.length);
            }

            // Serpientes
            var snakes = snakesSupplier.get();
            int idx = 0;
            for (Snake s : snakes) {
                var body = s.snapshot().toArray(new Position[0]);
                for (int i = 0; i < body.length; i++) {
                    var p = body[i];
                    Color base = (idx == 0) ? new Color(0, 170, 0) : new Color(0, 160, 180);
                    int shade = Math.max(0, 40 - i * 4);
                    g2.setColor(new Color(
                            Math.min(255, base.getRed() + shade),
                            Math.min(255, base.getGreen() + shade),
                            Math.min(255, base.getBlue() + shade)));
                    g2.fillRect(p.x() * cell + 2, p.y() * cell + 77, cell - 4, cell - 4);

                    // Dibujar número de serpiente en la cabeza
                    if (i == 0) {
                        g2.setColor(Color.WHITE);
                        g2.setFont(new Font("Arial", Font.BOLD, 10));
                        String num = String.valueOf(idx + 1);
                        FontMetrics fm = g2.getFontMetrics();
                        int textWidth = fm.stringWidth(num);
                        int textHeight = fm.getHeight();
                        g2.drawString(num,
                                p.x() * cell + cell / 2 - textWidth / 2,
                                p.y() * cell + 75 + cell / 2 + textHeight / 4);
                    }
                }
                idx++;
            }
            g2.dispose();
        }
    }

    public static void launch() {
        SwingUtilities.invokeLater(SnakeApp::new);
    }
}