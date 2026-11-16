package lootgobbler;

import lootgobbler.CharacterStore.Char;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Alive/dead in a sync merge: whoever saw the character last decides, even when the other copy was synced later.
 */
class CharacterLifeSyncTest {

    private static Char ch(boolean alive, String diedBy, long lastPlayed, long aliveAt) {
        Char c = new Char();
        c.alive = alive;
        c.diedBy = diedBy;
        c.lastPlayed = lastPlayed;
        c.aliveAt = aliveAt;
        return c;
    }

    @Test
    void deathFromBeforeAliveAtExistedBeatsTheSameCharacterSyncedAlive() {
        // main PC: buried with an older version (no aliveAt); the server/other PC: same last play, still alive
        Char server = ch(true, null, 1000, 0);
        Char mainPc = ch(false, "user", 1000, 0);
        assertTrue(CharacterStore.takeLife(server, mainPc));
        assertFalse(server.alive);
        assertEquals("user", server.diedBy);
        // and the other way round nothing changes
        assertFalse(CharacterStore.takeLife(mainPc, ch(true, null, 1000, 0)));
        assertFalse(mainPc.alive);
    }

    @Test
    void deathAfterTheLastPlayWinsWhenItIsSyncedDaysLater() {
        Char other = ch(true, null, 1000, 0);
        Char died = ch(false, "game", 1000, 5000);
        assertTrue(CharacterStore.takeLife(other, died));
        assertFalse(other.alive);
        assertEquals(5000, other.aliveAt);
    }

    @Test
    void playingAfterAWrongBurialBringsTheCharacterBack() {
        Char buried = ch(false, "user", 1000, 2000);
        Char playedLater = ch(true, null, 9000, 0);
        assertTrue(CharacterStore.takeLife(buried, playedLater));
        assertTrue(buried.alive);
        assertNull(buried.diedBy);
    }

    @Test
    void anOlderRevivalDoesNotUndoANewerDeath() {
        Char diedLater = ch(false, "game", 1000, 8000);
        Char revivedEarlier = ch(true, null, 1000, 3000);
        assertFalse(CharacterStore.takeLife(diedLater, revivedEarlier));
        assertFalse(diedLater.alive);
    }

    @Test
    void aNewerRevivalUndoesAnOlderBurial() {
        Char buried = ch(false, "user", 1000, 3000);
        Char revived = ch(true, null, 1000, 8000);
        assertTrue(CharacterStore.takeLife(buried, revived));
        assertTrue(buried.alive);
        assertNull(buried.diedBy);
        assertEquals(8000, buried.aliveAt);
    }
}
