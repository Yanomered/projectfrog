package com.twofrogs.clue;

import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;

/** Writes the clue pages. Each page stays short enough to fit a book page. */
public final class ClueWriter {

    private static final String[] COMPASS = {
            "north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};

    private ClueWriter() {
    }

    /** The compass direction of an offset. North is -Z, east is +X. */
    public static String compass(double dx, double dz) {
        double angle = Math.toDegrees(Math.atan2(dx, -dz));
        if (angle < 0) {
            angle += 360.0;
        }
        return COMPASS[(int) Math.round(angle / 45.0) % 8];
    }

    public static int round10(int value) {
        return Math.max(10, (int) (Math.round(value / 10.0) * 10));
    }

    public static int round100(double value) {
        return Math.max(100, (int) (Math.round(value / 100.0) * 100));
    }

    // ---------- Swamp ruin books ----------

    public static String swampTitle(boolean sun) {
        return sun ? "The Sun-keeper's Road" : "The Moon-keeper's Road";
    }

    /** What the intermediate ruin looks like (these are the real landmarks built there). */
    public static String landmarkOf(boolean sun) {
        return sun
                ? "a tall stone obelisk crowned with a lantern, rising from a ring of broken pillars"
                : "a grassy hill ringed by standing stones, with a cairn that glows blue at its crown";
    }

    public static List<String> swampPages(boolean sun, int altarX, int interX, int interZ,
                                          String direction, int distance, String landmark) {
        List<String> pages = new ArrayList<>();
        if (sun) {
            pages.add("The first creature was given to the rising light, and bound at the Sun Altar.\n\n"
                    + "That altar stands on the line x = " + altarX + ".\n\nWhere along that line, this page will not say.");
        } else {
            pages.add("The second creature was given to the fading light, and bound at the Moon Altar.\n\n"
                    + "That altar stands on the line x = " + altarX + ".\n\nWhere along that line, this page will not say.");
        }
        pages.add("Seek the old shrine at x = " + interX + ", z = " + interZ + ".\n\nIt lies about " + distance
                + " paces to the " + direction + ".\n\nYou will know it by " + landmark + ".");
        pages.add("Of the creature:\n\nIt is only a frog, and looks like any other. Carry it from the swamp and it "
                + "sweats and trembles. Green slime soothes it. A lead will not hold it forever. "
                + "When it breaks free it shines, and runs for its fellow.");
        pages.add("Never let the two meet.\n\nShould this page be lost, return to where it lay and wait. "
                + "The stones remember.");
        return pages;
    }

    // ---------- Intermediate ruin books ----------

    public static String intermediateTitle(boolean sun) {
        return sun ? "Second Fragment: the Sun Road" : "Second Fragment: the Moon Road";
    }

    public static List<String> intermediatePages(boolean sun, int zMin, int zMax,
                                                 TerrainAnalyzer.Report report, Direction facing) {
        String altarName = sun ? "Sun Altar" : "Moon Altar";
        List<String> pages = new ArrayList<>();

        pages.add("The " + altarName + " stands on the line you already know.\n\n"
                + "Its place along that line lies between z = " + zMin + " and z = " + zMax + ".");

        pages.add("Seek it in " + report.biome() + " country, where " + geography(report) + ".\n\n"
                + "Its gate faces " + facingPhrase(facing) + ".");

        if (sun) {
            pages.add("The builders feared the two creatures more than any enemy. They carried each far from the other, "
                    + "and sealed each in stone beneath its own sky.\n\nLead yours into the circle, and hold it there "
                    + "until the stone is quiet.");
        } else {
            pages.add("The second keeper walked by night, so that the creature would stay calm.\n\nDo the same if you can. "
                    + "Lead it into the circle, lay a hand on the stone, and hold firm until it falls quiet.");
        }

        pages.add("Should this page be lost, return to where it lay and wait. The stones remember.");
        return pages;
    }

    private static String facingPhrase(Direction facing) {
        return switch (facing) {
            case EAST -> "the rising sun";
            case WEST -> "the setting sun";
            case NORTH -> "the cold north";
            default -> "the midday sun";
        };
    }

    /** Describes the real surroundings: only features that the analysis found are mentioned. */
    private static String geography(TerrainAnalyzer.Report r) {
        List<String> clauses = new ArrayList<>();
        if (r.river() != null) {
            clauses.add("a river runs about " + round10(r.river().distance()) + " paces to the "
                    + r.river().direction());
        }
        if (r.cliff() != null) {
            clauses.add("a cliff drops some " + r.cliff().size() + " paces, about " + round10(r.cliff().distance())
                    + " paces to the " + r.cliff().direction());
        }
        if (r.peak() != null) {
            clauses.add("a mountain climbs about " + r.peak().size() + " paces higher, "
                    + round10(r.peak().distance()) + " paces to the " + r.peak().direction());
        }
        if (r.basin()) {
            clauses.add("the land rises around it on every side, so that it lies in a hollow");
        }
        if (r.highGround()) {
            clauses.add("it crowns a high rise, with the land falling away on every side");
        }
        if (r.lake() != null) {
            clauses.add("a lake glints about " + round10(r.lake().distance()) + " paces to the "
                    + r.lake().direction());
        }
        if (r.sea() != null) {
            clauses.add("the sea lies about " + round10(r.sea().distance()) + " paces to the " + r.sea().direction());
        }
        if (r.woodland() != null) {
            clauses.add(r.woodland() + " crowds around it");
        }
        if (r.centerHeight() >= 110) {
            clauses.add("it lies high above the sea");
        }

        List<String> chosen = clauses.size() > 3 ? clauses.subList(0, 3) : clauses;
        if (chosen.isEmpty()) {
            return "no river, cliff or mountain lies within a hundred paces, only open land";
        }
        if (chosen.size() == 1) {
            return chosen.get(0);
        }
        if (chosen.size() == 2) {
            return chosen.get(0) + " and " + chosen.get(1);
        }
        return chosen.get(0) + ", " + chosen.get(1) + ", and " + chosen.get(2);
    }
}