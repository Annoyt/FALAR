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
  @Test void memo() { assertEquals(0, MemoTest.run()); }
  @Test void pressure() { assertEquals(0, PressureTest.run()); }
  @Test void cloud() throws Exception { assertEquals(0, CloudTest.run()); }
  @Test void ocrCore() throws Exception { assertEquals(0, OcrCoreTest.run(new java.io.File(System.getProperty("falar.golden", "../../bench/ocr/runs/golden")))); }
  @Test void ocrWords() throws Exception { assertEquals(0, OcrWordsTest.run(new java.io.File(System.getProperty("falar.ocrwords", "../../data/ocr_words_pt.txt.gz")),
      new java.io.File(System.getProperty("falar.golden", "../../bench/ocr/runs/golden")))); }
  @Test void modules() { assertEquals(0, ModulesTest.run()); }
  @Test void textRules() { assertEquals(0, TextRulesTest.run()); }
  @Test void screen() { assertEquals(0, ScreenTest.run()); }
  @Test void voices() throws Exception { assertEquals(0, VoicesTest.run()); }
  @Test void fbank() throws Exception { assertEquals(0, FbankTest.run(System.getProperty("falar.vpgold", "/нет"), new java.io.File(System.getProperty("falar.root", ".")))); }
  @Test void bargeIn() throws Exception { assertEquals(0, BargeInTest.run(new java.io.File(System.getProperty("falar.bargegold", "../../bench/apk/test")))); }
  @Test void voiceOut() { assertEquals(0, VoiceOutTest.run()); }
  @Test void feedback() throws Exception { assertEquals(0, FeedbackTest.run(System.getProperty("falar.root", "../.."))); }
  @Test void whatsNew() throws Exception { assertEquals(0, WhatsNewTest.run(System.getProperty("falar.whatsnew", "../../bench/apk/whatsnew.txt"),
      System.getProperty("falar.manifest", "../../bench/apk/AndroidManifest.xml"))); }
  @Test void denoiseGate() { assertEquals(0, DenoiseGateTest.run()); }
  @Test void wiener() { assertEquals(0, WienerTest.run()); }
  @Test void wordList() throws Exception { assertEquals(0, WordListTest.run(System.getProperty("falar.common", "../../data/common_words.txt"))); }
}
