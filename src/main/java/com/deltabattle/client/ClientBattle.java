package com.deltabattle.client;

import com.deltabattle.battle.Battle;
import com.deltabattle.battle.Pattern;
import com.deltabattle.net.S2C;
import net.minecraft.client.Minecraft;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Клиентское состояние боя. Все данные приходят от сервера, время фаз считается по nanoTime (плавно на любом FPS). */
public final class ClientBattle {
    public static final int[] COLORS = {0xFF3030, 0x30C8FF, 0xFFD030, 0x50E070};
    public static ClientBattle cur;

    public static final class Popup { public float x, y; public String text; public int color; public long born; }

    public int id, myIdx, n, enemyHp, enemyMax, phase, turn, attackType;
    public boolean spareable;
    public long seed;
    public String[] names = new String[0];
    public int[] hp = new int[4], max = new int[4], tp = new int[4];
    public boolean[] ready = new boolean[4], down = new boolean[4];
    public float[] sx = new float[4], sy = new float[4], tx = new float[4], ty = new float[4];
    public long[] flashUntil = new long[4];
    public final float[] aimX = new float[Pattern.FLY_COUNT], aimY = new float[Pattern.FLY_COUNT];
    public final Pattern.Frame frame = new Pattern.Frame();
    public final List<Popup> popups = new ArrayList<>();
    public long phaseStart = System.nanoTime(), shakeUntil;
    public int phaseLen;
    public String phaseText = "";
    public int[] events = new int[0];
    public String[] eventText = new String[0];
    public int endResult = -1;
    public long endAt;
    public float shownEnemyHp;

    /** Время в тиках (дробное) с начала фазы. */
    public float phaseTicks() { return (System.nanoTime() - phaseStart) / 50_000_000f; }

    public static void handle(S2C p) {
        Minecraft mc = Minecraft.getInstance();
        if (p.kind == S2C.START) {
            ClientBattle b = new ClientBattle();
            b.id = p.i[0]; b.enemyHp = p.i[1]; b.enemyMax = p.i[2]; b.n = p.i[3]; b.myIdx = p.i[4];
            b.names = Arrays.copyOfRange(p.s, 0, b.n);
            for (int k = 0; k < b.n; k++) { b.hp[k] = p.i[5 + k * 2]; b.max[k] = p.i[6 + k * 2]; }
            b.shownEnemyHp = b.enemyHp;
            Arrays.fill(b.sx, 70); Arrays.fill(b.sy, 70); Arrays.fill(b.tx, 70); Arrays.fill(b.ty, 70);
            Arrays.fill(b.aimX, Float.NaN); Arrays.fill(b.aimY, Float.NaN);
            cur = b;
            mc.setScreen(new BattleScreen(b));
            return;
        }
        ClientBattle b = cur;
        if (b == null) return;
        long now = System.nanoTime();
        switch (p.kind) {
            case S2C.PHASE -> {
                b.phase = p.i[0]; b.phaseLen = p.i[1]; b.turn = p.i[2]; b.attackType = p.i[3]; b.seed = p.l; b.phaseText = p.s[0];
                b.phaseStart = now;
                if (b.phase == Battle.TALK) { Arrays.fill(b.aimX, Float.NaN); Arrays.fill(b.aimY, Float.NaN); }
                if (b.phase == Battle.TALK) for (int k = 0; k < 4; k++) { b.sx[k] = b.tx[k] = Pattern.BOX_W / 2f; b.sy[k] = b.ty[k] = Pattern.BOX_H / 2f; }
                if (mc.screen instanceof BattleScreen s) s.onPhase();
            }
            case S2C.EVENTS -> { b.events = p.i; b.eventText = p.s; }
            case S2C.STATE -> {
                if (p.i[2] < 0) { int who = -1 - p.i[2]; if (who >= 0 && who < 4) b.ready[who] = true; }
                else {
                    b.enemyHp = p.i[0]; b.spareable = p.i[1] == 1;
                    for (int k = 0; k < p.i[2]; k++) { b.hp[k] = p.i[3 + k * 2]; b.tp[k] = p.i[4 + k * 2]; b.down[k] = b.hp[k] <= 0; }
                    if (b.phase != Battle.EXECUTE) Arrays.fill(b.ready, false);
                }
            }
            case S2C.AIM -> { b.aimX[p.i[0]] = p.f[0]; b.aimY[p.i[0]] = p.f[1]; }
            case S2C.HIT -> {
                int k = p.i[0]; b.hp[k] = p.i[1]; b.tp[k] = p.i[2]; b.down[k] = b.hp[k] <= 0;
                b.flashUntil[k] = now + 1_000_000_000L;
                if (k == b.myIdx) { b.shakeUntil = now + 250_000_000L; mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.PLAYER_HURT, 1.2f, 0.5f)); }
            }
            case S2C.SOULS -> {
                int cnt = p.i[0];
                for (int k = 0; k < cnt && k < 4; k++) { b.tx[k] = p.f[k * 2]; b.ty[k] = p.f[k * 2 + 1]; b.down[k] = p.i[k + 1] == 1; }
            }
            case S2C.END -> { b.endResult = p.i[0]; b.endAt = now + 2_200_000_000L; b.phase = 4; if (mc.screen instanceof BattleScreen s) s.onEnd(); }
            default -> {}
        }
    }
}
