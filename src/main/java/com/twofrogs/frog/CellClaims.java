package com.twofrogs.frog;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Remembers where frogs have broken blocks.
 *  - History: a spot the frog has broken before breaks faster next time.
 *  - Claims: for a few seconds after a break, players cannot place a block in that exact cell.
 * Kept in memory only.
 */
public final class CellClaims {

    private static final int MAX_ENTRIES = 2048;

    private static final Map<String, Integer> BREAK_HISTORY = new LinkedHashMap<>();
    private static final Map<String, Long> CLAIMS = new HashMap<>();

    private CellClaims() {
    }

    private static String key(ServerLevel level, BlockPos pos) {
        return level.dimension().location() + "|" + pos.asLong();
    }

    public static int breakCount(ServerLevel level, BlockPos pos) {
        return BREAK_HISTORY.getOrDefault(key(level, pos), 0);
    }

    public static void recordBreak(ServerLevel level, BlockPos pos) {
        BREAK_HISTORY.merge(key(level, pos), 1, Integer::sum);
        if (BREAK_HISTORY.size() > MAX_ENTRIES) {
            Iterator<String> it = BREAK_HISTORY.keySet().iterator();
            it.next();
            it.remove();
        }
    }

    public static void claim(ServerLevel level, BlockPos pos) {
        if (CLAIMS.size() > MAX_ENTRIES) {
            CLAIMS.clear();
        }
        CLAIMS.put(key(level, pos), level.getGameTime() + FrogTuning.CLAIM_TICKS);
    }

    public static boolean isClaimed(ServerLevel level, BlockPos pos) {
        String k = key(level, pos);
        Long expiry = CLAIMS.get(k);
        if (expiry == null) {
            return false;
        }
        if (level.getGameTime() >= expiry) {
            CLAIMS.remove(k);
            return false;
        }
        return true;
    }
}