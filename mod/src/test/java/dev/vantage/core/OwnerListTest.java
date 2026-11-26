package dev.vantage.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class OwnerListTest {
    private static UUID id(int i) { return new UUID(0x1000L + i, 0x2000L + i); }

    private static final String HERE = "00aa00aa00aa00aa";
    private static final String OTHER = "bb11bb11bb11bb11";

    @Test
    void parsesTheServiceAnswerAndSkipsBadEntries() {
        String json = "{\"users\":[{\"uuid\":\"" + id(1) + "\",\"server\":\"" + HERE + "\"},{\"uuid\":\"not-a-uuid\",\"server\":\"menu\"},"
                + "{\"uuid\":\"" + id(2) + "\"},{\"uuid\":\"" + id(3) + "\",\"server\":\"MENU\"},7]}";
        List<OwnerList.Entry> l = OwnerList.parse(json);
        assertEquals(List.of(new OwnerList.Entry(id(1), HERE), new OwnerList.Entry(id(3), "menu")), l);
    }

    @Test
    void garbageGivesAnEmptyList() {
        assertTrue(OwnerList.parse("").isEmpty());
        assertTrue(OwnerList.parse("[]").isEmpty());
        assertTrue(OwnerList.parse("{\"error\":\"forbidden\"}").isEmpty());
        assertTrue(OwnerList.parse("{\"users\":5}").isEmpty());
        assertTrue(OwnerList.parse("{\"users\":[]}").isEmpty());
    }

    @Test
    void whereKnowsMenuHereAndElsewhere() {
        assertEquals(OwnerList.Where.MENU, OwnerList.where("menu", HERE));
        assertEquals(OwnerList.Where.MENU, OwnerList.where("menu", null));
        assertEquals(OwnerList.Where.HERE, OwnerList.where(HERE, HERE));
        assertEquals(OwnerList.Where.OTHER, OwnerList.where(OTHER, HERE));
        assertEquals(OwnerList.Where.OTHER, OwnerList.where(HERE, null)); // you are in the menu yourself: nobody is on "your server"
    }

    @Test
    void friendsComeFirstThenYourServerOtherServersMenuThenNames() {
        Map<UUID, String> names = Map.of(id(1), "zed_Test", id(2), "Alex_Test", id(3), "bob_Test", id(4), "Cy_Test", id(5), "Dee_Test");
        List<OwnerList.Entry> in = List.of(new OwnerList.Entry(id(1), "menu"), new OwnerList.Entry(id(2), OTHER), new OwnerList.Entry(id(3), HERE),
                new OwnerList.Entry(id(4), HERE), new OwnerList.Entry(id(5), "menu"));
        List<OwnerList.Row> rows = OwnerList.rows(in, HERE, names::get, Set.of("zed_Test", "Dee_Test")::contains);
        assertEquals(List.of("Dee_Test", "zed_Test", "bob_Test", "Cy_Test", "Alex_Test"), rows.stream().map(OwnerList.Row::name).toList());
        assertEquals(List.of(true, true, false, false, false), rows.stream().map(OwnerList.Row::friend).toList());
        assertEquals(OwnerList.Where.HERE, rows.get(2).where());
        assertEquals(OwnerList.Where.OTHER, rows.get(4).where());
    }

    @Test
    void sortingIgnoresCase() {
        Map<UUID, String> names = Map.of(id(1), "bravo_Test", id(2), "Alpha_Test", id(3), "charlie_Test");
        List<OwnerList.Row> rows = OwnerList.rows(List.of(new OwnerList.Entry(id(1), HERE), new OwnerList.Entry(id(2), HERE), new OwnerList.Entry(id(3), HERE)),
                HERE, names::get, n -> false);
        assertEquals(List.of("Alpha_Test", "bravo_Test", "charlie_Test"), rows.stream().map(OwnerList.Row::name).toList());
    }

    @Test
    void unknownNamesShowAShortUuidAndAreNeverFriends() {
        UUID u = UUID.fromString("0a1b2c3d-1111-2222-3333-444455556666");
        List<OwnerList.Row> rows = OwnerList.rows(List.of(new OwnerList.Entry(u, "menu")), HERE, x -> null, n -> true);
        assertEquals("0a1b2c3d", rows.get(0).name());
        assertFalse(rows.get(0).friend());
    }

    @Test
    void summaryCountsOnlineAndFriends() {
        Map<UUID, String> names = Map.of(id(1), "A_Test", id(2), "B_Test", id(3), "C_Test");
        List<OwnerList.Entry> in = List.of(new OwnerList.Entry(id(1), HERE), new OwnerList.Entry(id(2), "menu"), new OwnerList.Entry(id(3), OTHER));
        assertEquals("3 online, 2 friends", OwnerList.summary(OwnerList.rows(in, HERE, names::get, Set.of("A_Test", "C_Test")::contains)));
        assertEquals("3 online, 1 friend", OwnerList.summary(OwnerList.rows(in, HERE, names::get, "B_Test"::equals)));
        assertEquals("3 online, 0 friends", OwnerList.summary(OwnerList.rows(in, HERE, names::get, n -> false)));
        assertEquals("0 online, 0 friends", OwnerList.summary(List.of()));
        assertEquals("1 online, 1 friend", OwnerList.summary(OwnerList.rows(in.subList(0, 1), HERE, names::get, n -> true)));
    }

    @Test
    void whereTextIsPlain() {
        assertEquals("your server", OwnerList.whereText(OwnerList.Where.HERE));
        assertEquals("other server", OwnerList.whereText(OwnerList.Where.OTHER));
        assertEquals("menu", OwnerList.whereText(OwnerList.Where.MENU));
    }
}
