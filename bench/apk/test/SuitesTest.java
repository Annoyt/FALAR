package dev.agenttranslator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

/** Те же наборы, что гоняет bench/apk/test.sh, под JUnit 5 — чтобы их мог запускать PIT
 *  (мутационное тестирование, bench/apk/mutate.sh). Сами наборы от JUnit не зависят. */
public class SuitesTest {
  @Test void brief() { assertEquals(0, new BriefTest().run()); }
  @Test void heard() throws Exception { assertEquals(0, HeardTest.run()); }
  @Test void updates() throws Exception { assertEquals(0, UpdatesTest.run()); }
  @Test void modelStore() throws Exception { assertEquals(0, ModelStoreTest.run()); }
  @Test void chats() throws Exception { assertEquals(0, ChatsTest.run()); }
}
