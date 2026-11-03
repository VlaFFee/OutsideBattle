package com.deltabattle.battle;

import com.deltabattle.entity.FroggitEntity;
import com.deltabattle.net.Net;
import com.deltabattle.net.S2C;
import com.deltabattle.registry.ModItems;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.*;

/** Серверная машина состояний одной битвы (без аллокаций в горячем цикле атаки). */
public class Battle {
    public static final int MENU = 0, EXECUTE = 1, TALK = 2, ATTACK = 3;
    public static final int CMD_FIGHT = 0, CMD_ACT = 1, CMD_ITEM = 2, CMD_SPARE = 3, CMD_DEFEND = 4;
    public static final int EV_TEXT = 0, EV_DAMAGE = 1, EV_MISS = 2, EV_HEAL = 3, EV_DEFEND = 4, EV_SPARED = 5;
    public static final int RES_KILLED = 1, RES_SPARED = 2, RES_DEFEAT = 3, RES_ABORT = 4;
    static final int MENU_TIMEOUT = 20 * 45, IFRAMES = 20, HEAL_COST = 32;

    public static class Member {
        public final ServerPlayer p;
        public int hp = 20, max = 20, tp = 0, cmd = -1, sub, acc, inv;
        public float x = Pattern.BOX_W / 2f, y = Pattern.BOX_H / 2f;
        public boolean defend;
        Member(ServerPlayer p) { this.p = p; }
        public boolean down() { return hp <= 0; }
    }

    public final int id;
    public final ServerLevel level;
    public final UUID enemyId;
    public final List<Member> members = new ArrayList<>();
    private final Random rnd = new Random();
    private final Pattern.Frame frame = new Pattern.Frame();
    private final float[] aimX = new float[Pattern.FLY_COUNT], aimY = new float[Pattern.FLY_COUNT];
    int enemyHp = FroggitStats.HP, phase, phaseTick, phaseLen, turn, attackType, result;
    boolean spareable, over;
    long seed;
    String lastAct = "";

    public Battle(int id, ServerLevel level, FroggitEntity enemy, List<ServerPlayer> players) {
        this.id = id; this.level = level; this.enemyId = enemy.getUUID();
        for (ServerPlayer p : players) members.add(new Member(p));
        enemy.setInBattle(true);
        sendStart();
        enterMenu("deltabattle.flavor.start");
    }

    // ---------- рассылка ----------
    private void all(S2C pkt) { for (Member m : members) Net.toPlayer(m.p, pkt); }

    private void sendStart() {
        for (int mi = 0; mi < members.size(); mi++) {
            int n = members.size();
            int[] ints = new int[5 + n * 2];
            ints[0] = id; ints[1] = enemyHp; ints[2] = FroggitStats.HP; ints[3] = n; ints[4] = mi;
            String[] names = new String[n];
            for (int k = 0; k < n; k++) { ints[5 + k * 2] = members.get(k).hp; ints[6 + k * 2] = members.get(k).max; names[k] = members.get(k).p.getGameProfile().getName(); }
            Net.toPlayer(members.get(mi).p, new S2C(S2C.START).ints(ints).strs(names));
        }
    }

    private void sendState() {
        int n = members.size(); int[] v = new int[3 + n * 2];
        v[0] = enemyHp; v[1] = spareable ? 1 : 0; v[2] = n;
        for (int k = 0; k < n; k++) { v[3 + k * 2] = members.get(k).hp; v[4 + k * 2] = members.get(k).tp; }
        all(new S2C(S2C.STATE).ints(v));
    }

    private void setPhase(int ph, int len, String text) {
        phase = ph; phaseTick = 0; phaseLen = len;
        all(new S2C(S2C.PHASE).ints(ph, len, turn, attackType).lng(seed).strs(text));
    }

    // ---------- ввод ----------
    public void onChoice(ServerPlayer p, int cmd, int sub, int acc) {
        Member m = get(p);
        if (m == null || phase != MENU || m.down() || m.cmd >= 0 || cmd < 0 || cmd > 4) return;
        m.cmd = cmd; m.sub = sub; m.acc = Math.max(0, Math.min(1000, acc));
        all(new S2C(S2C.STATE).ints(enemyHp, spareable ? 1 : 0, -1 - members.indexOf(m))); // -1-idx: "игрок готов"
        if (allReady()) execute();
    }

    public void onSoul(ServerPlayer p, float x, float y) {
        Member m = get(p);
        if (m == null || phase != ATTACK || m.down()) return;
        x = Math.max(Pattern.SOUL_HALF, Math.min(Pattern.BOX_W - Pattern.SOUL_HALF, x));
        y = Math.max(Pattern.SOUL_HALF, Math.min(Pattern.BOX_H - Pattern.SOUL_HALF, y));
        if (Math.abs(x - m.x) < 10 && Math.abs(y - m.y) < 10) { m.x = x; m.y = y; } // защита от телепорта
    }

    public Member get(ServerPlayer p) { for (Member m : members) if (m.p == p) return m; return null; }
    private boolean allReady() { for (Member m : members) if (!m.down() && m.cmd < 0) return false; return true; }

    // ---------- фазы ----------
    private void enterMenu(String flavor) {
        turn++;
        for (Member m : members) { m.cmd = -1; m.defend = false; }
        setPhase(MENU, MENU_TIMEOUT, flavor);
        sendState();
    }

    private String pickFlavor() {
        if (spareable) return "deltabattle.flavor.reluctant";
        if (enemyHp <= FroggitStats.HP / 3) return "deltabattle.flavor.runaway";
        return "deltabattle.flavor.n" + rnd.nextInt(4);
    }

    private void execute() {
        List<int[]> ev = new ArrayList<>(); List<String> tx = new ArrayList<>();
        for (int mi = 0; mi < members.size() && result == 0; mi++) {
            Member m = members.get(mi);
            if (m.down()) continue;
            int cmd = m.cmd < 0 ? CMD_DEFEND : m.cmd;
            switch (cmd) {
                case CMD_FIGHT -> {
                    float a = m.acc / 1000f;
                    if (a < 0.05f) { ev.add(new int[]{EV_MISS, mi, 0}); tx.add("deltabattle.text.miss"); break; }
                    int dmg = Math.max(1, Math.round((14 + rnd.nextInt(3)) * (0.4f + 1.1f * a)) - FroggitStats.DEF);
                    enemyHp -= dmg; m.tp = Math.min(100, m.tp + Math.round(a * 10));
                    ev.add(new int[]{EV_DAMAGE, mi, dmg}); tx.add("deltabattle.text.fight");
                    if (enemyHp <= 0) { enemyHp = 0; result = RES_KILLED; }
                }
                case CMD_ACT -> {
                    m.tp = Math.min(100, m.tp + 4);
                    switch (m.sub) {
                        case 0 -> { ev.add(new int[]{EV_TEXT, mi, 0}); tx.add("deltabattle.act.check"); }
                        case 1 -> { spareable = true; lastAct = "blush"; ev.add(new int[]{EV_TEXT, mi, 0}); tx.add("deltabattle.act.compliment"); }
                        case 2 -> { spareable = true; lastAct = "shiver"; ev.add(new int[]{EV_TEXT, mi, 0}); tx.add("deltabattle.act.threaten"); }
                        default -> {
                            if (m.tp >= HEAL_COST) {
                                m.tp -= HEAL_COST;
                                for (Member o : members) { o.hp = Math.min(o.max, (o.down() ? 0 : o.hp) + 6); }
                                ev.add(new int[]{EV_HEAL, mi, 6}); tx.add("deltabattle.act.heal");
                            } else { ev.add(new int[]{EV_TEXT, mi, 0}); tx.add("deltabattle.act.notp"); }
                        }
                    }
                }
                case CMD_ITEM -> {
                    ItemStack st = findCandy(m.p);
                    if (st == null) { ev.add(new int[]{EV_TEXT, mi, 0}); tx.add("deltabattle.item.none"); }
                    else { st.shrink(1); m.hp = Math.min(m.max, m.hp + 10); ev.add(new int[]{EV_HEAL, mi, 10}); tx.add("deltabattle.item.candy"); }
                }
                case CMD_SPARE -> {
                    if (spareable) { result = RES_SPARED; ev.add(new int[]{EV_SPARED, mi, 0}); tx.add("deltabattle.text.spare"); }
                    else { ev.add(new int[]{EV_TEXT, mi, 0}); tx.add("deltabattle.text.spare_fail"); }
                }
                default -> { m.defend = true; m.tp = Math.min(100, m.tp + 16); ev.add(new int[]{EV_DEFEND, mi, 0}); tx.add("deltabattle.text.defend"); }
            }
        }
        int n = ev.size(); int[] flat = new int[n * 3];
        for (int k = 0; k < n; k++) { flat[k * 3] = ev.get(k)[0]; flat[k * 3 + 1] = ev.get(k)[1]; flat[k * 3 + 2] = ev.get(k)[2]; }
        all(new S2C(S2C.EVENTS).ints(flat).strs(tx.toArray(new String[0])));
        sendState();
        setPhase(EXECUTE, n * 40 + 15, "");
    }

    private ItemStack findCandy(ServerPlayer p) {
        for (ItemStack s : p.getInventory().items) if (s.is(ModItems.MONSTER_CANDY.get())) return s;
        return null;
    }

    private void startTalk() {
        String key = lastAct.isEmpty() ? new String[]{"ribbit", "croak", "hop", "meow"}[rnd.nextInt(4)] : lastAct;
        lastAct = "";
        seed = rnd.nextLong(); attackType = Pattern.pickType(seed);
        Arrays.fill(aimX, Float.NaN); Arrays.fill(aimY, Float.NaN);
        for (Member m : members) { m.x = Pattern.BOX_W / 2f; m.y = Pattern.BOX_H / 2f; m.inv = 0; }
        setPhase(TALK, 45, "deltabattle.say." + key);
    }

    private void startAttack() { setPhase(ATTACK, Pattern.DURATION[attackType], ""); }

    // ---------- тик ----------
    public void tick() {
        if (over) return;
        for (Member m : members) if (m.p.isRemoved()) { end(RES_ABORT); return; }
        Entity e = level.getEntity(enemyId);
        if (e == null || !e.isAlive()) { end(RES_ABORT); return; }
        phaseTick++;
        switch (phase) {
            case MENU -> { if (phaseTick >= phaseLen) { for (Member m : members) if (!m.down() && m.cmd < 0) m.cmd = CMD_DEFEND; execute(); } }
            case EXECUTE -> {
                if (phaseTick >= phaseLen) {
                    if (result == RES_KILLED || result == RES_SPARED) end(result); else startTalk();
                }
            }
            case TALK -> { if (phaseTick >= phaseLen) startAttack(); }
            case ATTACK -> tickAttack();
        }
    }

    private void tickAttack() {
        if (attackType == Pattern.TYPE_FLIES) {
            for (int i = 0; i < Pattern.FLY_COUNT; i++) {
                if (phaseTick == Pattern.fireTick(i)) {
                    Member t = pickTarget(i);
                    if (t != null) { aimX[i] = t.x; aimY[i] = t.y; all(new S2C(S2C.AIM).ints(i).floats(t.x, t.y)); }
                }
            }
        }
        Pattern.collect(frame, attackType, seed, phaseTick, aimX, aimY);
        for (Member m : members) {
            if (m.inv > 0) m.inv--;
            if (m.down() || m.inv > 0) continue;
            if (Pattern.hit(frame, m.x, m.y)) {
                int dmg = m.defend ? FroggitStats.ATK / 2 : FroggitStats.ATK;
                m.hp = Math.max(0, m.hp - dmg); m.inv = IFRAMES; m.tp = Math.min(100, m.tp + 4);
                all(new S2C(S2C.HIT).ints(members.indexOf(m), m.hp, m.tp));
            }
        }
        if ((phaseTick & 1) == 0) sendSouls();
        boolean anyAlive = false; for (Member m : members) if (!m.down()) anyAlive = true;
        if (!anyAlive) { end(RES_DEFEAT); return; }
        if (phaseTick >= phaseLen) { enterMenu(pickFlavor()); }
    }

    private Member pickTarget(int i) {
        int alive = 0; for (Member m : members) if (!m.down()) alive++;
        if (alive == 0) return null;
        int k = i % alive;
        for (Member m : members) if (!m.down() && k-- == 0) return m;
        return null;
    }

    private void sendSouls() {
        int n = members.size(); int[] d = new int[n + 1]; float[] f = new float[n * 2]; d[0] = n;
        for (int k = 0; k < n; k++) { Member m = members.get(k); d[k + 1] = m.down() ? 1 : 0; f[k * 2] = m.x; f[k * 2 + 1] = m.y; }
        all(new S2C(S2C.SOULS).ints(d).floats(f));
    }

    public void end(int res) {
        if (over) return; over = true;
        Entity e = level.getEntity(enemyId);
        if (e instanceof FroggitEntity fr) fr.finishBattle(res);
        for (Member m : members) {
            if (m.down()) m.hp = Math.max(1, m.max / 8);
            if (res == RES_KILLED && !m.down()) { m.p.giveExperiencePoints(3); m.p.getInventory().add(new ItemStack(Items.GOLD_NUGGET, 2)); }
            Net.toPlayer(m.p, new S2C(S2C.END).ints(res));
        }
        BattleManager.remove(this);
    }
}
