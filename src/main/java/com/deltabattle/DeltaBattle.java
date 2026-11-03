package com.deltabattle;

import com.deltabattle.battle.BattleManager;
import com.deltabattle.entity.FroggitEntity;
import com.deltabattle.net.Net;
import com.deltabattle.party.PartyCommand;
import com.deltabattle.party.PartyManager;
import com.deltabattle.registry.ModEntities;
import com.deltabattle.registry.ModItems;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.api.distmarker.Dist;

@Mod(DeltaBattle.MODID)
public class DeltaBattle {
    public static final String MODID = "deltabattle";

    public DeltaBattle() {
        var bus = FMLJavaModLoadingContext.get().getModEventBus();
        ModItems.ITEMS.register(bus);
        ModEntities.ENTITIES.register(bus);
        bus.addListener((FMLCommonSetupEvent e) -> e.enqueueWork(Net::init));
        bus.addListener((EntityAttributeCreationEvent e) -> e.put(ModEntities.FROGGIT.get(), FroggitEntity.createAttributes().build()));
        bus.addListener((BuildCreativeModeTabContentsEvent e) -> {
            if (e.getTabKey() == CreativeModeTabs.SPAWN_EGGS) e.accept(ModItems.FROGGIT_SPAWN_EGG);
            if (e.getTabKey() == CreativeModeTabs.FOOD_AND_DRINKS) e.accept(ModItems.MONSTER_CANDY);
        });
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent public void onCommands(RegisterCommandsEvent e) { PartyCommand.register(e.getDispatcher()); }

    @SubscribeEvent public void onTick(TickEvent.ServerTickEvent e) { if (e.phase == TickEvent.Phase.END) BattleManager.tick(); }

    @SubscribeEvent public void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (e.getEntity() instanceof ServerPlayer sp) { BattleManager.onLogout(sp); PartyManager.onLogout(sp); }
    }

    /** Во время битвы игрок неуязвим в мире. */
    @SubscribeEvent public void onAttack(LivingAttackEvent e) {
        if (e.getEntity() instanceof ServerPlayer sp && BattleManager.inBattle(sp)) e.setCanceled(true);
    }
}
