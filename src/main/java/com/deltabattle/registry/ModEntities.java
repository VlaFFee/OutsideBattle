package com.deltabattle.registry;

import com.deltabattle.DeltaBattle;
import com.deltabattle.entity.FroggitEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, DeltaBattle.MODID);
    public static final RegistryObject<EntityType<FroggitEntity>> FROGGIT = ENTITIES.register("froggit",
            () -> EntityType.Builder.of(FroggitEntity::new, MobCategory.CREATURE).sized(0.8f, 0.8f).clientTrackingRange(8).build("froggit"));
}
