package com.deltabattle.battle;

import com.deltabattle.entity.FroggitEntity;
import com.deltabattle.net.C2S;
import com.deltabattle.party.PartyManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

public final class BattleManager {
    private static final Map<UUID, Battle> BY_PLAYER = new HashMap<>();
    private static final List<Battle> ACTIVE = new ArrayList<>();
    private static int counter;

    public static boolean inBattle(ServerPlayer p) { return BY_PLAYER.containsKey(p.getUUID()); }

    /** Запуск битвы: в неё входит вся пати инициатора, находящаяся рядом (до 4 игроков). */
    public static void start(ServerPlayer starter, FroggitEntity enemy) {
        if (inBattle(starter) || enemy.isInBattle() || !(enemy.level() instanceof ServerLevel level)) return;
        List<ServerPlayer> list = new ArrayList<>();
        list.add(starter);
        for (ServerPlayer p : PartyManager.membersOf(starter)) {
            if (p != starter && list.size() < PartyManager.MAX && !inBattle(p) && p.level() == level
                    && p.distanceToSqr(enemy) < 32 * 32) list.add(p);
        }
        Battle b = new Battle(++counter, level, enemy, list);
        ACTIVE.add(b);
        for (ServerPlayer p : list) BY_PLAYER.put(p.getUUID(), b);
    }

    public static void onPacket(ServerPlayer p, C2S pkt) {
        Battle b = BY_PLAYER.get(p.getUUID());
        if (b == null) return;
        switch (pkt.kind) {
            case C2S.CHOICE -> b.onChoice(p, pkt.cmd, pkt.sub, pkt.acc);
            case C2S.SOUL -> b.onSoul(p, pkt.x, pkt.y);
            default -> {}
        }
    }

    public static void tick() {
        for (int i = ACTIVE.size() - 1; i >= 0; i--) { if (i < ACTIVE.size()) ACTIVE.get(i).tick(); }
    }

    static void remove(Battle b) {
        ACTIVE.remove(b);
        for (Battle.Member m : b.members) BY_PLAYER.remove(m.p.getUUID(), b);
    }

    public static void onLogout(ServerPlayer p) {
        Battle b = BY_PLAYER.get(p.getUUID());
        if (b != null) b.end(Battle.RES_ABORT);
    }
}
