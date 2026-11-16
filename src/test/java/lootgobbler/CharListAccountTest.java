package lootgobbler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CharListAccountTest {
    @Test
    void theListsOwnAccountNotThePetsOne() {
        String xml = "<Chars nextCharId=\"9\"><Char id=\"3\"><ObjectType>784</ObjectType>"
                + "<Pet name=\"Tiny\"><Account><Name>SomeoneElse</Name></Account></Pet></Char>"
                + "<Char id=\"5\"><ObjectType>775</ObjectType></Char>"
                + "<Account><AccountId>123</AccountId><Name>Gunshit</Name><Vault></Vault></Account></Chars>";
        assertEquals("Gunshit", CharacterStore.listAccount(xml));
    }

    @Test
    void noAccountBlockMeansUnknown() {
        assertNull(CharacterStore.listAccount("<Chars><Char id=\"1\"></Char></Chars>"));
    }
}
