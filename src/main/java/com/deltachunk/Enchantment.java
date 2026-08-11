package com.deltachunk;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@EventBusSubscriber(
        modid = DeltaChunk.MOD_ID
)
public final class Enchantment {

    private static final ResourceKey<net.minecraft.world.item.enchantment.Enchantment> DELTA_ADD =
            ResourceKey.create(
                    Registries.ENCHANTMENT,
                    ResourceLocation.fromNamespaceAndPath(
                            DeltaChunk.MOD_ID,
                            "delta_add"
                    )
            );

    private static final ResourceKey<net.minecraft.world.item.enchantment.Enchantment> DELTA_DELETE =
            ResourceKey.create(
                    Registries.ENCHANTMENT,
                    ResourceLocation.fromNamespaceAndPath(
                            DeltaChunk.MOD_ID,
                            "delta_delete"
                    )
            );
 
    /*
     * 每個玩家自己的第一點。
     *
     * UUID -> 第一個座標
     */
    private static final Map<UUID, BlockPos> FIRST_POINTS =
            new ConcurrentHashMap<>();

    private Enchantment() {
    }

    @SubscribeEvent
    public static void onRightClickBlock(
            PlayerInteractEvent.RightClickBlock event
    ) {
        if (event.getLevel().isClientSide()) {
            return;
        }

        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        ItemStack stack = player.getItemInHand(event.getHand());

        if (stack.isEmpty()) {
            return;
        }

        var registry =
                player.level()
                        .registryAccess()
                        .lookupOrThrow(Registries.ENCHANTMENT);

        Holder<net.minecraft.world.item.enchantment.Enchantment> addHolder =
                registry.getOrThrow(DELTA_ADD);

        Holder<net.minecraft.world.item.enchantment.Enchantment> deleteHolder =
                registry.getOrThrow(DELTA_DELETE);

        int addLevel =
                EnchantmentHelper.getItemEnchantmentLevel(
                        addHolder,
                        stack
                );

        int deleteLevel =
                EnchantmentHelper.getItemEnchantmentLevel(
                        deleteHolder,
                        stack
                );

        boolean hasAdd = addLevel > 0;
        boolean hasDelete = deleteLevel > 0;

        if (!hasAdd && !hasDelete) {
            return;
        }

        /*
         * 不讓這次右鍵繼續觸發箱子、工作台、按鈕等原版行為。
         */
        event.setCanceled(true);

        /*
         * 同時存在兩種附魔時拒絕操作，
         * 避免第二點時 ADD 和 DELETE 同時執行。
         */
        if (hasAdd && hasDelete) {
            player.sendSystemMessage(
                Component.translatable(
            "message.deltachunk.enchant_conflict"
        )
            );

            FIRST_POINTS.remove(player.getUUID());
            return;
        }

        BlockPos clickedPos = event.getPos().immutable();

        UUID uuid = player.getUUID();

        BlockPos first = FIRST_POINTS.get(uuid);

        /*
         * 第一次右鍵
         */
        if (first == null) {
            FIRST_POINTS.put(uuid, clickedPos);

            player.sendSystemMessage(
 
                 Component.translatable(
                     "message.deltachunk.firstpoint", clickedPos.getX(), clickedPos.getY(),clickedPos.getZ()
                                    )
            );

            player.sendSystemMessage(
 
                  Component.translatable("message.deltachunk.waitfor2point")
            );

            return;
        }

        /*
         * 第二次右鍵
         */
        BlockPos second = clickedPos;

        player.sendSystemMessage(
          
             Component.translatable(
                 "message.deltachunk.secondpoint", clickedPos.getX(), clickedPos.getY(),clickedPos.getZ()
                                       )
        );

        /*
         * 先清除選取狀態。
         * 不論 command 成功或失敗，都不會卡在選取狀態。
         */
        FIRST_POINTS.remove(uuid);

        String command;

        if (hasAdd) {
            command =
                    "deltachunk add "
                            + first.getX() + " "
                            + first.getY() + " "
                            + first.getZ() + " "
                            + second.getX() + " "
                            + second.getY() + " "
                            + second.getZ();
        } else {
            command =
                    "deltachunk delete "
                            + first.getX() + " "
                            + first.getY() + " "
                            + first.getZ() + " "
                            + second.getX() + " "
                            + second.getY() + " "
                            + second.getZ();
        }

        player.sendSystemMessage(
                Component.literal(command)
        );

        /*
         * DeltaCommand 目前要求 permission level 2。
         *
         * 這裡使用玩家作為 command source，
         * 但只在這次內部執行時提高到 level 2。
         *
         * 因此玩家不需要真的成為 OP。
         */
        player.getServer()
                .getCommands()
                .performPrefixedCommand(
                        player.createCommandSourceStack()
                                .withPermission(2),
                        command
                );
    }
}
