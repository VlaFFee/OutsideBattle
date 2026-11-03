package com.deltabattle.client;

import com.deltabattle.DeltaBattle;
import com.deltabattle.battle.Battle;
import com.deltabattle.battle.Pattern;
import com.deltabattle.net.C2S;
import com.deltabattle.net.Net;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import java.util.Iterator;
import java.util.List;

/** Экран боя в стиле Deltarune. Виртуальное разрешение 320x240, всё масштабируется и интерполируется по реальному времени. */
public class BattleScreen extends Screen {
    private static final ResourceLocation SOUL = tex("soul"), FLY = tex("fly"), FROG = tex("frog"), FROGGIT = tex("froggit");
    private static ResourceLocation tex(String n) { return new ResourceLocation(DeltaBattle.MODID, "textures/gui/" + n + ".png"); }
    private static final int UI_MAIN = 0, UI_ACT = 1, UI_FIGHT = 2, UI_WAIT = 3;
    private static final int YELLOW = 0xFFFFFF00, WHITE = 0xFFFFFFFF;

    private final ClientBattle b;
    private int ui = UI_WAIT, sel, actSel, lastEvent = -1, lastChars;
    private float bx = 10, by = 124, bw = 300, bh = 48, myX = 70, myY = 70, lastSentX = -1, lastSentY = -1, fade = 1f;
    private final float[] dHp = new float[4];
    private String fullText = "";
    private long typeStart, fightStart, lastNs = System.nanoTime(), enemyHitAt;

    public BattleScreen(ClientBattle b) {
        super(Component.empty());
        this.b = b;
        for (int i = 0; i < 4; i++) dHp[i] = b.hp[i];
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean shouldCloseOnEsc() { return false; }

    // ---------- текст ----------
    private void setText(String key, Object... args) { fullText = I18n.get(key, args); typeStart = System.nanoTime(); lastChars = 0; }

    public void onPhase() {
        switch (b.phase) {
            case Battle.MENU -> { ui = b.down[b.myIdx] ? UI_WAIT : UI_MAIN; setText(b.phaseText); }
            case Battle.EXECUTE -> { ui = UI_WAIT; lastEvent = -1; fullText = ""; }
            case Battle.TALK -> { ui = UI_WAIT; setText(b.phaseText); myX = myY = Pattern.BOX_W / 2f; }
            default -> { ui = UI_WAIT; fullText = ""; }
        }
    }

    public void onEnd() { setText("deltabattle.end." + b.endResult); ui = UI_WAIT; }

    // ---------- ввод меню ----------
    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        boolean ok = key == 90 || key == 257 || key == 32, back = key == 88 || key == 259;
        boolean left = key == 263 || key == 65, right = key == 262 || key == 68, up = key == 265 || key == 87, dn = key == 264 || key == 83;
        if (ui == UI_MAIN) {
            if (left) { sel = (sel + 4) % 5; click(1.3f); } else if (right) { sel = (sel + 1) % 5; click(1.3f); }
            else if (ok) {
                switch (sel) {
                    case 0 -> { ui = UI_FIGHT; fightStart = System.nanoTime(); }
                    case 1 -> { ui = UI_ACT; actSel = 0; }
                    default -> send(sel == 2 ? Battle.CMD_ITEM : sel == 3 ? Battle.CMD_SPARE : Battle.CMD_DEFEND, 0, 0);
                }
                click(1.0f);
            }
        } else if (ui == UI_ACT) {
            if (up) { actSel = (actSel + 3) % 4; click(1.3f); } else if (dn) { actSel = (actSel + 1) % 4; click(1.3f); }
            else if (ok) send(Battle.CMD_ACT, actSel, 0);
            else if (back) ui = UI_MAIN;
        } else if (ui == UI_FIGHT) {
            if (ok) { float p = (System.nanoTime() - fightStart) / 900_000_000f; send(Battle.CMD_FIGHT, 0, Math.round(Math.max(0f, 1f - Math.abs(p - 0.5f) * 2f) * 1000)); }
            else if (back) ui = UI_MAIN;
        }
        return true;
    }

    private void send(int cmd, int sub, int acc) {
        Net.toServer(C2S.choice(cmd, sub, acc));
        ui = UI_WAIT; b.ready[b.myIdx] = true; fullText = ""; click(0.8f);
    }

    private void click(float pitch) { Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_HAT.value(), pitch, 0.6f)); }

    // ---------- тик (20 Гц): сеть и завершение ----------
    @Override
    public void tick() {
        long now = System.nanoTime();
        if (b.endResult >= 0 && now > b.endAt) { ClientBattle.cur = null; Minecraft.getInstance().setScreen(null); return; }
        if (b.phase == Battle.ATTACK && !b.down[b.myIdx] && (myX != lastSentX || myY != lastSentY)) {
            Net.toServer(C2S.soul(myX, myY)); lastSentX = myX; lastSentY = myY;
        }
        // "печатная машинка": звук на каждый второй символ
        int chars = Math.min(fullText.length(), (int) ((now - typeStart) / 22_000_000L));
        if (chars > lastChars) {
            if ((chars & 1) == 0 && fullText.charAt(chars - 1) != ' ')
                Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BIT.value(), 1.6f, 0.25f));
            lastChars = chars;
        }
    }

    @Override public void removed() { if (ClientBattle.cur == b && b.endResult < 0) ClientBattle.cur = null; }

    // ---------- обновление кадра ----------
    private static boolean key(long w, int a, int c) { return InputConstants.isKeyDown(w, a) || InputConstants.isKeyDown(w, c); }

    private void update(float dt, long now) {
        float k = 1f - (float) Math.exp(-dt * 16f);
        // плавные полоски HP и HP врага
        for (int i = 0; i < b.n; i++) dHp[i] += (b.hp[i] - dHp[i]) * k * 0.6f;
        b.shownEnemyHp += (b.enemyHp - b.shownEnemyHp) * (1f - (float) Math.exp(-dt * 6f));
        // другие души - сглаживание к позициям сервера (приходят 10 раз в сек)
        for (int i = 0; i < b.n; i++) if (i != b.myIdx) { b.sx[i] += (b.tx[i] - b.sx[i]) * k; b.sy[i] += (b.ty[i] - b.sy[i]) * k; }
        // собственная душа - покадровое движение, скорость 60 px/с (X - медленно)
        if (b.phase == Battle.ATTACK && !b.down[b.myIdx]) {
            long w = Minecraft.getInstance().getWindow().getWindow();
            float dx = (key(w, 262, 68) ? 1 : 0) - (key(w, 263, 65) ? 1 : 0), dy = (key(w, 264, 83) ? 1 : 0) - (key(w, 265, 87) ? 1 : 0);
            if (dx != 0 && dy != 0) { dx *= 0.7071f; dy *= 0.7071f; }
            float sp = InputConstants.isKeyDown(w, 88) ? 30f : 60f;
            float h = Pattern.SOUL_HALF;
            myX = Math.max(h, Math.min(Pattern.BOX_W - h, myX + dx * sp * dt));
            myY = Math.max(h, Math.min(Pattern.BOX_H - h, myY + dy * sp * dt));
            b.sx[b.myIdx] = myX; b.sy[b.myIdx] = myY;
            Pattern.collect(b.frame, b.attackType, b.seed, b.phaseTicks(), b.aimX, b.aimY);
        } else if (b.phase != Battle.ATTACK) { b.frame.n = 0; b.sx[b.myIdx] = myX; b.sy[b.myIdx] = myY; }
        // авто-промах в мини-игре удара
        if (ui == UI_FIGHT && (now - fightStart) > 900_000_000L) send(Battle.CMD_FIGHT, 0, 0);
        // показ событий хода по очереди (каждые 2 секунды)
        if (b.phase == Battle.EXECUTE) {
            int idx = (int) (b.phaseTicks() / 40f);
            while (lastEvent < idx && lastEvent + 1 < b.events.length / 3) showEvent(++lastEvent, now);
        }
        // затухание врага после пощады
        if (b.endResult == Battle.RES_SPARED) fade = Math.max(0f, fade - dt * 1.2f);
        for (Iterator<ClientBattle.Popup> it = b.popups.iterator(); it.hasNext(); ) if (now - it.next().born > 1_300_000_000L) it.remove();
        if (b.endResult == Battle.RES_KILLED) fade = Math.max(0f, fade - dt * 2f);
    }

    private void showEvent(int i, long now) {
        int type = b.events[i * 3], who = b.events[i * 3 + 1], val = b.events[i * 3 + 2];
        String name = who < b.names.length ? b.names[who] : "?";
        setText(b.eventText[i], name);
        ClientBattle.Popup p = new ClientBattle.Popup(); p.born = now;
        switch (type) {
            case Battle.EV_DAMAGE -> { p.x = 250; p.y = 60; p.text = "-" + val; p.color = 0xFFFF4040; b.shakeUntil = 0; enemyHitAt = now;
                Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_ATTACK_STRONG, 1.0f, 0.7f)); }
            case Battle.EV_MISS -> { p.x = 250; p.y = 60; p.text = "MISS"; p.color = 0xFFAAAAAA; }
            case Battle.EV_HEAL -> { p.x = 10 + who * 77 + 30; p.y = 190; p.text = "+" + val; p.color = 0xFF40FF60;
                Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.4f, 0.5f)); }
            default -> { return; }
        }
        b.popups.add(p);
    }

    // ---------- отрисовка ----------
    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - lastNs) / 1e9f); lastNs = now;
        update(dt, now);
        g.fill(0, 0, width, height, 0xFF000000);
        float u = Math.min(width / 320f, height / 240f), ox = (width - 320 * u) / 2f, oy = (height - 240 * u) / 2f;
        PoseStack ps = g.pose();
        ps.pushPose();
        ps.translate(ox, oy, 0);
        if (now < b.shakeUntil) ps.translate((float) Math.sin(now / 1.5e7) * 2f * u, 0, 0);
        ps.scale(u, u, 1f);
        float t = now / 1e9f;
        drawGrid(g, t);
        drawEnemy(g, ps, t, now);
        // плавное изменение размера бокса
        boolean atk = b.phase == Battle.ATTACK || b.phase == Battle.TALK;
        float k = 1f - (float) Math.exp(-dt * 12f);
        float tx = atk ? 20 : 10, ty = atk ? 22 : 124, tw = atk ? Pattern.BOX_W : 300, th = atk ? Pattern.BOX_H : 48;
        bx += (tx - bx) * k; by += (ty - by) * k; bw += (tw - bw) * k; bh += (th - bh) * k;
        int ix = Math.round(bx), iy = Math.round(by), iw = Math.round(bw), ih = Math.round(bh);
        g.fill(ix - 3, iy - 3, ix + iw + 3, iy + ih + 3, WHITE);
        g.fill(ix, iy, ix + iw, iy + ih, 0xFF000000);
        if (b.phase == Battle.ATTACK) drawAttack(g, ps, ox, oy, u, t, now);
        else drawBoxContent(g, ps, ix, iy, iw, now);
        drawHud(g, now);
        for (ClientBattle.Popup p : b.popups) {
            float age = (now - p.born) / 1e9f; int a = Math.max(4, (int) (255 * (1f - age / 1.3f)));
            g.drawString(font, p.text, (int) p.x - font.width(p.text) / 2, (int) (p.y - Math.min(age, 0.4f) * 40f - age * 6f), (a << 24) | (p.color & 0xFFFFFF), true);
        }
        ps.popPose();
    }

    private void drawGrid(GuiGraphics g, float t) {
        int off = (int) ((t * 8f) % 20f);
        for (int x = -20; x <= 320; x += 20) g.fill(x + off, 0, x + off + 1, 240, 0xFF220A33);
        for (int y = -20; y <= 240; y += 20) g.fill(0, y + off, 320, y + off + 1, 0xFF220A33);
    }

    private void drawEnemy(GuiGraphics g, PoseStack ps, float t, long now) {
        float bob = (float) Math.abs(Math.sin(t * 2.4f));        // прыжки на месте
        float sqY = 1f + 0.07f * (float) Math.cos(t * 4.8f), sqX = 1f / sqY;
        float hurt = Math.max(0f, 1f - (now - enemyHitAt) / 4.5e8f), shake = (float) Math.sin(now / 1.2e7) * 5f * hurt;
        float a = fade;
        ps.pushPose();
        ps.translate(250 + shake, 118 - bob * 7f, 0);
        ps.scale(sqX, sqY, 1f);
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1f, 1f - hurt * 0.6f, 1f - hurt * 0.6f, a);
        g.blit(FROGGIT, -48, -96, 96, 96, 0, 0, 48, 48, 48, 48);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        ps.popPose();
        // полоска HP врага и имя (жёлтое, если можно пощадить)
        String nm = I18n.get("deltabattle.froggit");
        g.drawString(font, nm, 250 - font.width(nm) / 2, 10, b.spareable ? YELLOW : WHITE, true);
        g.fill(210, 21, 290, 26, 0xFF550000);
        g.fill(210, 21, 210 + Math.round(80f * Math.max(0f, b.shownEnemyHp) / b.enemyMax), 26, 0xFF30D050);
        if (b.phase == Battle.TALK) drawBubble(g, now);
    }

    private void drawBubble(GuiGraphics g, long now) {
        String s = I18n.get(b.phaseText);
        int shown = Math.min(s.length(), (int) ((now - b.phaseStart) / 45_000_000L));
        List<FormattedCharSequence> lines = font.split(Component.literal(s.substring(0, shown)), 58);
        int h = 12 + Math.max(1, lines.size()) * 9;
        g.fill(168, 34, 232, 34 + h, 0xFFFFFFFF); g.fill(232, 54, 236, 60, 0xFFFFFFFF);
        for (int i = 0; i < lines.size(); i++) g.drawString(font, lines.get(i), 172, 39 + i * 9, 0xFF000000, false);
    }

    private void drawBoxContent(GuiGraphics g, PoseStack ps, int ix, int iy, int iw, long now) {
        if (ui == UI_ACT) {
            for (int i = 0; i < 4; i++) {
                int x = ix + 12 + (i % 2) * 140, y = iy + 8 + (i / 2) * 16;
                if (i == actSel) drawSoul(g, ps, x, y, 0xFF3030, 0.9f);
                g.drawString(font, I18n.get("deltabattle.menu.act" + i), x + 12, y, i == actSel ? YELLOW : WHITE, false);
            }
        } else if (ui == UI_FIGHT) {            // шкала тайминга: чем ближе к центру, тем сильнее удар
            float p = Math.min(1f, (now - fightStart) / 900_000_000f);
            g.fill(ix + 10, iy + 12, ix + iw - 10, iy + 36, 0xFF202020);
            g.fill(ix + iw / 2 - 6, iy + 12, ix + iw / 2 + 6, iy + 36, 0xFF40FF60);
            g.fill(ix + 10 + Math.round((iw - 22) * p), iy + 8, ix + 14 + Math.round((iw - 22) * p), iy + 40, WHITE);
        } else {
            int shown = Math.min(fullText.length(), (int) ((now - typeStart) / 22_000_000L));
            List<FormattedCharSequence> lines = font.split(Component.literal(fullText.substring(0, shown)), iw - 24);
            g.drawString(font, "*", ix + 6, iy + 8, WHITE, false);
            for (int i = 0; i < lines.size() && i < 4; i++) g.drawString(font, lines.get(i), ix + 16, iy + 8 + i * 10, WHITE, false);
        }
    }

    private void drawSoul(GuiGraphics g, PoseStack ps, float x, float y, int rgb, float alpha) {
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f, alpha);
        ps.pushPose(); ps.translate(x, y, 0); g.blit(SOUL, 0, 0, 9, 8, 0, 0, 9, 8, 9, 8); ps.popPose();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
    }

    private void drawAttack(GuiGraphics g, PoseStack ps, float ox, float oy, float u, float t, long now) {
        // всё рисуем только внутри коробки (scissor в пикселях GUI)
        g.enableScissor(Math.round(ox + bx * u), Math.round(oy + by * u), Math.round(ox + (bx + bw) * u), Math.round(oy + (by + bh) * u));
        Pattern.Frame f = b.frame;
        for (int i = 0; i < f.n; i++) {
            boolean frog = f.kind[i] == 1; int w = frog ? 15 : 9, h = frog ? 12 : 6;
            ps.pushPose(); ps.translate(bx + f.x[i] - w / 2f, by + f.y[i] - h / 2f, 0);
            g.blit(frog ? FROG : FLY, 0, 0, w, h, 0, 0, w, h, w, h); ps.popPose();
        }
        for (int i = 0; i < b.n; i++) {
            if (b.down[i]) continue;
            boolean flashing = now < b.flashUntil[i] && ((now / 60_000_000L) & 1) == 0;
            if (i == b.myIdx || !flashing) drawSoul(g, ps, bx + b.sx[i] - 4.5f, by + b.sy[i] - 4f, ClientBattle.COLORS[i], i == b.myIdx ? (flashing ? 0.25f : 1f) : 0.75f);
        }
        g.disableScissor();
    }

    private void drawHud(GuiGraphics g, long now) {
        for (int i = 0; i < 5; i++) {
            int x = 10 + i * 62; boolean s = ui == UI_MAIN && sel == i;
            g.fill(x, 178, x + 58, 194, s ? YELLOW : 0xFF704000);
            g.fill(x + 1, 179, x + 57, 193, 0xFF000000);
            String l = I18n.get("deltabattle.menu." + i);
            g.drawString(font, l, x + 29 - font.width(l) / 2, 182, s ? YELLOW : 0xFFFF8020, false);
        }
        for (int i = 0; i < b.n; i++) {
            int x = 10 + i * 77, y = 198, c = 0xFF000000 | ClientBattle.COLORS[i];
            g.fill(x, y, x + 73, y + 38, i == b.myIdx ? c : 0xFF555555);
            g.fill(x + 1, y + 1, x + 72, y + 37, 0xFF101018);
            String nm = font.plainSubstrByWidth(b.names[i], 50);
            g.drawString(font, nm, x + 4, y + 3, b.down[i] ? 0xFF777777 : c, false);
            if (b.ready[i] && b.phase == Battle.MENU) g.drawString(font, "OK", x + 56, y + 3, 0xFF40FF60, false);
            g.fill(x + 4, y + 15, x + 69, y + 21, 0xFF550000);
            g.fill(x + 4, y + 15, x + 4 + Math.round(65f * Math.max(0f, dHp[i]) / b.max[i]), y + 21, c);
            g.drawString(font, b.hp[i] + "/" + b.max[i], x + 4, y + 23, WHITE, false);
            g.fill(x + 46, y + 25, x + 69, y + 31, 0xFF402000);
            g.fill(x + 46, y + 25, x + 46 + 23 * b.tp[i] / 100, y + 31, 0xFFFFA020);
        }
    }
}
