package com.twofrogs.world.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;

/** Places waxed (uneditable) wall signs with lore on them. Each line must be about 15 characters or fewer. */
public final class Signs {

    private Signs() {
    }

    /** 'facing' is the direction the sign faces (away from the wall it is attached to). */
    public static void wall(StructureContext ctx, int dx, int dy, int dz, Direction facing, String... lines) {
        ctx.set(dx, dy, dz, Blocks.DARK_OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, facing));

        BlockPos pos = ctx.world(dx, dy, dz);
        if (ctx.level.getBlockEntity(pos) instanceof SignBlockEntity sign) {
            SignText text = new SignText();
            for (int i = 0; i < 4 && i < lines.length; i++) {
                text = text.setMessage(i, Component.literal(lines[i]));
            }
            sign.setText(text, true);
            sign.setWaxed(true);
            sign.setChanged();
        }
    }
}