package com.deltabattle.net;

import com.deltabattle.battle.BattleManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import java.util.function.Supplier;

/** Клиент -> сервер: выбор команды и позиция души. */
public class C2S {
    public static final int CHOICE = 0, SOUL = 1, LEAVE = 2;
    public int kind, cmd, sub, acc;
    public float x, y;

    public static C2S choice(int cmd, int sub, int acc) { C2S p = new C2S(); p.kind = CHOICE; p.cmd = cmd; p.sub = sub; p.acc = acc; return p; }
    public static C2S soul(float x, float y) { C2S p = new C2S(); p.kind = SOUL; p.x = x; p.y = y; return p; }
    public static C2S leave() { C2S p = new C2S(); p.kind = LEAVE; return p; }

    public static void encode(C2S p, FriendlyByteBuf b) {
        b.writeByte(p.kind);
        if (p.kind == CHOICE) { b.writeByte(p.cmd); b.writeByte(p.sub); b.writeShort(p.acc); }
        else if (p.kind == SOUL) { b.writeShort(Math.round(p.x * 16)); b.writeShort(Math.round(p.y * 16)); } // компактно: 1/16 px
    }

    public static C2S decode(FriendlyByteBuf b) {
        C2S p = new C2S(); p.kind = b.readByte();
        if (p.kind == CHOICE) { p.cmd = b.readByte(); p.sub = b.readByte(); p.acc = b.readShort(); }
        else if (p.kind == SOUL) { p.x = b.readShort() / 16f; p.y = b.readShort() / 16f; }
        return p;
    }

    public static void handle(C2S p, Supplier<NetworkEvent.Context> ctx) {
        var c = ctx.get();
        c.enqueueWork(() -> { if (c.getSender() != null) BattleManager.onPacket(c.getSender(), p); });
        c.setPacketHandled(true);
    }
}
