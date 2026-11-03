package com.deltabattle.battle;

import java.util.Random;

/**
 * Детерминированные паттерны пуль Фроггита (как в Undertale).
 * Позиции пуль - чистая функция (type, seed, t), поэтому сервер и клиенты считают их
 * сами и по сети пули не передаются (экономия трафика). Координаты - в пикселях коробки.
 */
public final class Pattern {
    public static final int BOX_W = 140, BOX_H = 140;
    public static final float SOUL_HALF = 4f;            // половина размера сердца
    public static final int FLY_COUNT = 5;

    public static final int TYPE_FLIES = 0;   // 5 мух сверху, зависают, летят в душу
    public static final int TYPE_FROG = 1;    // лягушка прыгает из правого нижнего угла
    public static final int TYPE_FLIES_STRAIGHT = 2; // редкий вариант: мухи плавно вниз

    public static final int[] DURATION = {150, 150, 150}; // тики

    // Кадр пуль. Заполняется collect(); без аллокаций.
    public static final class Frame {
        public final float[] x = new float[16], y = new float[16], r = new float[16];
        public final byte[] kind = new byte[16]; // 0 муха, 1 лягушка
        public int n;
        void add(float px, float py, float pr, int k) { x[n] = px; y[n] = py; r[n] = pr; kind[n++] = (byte) k; }
    }

    public static int pickType(long seed) {
        int v = new Random(seed ^ 0x5DEECE66DL).nextInt(100);
        return v < 8 ? TYPE_FLIES_STRAIGHT : (v < 54 ? TYPE_FLIES : TYPE_FROG);
    }

    public static int launchTick(int i) { return 10 + i * 9; }          // момент появления i-й мухи
    public static int fireTick(int i) { return launchTick(i) + 26; }    // момент, когда муха выбирает цель

    public static float flyStartX(long seed, int i) {
        Random rnd = new Random(seed * 31 + i);
        return 16 + (BOX_W - 32) * (0.1f + 0.8f * ((i * 0.37f + rnd.nextFloat() * 0.25f) % 1f));
    }

    /** aimX/aimY - цели мух (координаты души в момент fireTick), NaN если ещё не известны. */
    public static void collect(Frame f, int type, long seed, float t, float[] aimX, float[] aimY) {
        f.n = 0;
        switch (type) {
            case TYPE_FLIES -> {
                for (int i = 0; i < FLY_COUNT; i++) {
                    float lt = t - launchTick(i);
                    if (lt < 0) continue;
                    float sx = flyStartX(seed, i);
                    float enter = Math.min(lt / 10f, 1f);
                    float ey = -8 + (22 + 8) * easeOut(enter);        // влетает сверху
                    float bob = (float) Math.sin(t * 0.9f + i) * 1.2f; // жужжание
                    float ft = t - fireTick(i);
                    if (ft < 0 || Float.isNaN(aimX[i])) { f.add(sx + bob, ey, 4f, 0); continue; }
                    float dx = aimX[i] - sx, dy = aimY[i] - 22, len = (float) Math.sqrt(dx * dx + dy * dy);
                    if (len < 0.001f) len = 1;
                    float sp = 1.9f * ft;      // пикс/тик
                    f.add(sx + dx / len * sp + bob * (ft < 4 ? 1 : 0), 22 + dy / len * sp, 4f, 0);
                }
            }
            case TYPE_FLIES_STRAIGHT -> {
                for (int i = 0; i < FLY_COUNT; i++) {
                    float lt = t - launchTick(i) * 0.7f;
                    if (lt < 0) continue;
                    float sx = flyStartX(seed, i);
                    f.add(sx + (float) Math.sin(lt * 0.18f + i) * 6f, -8 + lt * 1.5f, 4f, 0);
                }
            }
            default -> {
                // лягушка: прыжки справа налево с случайной силой
                Random rnd = new Random(seed + 7);
                float x = BOX_W + 6, tt = t - 8;
                if (tt < 0) { f.add(x, BOX_H - 7, 7f, 1); break; }
                for (int hop = 0; hop < 12; hop++) {
                    float dist = 22 + rnd.nextFloat() * 42, air = 12 + dist * 0.2f, rest = 5 + rnd.nextFloat() * 4;
                    float height = 14 + dist * 0.55f;
                    if (tt < air) {
                        float u = tt / air;
                        f.add(x - dist * u, BOX_H - 7 - 4 * height * u * (1 - u), 7f, 1);
                        return;
                    }
                    tt -= air; x -= dist;
                    if (tt < rest) { f.add(x, BOX_H - 7, 7f, 1); return; }
                    tt -= rest;
                }
                f.add(x, BOX_H - 7, 7f, 1);
            }
        }
    }

    private static float easeOut(float u) { return 1 - (1 - u) * (1 - u); }

    public static boolean hit(Frame f, float sx, float sy) {
        for (int i = 0; i < f.n; i++) {
            float dx = Math.abs(f.x[i] - sx), dy = Math.abs(f.y[i] - sy), rr = f.r[i] * 0.8f + SOUL_HALF * 0.6f;
            if (dx < rr && dy < rr && dx * dx + dy * dy < rr * rr * 1.4f) return true;
        }
        return false;
    }
}
