package dev.agenttranslator;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.*;
import android.text.method.ScrollingMovementMethod;
import android.view.*;
import android.widget.*;

/** Два экрана. «Разговор» — то, что видит собеседник: португальская сторона крупно, русская мелко.
 *  Крупный португальский здесь не украшение, а замена озвучке: при подключённых наушниках вывести
 *  португальскую реплику в динамик нельзя ни одним штатным способом (results/2026-09-13-headphones.md),
 *  поэтому собеседник её читает. «Система» — всё остальное: кнопки, переключатели, журнал. */
public class MainActivity extends Activity implements TranslatorService.Listener {
  TextView status, logView, smallRu, hint, keyState, voiceState, voiceMsg;
  /** Крупный текст словами: под каждым словом транскрипция для чтения вслух. */
  FlowLayout bigBox; String bigText = "—", bigDir = "pt2ru", hintBase = ""; boolean bigRefined = false, cribShown = false, cribManual = false;
  Button bRefineEvery, bCloudEvery; ToggleButton tTranslitOther;
  ListView histList;
  Button bPin, bVoice, bVoice2, bWord, bBetter, bTalk, bSys, bLearn, bAnswer, bClear, bKey, bModels, bVoices, bVoiceBack, bForget, bPhoto, bType, bModeInput, bModePtt, bModeListen, bFold, bLog;
  TextView logLast;
  EditText keyIn; ToggleButton tKnown, tMicSrc; SeekBar sMic, sHold; TextView micLbl, holdLbl;
  View reviewBox; TextView revWord, revRu, revEx, revStat; Button bRevPlay, bRevShow, bRevOk, bRevNo, bRevStart;
  String revCur; boolean revOpen;
  ToggleButton tCtx, tAuto, tLang, tListenPt, tListenRu;
  View talkView, sysView, learnView, voiceView, panelInput, panelPtt, panelListen; ScrollView logScroll;
  FrameLayout panelBox; LinearLayout utilRow; int modeNow = 2; boolean folded = false;
  ListView wordList; TextView learnHint; SeekBar sMin;
  TranslatorService svc;
  ListView chatList; View sidePanel; Button bMenu, bEnd; SeekBar sPt, sRu;
  TextView sizeLbl, hintSide;
  SharedPreferences prefs;
  float szPt = 34, szRu = 17;
  /** Экран первого запуска: разрешения и загрузка моделей по манифесту; блок моделей в «Системе». */
  View setupView, tabsRow; TextView setupMic, setupText, setupProg, modelsLbl; ProgressBar setupBar;
  Button bSetupMic, bSetupDl, bSetupStop, bModelsDl, bModelsStop, bModelsVerify; ToggleButton tSetupAny, tAnyNet;
  ModelStore.State mst; boolean micForever, svcStarted;
  /** Держат крупный текст и читают его вслух; и видна ли сейчас транскрипция. */
  boolean holdingRead = false, cribVisible = false; Button bReadGuard;

  final ServiceConnection conn = new ServiceConnection() {
    public void onServiceConnected(ComponentName n, IBinder b) { svc = ((TranslatorService.LocalBinder) b).get(); svc.setListener(MainActivity.this); }
    public void onServiceDisconnected(ComponentName n) { svc = null; }
  };

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);

    prefs = getSharedPreferences("at", MODE_PRIVATE);
    szPt = prefs.getFloat("szPt", 34); szRu = prefs.getFloat("szRu", 17);

    LinearLayout tabs = new LinearLayout(this); tabs.setOrientation(LinearLayout.HORIZONTAL);
    bMenu = new Button(this); bMenu.setText("☰"); bMenu.setTextSize(16);
    bTalk = new Button(this); bTalk.setText("Разговор"); bTalk.setTextSize(15);
    bLearn = new Button(this); bLearn.setText("Изучение"); bLearn.setTextSize(15);
    bSys = new Button(this); bSys.setText("Система"); bSys.setTextSize(15);
    tabs.addView(bMenu, new LinearLayout.LayoutParams(-2, -2));
    tabs.addView(bTalk, new LinearLayout.LayoutParams(0, -2, 1f));
    tabs.addView(bLearn, new LinearLayout.LayoutParams(0, -2, 1f));
    tabs.addView(bSys, new LinearLayout.LayoutParams(0, -2, 1f));
    root.addView(tabs); tabsRow = tabs;

    // Список разговоров слева: без AndroidX выдвижной панели нет, поэтому просто колонка,
    // которая появляется по ☰ и делит ширину с содержимым.
    LinearLayout mid = new LinearLayout(this); mid.setOrientation(LinearLayout.HORIZONTAL);
    sidePanel = buildSide();
    sidePanel.setVisibility(View.GONE);
    mid.addView(sidePanel, new LinearLayout.LayoutParams(0, -1, 0.85f));   // узкая панель рвала заголовок по слову
    FrameLayout box = new FrameLayout(this);
    box.addView(talkView = buildTalk());
    box.addView(learnView = buildLearn());
    box.addView(sysView = buildSys());
    box.addView(voiceView = buildVoice());
    box.addView(setupView = buildSetup());
    mid.addView(box, new LinearLayout.LayoutParams(0, -1, 1f));
    root.addView(mid, new LinearLayout.LayoutParams(-1, 0, 1f));
    for (Button btn : new Button[]{bMenu, bTalk, bLearn, bSys, bAnswer, bEnd, bPin, bVoice, bVoice2, bWord, bBetter, bClear, bKey, bModels, bVoices, bVoiceBack, bForget, bPhoto, bType, bModeInput, bModePtt, bModeListen, bFold, bRefineEvery, bCloudEvery}) style(btn);
    for (ToggleButton tg : new ToggleButton[]{tCtx, tAuto, tLang, tKnown, tMicSrc, tListenPt, tListenRu, tTranslitOther}) style(tg);
    for (Button cb : new Button[]{bBetter, bPin, bEnd, bModeInput, bModePtt, bModeListen, bFold, bPhoto, bType}) {
      cb.setMaxLines(1); cb.setPadding(6, 22, 6, 22);   // иначе подписи ломались по слогам: «слу/ша/ть»
      cb.setEllipsize(android.text.TextUtils.TruncateAt.END);
    }
    setContentView(root);
    show(0); applySizes(); mode(2);

    bTalk.setOnClickListener(v -> show(0));
    bLearn.setOnClickListener(v -> { show(1); refreshWords(); });
    bSys.setOnClickListener(v -> show(2));
    bMenu.setOnClickListener(v -> {
      boolean open = sidePanel.getVisibility() != View.VISIBLE;
      sidePanel.setVisibility(open ? View.VISIBLE : View.GONE);
      // Миг нажатия здесь не видно — панель открывается сразу. Поэтому кнопка остаётся
      // подсвеченной, пока список открыт: видно не «нажал», а «открыто».
      bMenu.setSelected(open);
      bMenu.setTypeface(null, open ? Typeface.BOLD : Typeface.NORMAL);
      if (open) refreshChats();
    });
    bEnd.setOnClickListener(v -> {
      if (svc == null) return;
      svc.newChat("");                       // имя даст модель по содержанию, спрашивать нечего
      clearTalk("Новый разговор");
      refreshChats();
    });

    // Первый запуск: без микрофона или без обязательных моделей — экран с объяснением и кнопками,
    // а не системный диалог с порога. Всё на месте — как раньше: сервис сразу.
    boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    if (!mic || !quickModelsOk()) { show(4); refreshSetup(); }
    if (mic) {
      if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
      else startSvc();
    }

    // «Говорить» принимает любой язык: направление определяется по сказанному.
    bAnswer.setOnTouchListener((v, e) -> { pressed(v, e); return ptt(e, "ru2pt"); });
    CompoundButton.OnCheckedChangeListener lis = (v, on) -> {
      if (svc == null) return;
      svc.setListen(tListenPt.isChecked(), tListenRu.isChecked());
      setHint(!tListenPt.isChecked() && !tListenRu.isChecked() ? "Микрофон выключен"
          : tListenPt.isChecked() && tListenRu.isChecked() ? "Pode falar · слушаю оба языка, направление по реплике"
          : tListenPt.isChecked() ? "Pode falar · слушаю португальский" : "Говорите · слушаю русский");
      markListen();
    };
    tListenPt.setOnCheckedChangeListener(lis); tListenRu.setOnCheckedChangeListener(lis);
    bPin.setOnClickListener(v -> { if (svc != null && !svc.pinLast()) onLog("нечего запоминать"); });
    bBetter.setOnClickListener(v -> improve());
    tCtx.setOnCheckedChangeListener((v, on) -> { if (svc != null) svc.setContext(on); });
    bVoice.setOnTouchListener((v, e) -> { pressed(v, e); return enroll(e, "я", tLang.isChecked() ? "pt" : "ru"); });
    bVoice2.setOnTouchListener((v, e) -> { pressed(v, e); return enroll(e, "собеседник", tLang.isChecked() ? "ru" : "pt"); });
    tAuto.setOnCheckedChangeListener((v, on) -> {
      if (svc == null) return;
      svc.setAutoDir(on);
      if (on && !svc.autoDir) { v.setChecked(false); voiceMsg.setText("Сначала запишите свой голос — без профиля направление выводить не из чего."); }
      else voiceMsg.setText(on ? "Направление берётся из языка опознанного голоса." : "Направление задают кнопки «Слушать» на экране разговора.");
    });
    bModeInput.setOnClickListener(v -> { if (folded) fold(false); mode(0); });
    bModePtt.setOnClickListener(v -> { if (folded) fold(false); mode(1); });
    bModeListen.setOnClickListener(v -> { if (folded) fold(false); mode(2); });
    bFold.setOnClickListener(v -> fold(!folded));
    bModels.setOnClickListener(v -> showModels());
    // Долгое нажатие — сразу в облако, минуя офлайн-распознавание: нужно, когда оно не справилось.
    bPhoto.setOnLongClickListener(v -> { cloudPhoto = true; pickPhotoMenu(); return true; });
    bPhoto.setOnClickListener(v -> { cloudPhoto = false; pickPhotoMenu(); });

    bType.setOnClickListener(v -> {
      final EditText in = new EditText(this);
      in.setHint("что перевести"); in.setMinLines(2); in.setTextSize(16);
      new android.app.AlertDialog.Builder(this)
          .setTitle("Набрать фразу").setView(in)
          .setPositiveButton("перевести", (d, w) -> { if (svc != null) svc.typedText(in.getText().toString()); })
          .setNegativeButton("отмена", null).show();
    });
    bVoices.setOnClickListener(v -> { show(3); refreshVoice(); });
    bVoiceBack.setOnClickListener(v -> show(2));
    bForget.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
        .setTitle("Забыть голоса?")
        .setMessage("Оба профиля удалятся. Записать заново — это две секунды речи, но пока их нет, "
                  + "авто-направление по голосу работать не будет.")
        .setPositiveButton("забыть", (d, w) -> { if (svc != null) { svc.clearVoices(); tAuto.setChecked(false); refreshVoice(); voiceMsg.setText("Профили удалены."); } })
        .setNegativeButton("отмена", null).show());
    // Ключ проверяется у OpenRouter, а не просто сохраняется: ответ приходит через секунды,
    // и всё это время надо показывать, что происходит — иначе кнопку жмут второй раз.
    bKey.setOnClickListener(v -> {
      if (svc == null) return;
      final String k = keyIn.getText().toString().trim();
      keyState.setText(k.isEmpty() ? "убираю ключ…" : "проверяю ключ у OpenRouter…");
      bKey.setEnabled(false);
      // Сервис сам пишет итог в журнал, второй раз не дублируем.
      new Thread(() -> { final String r = svc.setCloudKey(k);
        runOnUiThread(() -> { keyIn.setText(""); bKey.setEnabled(true); refreshKey(r); }); }).start();
    });
    bClear.setOnClickListener(v -> {
      if (svc == null || svc.pb == null) return;
      new android.app.AlertDialog.Builder(this)
          .setTitle("Очистить выученное?")
          .setMessage("Будет удалено " + svc.pb.learnedCount + " накопленных фраз и кэш их звука.\n\n"
                    + "Затравка и пины останутся: их задавали вы. Выученное копится само, и туда "
                    + "попадает всё подряд — включая прогоны замеров, которые портят разбор слов.")
          .setPositiveButton("очистить", (d, w) -> { int n = svc.clearLearned(); onLog("очищено " + n + " фраз"); refreshWords(); })
          .setNegativeButton("отмена", null).show();
    });
    bWord.setOnClickListener(v -> { if (svc != null) { svc.addWord(((EditText) findWord()).getText().toString()); ((EditText) findWord()).setText(""); } });
  }

  EditText wordIn;
  View findWord() { return wordIn; }

  void clearTalk(String h) {
    showBig("—", "pt2ru", false); smallRu.setText(""); setHint(h); refreshHist();
  }

  /** Крупный текст словами. Подсказка для чтения вслух показывается для своих реплик (ru2pt), для
   *  реплик собеседника — по тумблеру в «Системе». Одно касание прячет или возвращает её для
   *  текущей реплики: этот же экран читает собеседник, и кириллица под словами ему мешает.
   *  Следующая реплика снова показывается по умолчанию для своего направления. */
  void showBig(String pt, String dir, boolean refined) {
    if (pt == null) pt = "—";
    // Уточнение той же реплики (refined) — не новая реплика: спрятанная касанием подсказка не возвращается.
    boolean newTurn = !dir.equals(bigDir) || (!refined && !pt.equals(bigText));
    bigText = pt; bigDir = dir; bigRefined = refined;
    if (newTurn) cribManual = false;
    if (!cribManual) cribShown = cribDefault();
    bigBox.removeAllViews();
    boolean crib = cribShown && pt.length() > 1 && pt.matches("(?s).*\\p{L}.*");
    cribVisible = crib; syncGuard();
    if (!crib) {
      TextView t = new TextView(this); t.setTextSize(szPt); t.setTypeface(null, Typeface.BOLD);
      t.setTextColor(Color.BLACK); t.setLineSpacing(0, 1.05f); t.setText(pt);
      bigBox.addView(t); return;
    }
    for (String w : pt.trim().split("\\s+")) {
      LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
      TextView t = new TextView(this); t.setTextSize(szPt); t.setTypeface(null, Typeface.BOLD); t.setTextColor(Color.BLACK); t.setText(w);
      TextView c = new TextView(this); c.setTextSize(Math.max(9, szPt * 0.45f)); c.setTextColor(Color.GRAY); c.setText(Translit.say(w));
      col.addView(t); col.addView(c); bigBox.addView(col);
    }
  }
  boolean cribDefault() { return bigDir.startsWith("ru") || prefs.getBoolean("translit_other", false); }

  void startReading() {
    holdingRead = true; buzz();
    if (!cribShown) { cribManual = true; cribShown = true; showBig(bigText, bigDir, bigRefined); }
    else syncGuard();
    refreshHint();
  }
  void stopReading() {
    if (!holdingRead) return;
    holdingRead = false;
    cribManual = false; showBig(bigText, bigDir, bigRefined);   // транскрипция возвращается к своему обычному состоянию
    refreshHint();
  }
  /** Микрофон глушим, пока человек читает вслух. Что считать чтением — задаёт настройка:
   *  только удержание текста (по умолчанию) или всё время, пока транскрипция на экране. */
  void syncGuard() {
    if (svc == null) return;
    int g = svc.readGuard;
    svc.setReadingAloud(g >= 1 && holdingRead || g == 2 && cribVisible);
  }
  void refreshReadGuard() {
    if (bReadGuard == null || svc == null) return;
    bReadGuard.setText("🔇 пока читаю вслух: " + (svc.readGuard == 0 ? "слушать всегда"
        : svc.readGuard == 1 ? "молчать, пока держу текст" : "молчать, пока видна транскрипция"));
  }

  /** Строка-подсказка: состояние, потом пометка о спрятанной транскрипции, потом тема разговора. */
  void setHint(String s) { hintBase = s == null ? "" : s; refreshHint(); }
  void refreshHint() {
    if (hint == null) return;
    String h = hintBase;
    if (holdingRead) h = "читаете вслух · микрофон не слушает";
    else if (cribDefault() && !cribShown && bigText.length() > 1) h += (h.isEmpty() ? "" : " · ") + "транскрипция скрыта · касание вернёт";
    // Причина, по которой «получше» серая, — здесь же: в журнал её никто не пойдёт читать посреди разговора.
    if (svc != null && svc.eng != null && !svc.cloudBusy) {
      String m = svc.improveMode();
      if (!m.equals("cloud") && !m.equals("local")) h += (h.isEmpty() ? "" : " · ") + "получше недоступно: " + m;
    }
    String topic = svc != null && svc.chats != null ? svc.chats.topic : "";
    if (!topic.isEmpty() && !h.contains(topic)) h += (h.isEmpty() ? "" : " · ") + topic;
    hint.setText(h);
  }

  /** Кнопка «получше» значит «улучшить сейчас»: облако, если есть ключ и сеть, иначе локальный
   *  проход при включённом контексте. Перед первой отправкой разговора — согласие, один раз. */
  void improve() {
    if (svc == null) return;
    String m = svc.improveMode();
    if (m.equals("cloud") && !svc.cloudConsent()) { consentDialog(() -> svc.improveNow()); return; }
    svc.improveNow(); refreshBetter();
  }
  void consentDialog(final Runnable then) {
    int n = svc == null || svc.chats == null ? 0 : svc.chats.size();
    new android.app.AlertDialog.Builder(this)
        .setTitle("Отправить разговор в облако?")
        .setMessage("Для пересмотра в бесплатную модель OpenRouter уйдёт разговор целиком — сейчас " + n
                  + " реплик, не больше 6000 знаков с конца, — включая слова собеседника. Это третья сторона. "
                  + "Спрашиваем один раз; отозвать можно, убрав ключ в «Системе».")
        .setPositiveButton("отправлять", (d, w) -> { prefs.edit().putBoolean("cloud_consent", true).apply(); then.run(); refreshBetter(); })
        .setNegativeButton("отмена", null).show();
  }
  void refreshBetter() {
    if (bBetter == null) return;
    if (svc == null || svc.eng == null) { bBetter.setEnabled(false); return; }
    String m = svc.improveMode();
    bBetter.setText(m.equals("cloud") ? "☁ получше" : m.equals("local") ? "🧠 получше" : "получше");
    bBetter.setEnabled(!svc.cloudBusy && (m.equals("cloud") || m.equals("local")));
    refreshHint();
  }
  void refreshIntervals() {
    if (bRefineEvery == null || svc == null) return;
    bRefineEvery.setText("🧠 разбор контекста: " + (svc.refineEvery == 0 ? "по кнопке" : "каждые " + svc.refineEvery + (svc.refineEvery == 1 ? " реплику" : " реплик")));
    bCloudEvery.setText("☁ пересмотр в облаке: " + (svc.cloudEvery == 0 ? "по кнопке" : "каждые " + svc.cloudEvery + " реплик"));
  }

  /** Тема и глоссарий разговора — по долгому нажатию на подсказку; отсюда же «убрать подсказки»
   *  и имена-кандидаты в свои слова. */
  void glossaryDialog() {
    if (svc == null || svc.chats == null) return;
    StringBuilder b = new StringBuilder();
    b.append("Тема: ").append(svc.chats.topic.isEmpty() ? "нет" : svc.chats.topic).append("\n\n");
    java.util.List<String[]> ts = svc.chats.terms();
    if (ts.isEmpty()) b.append("Глоссарий разговора пуст.");
    else {
      b.append("Глоссарий разговора — подсказка уточнителю и облаку, OPUS-MT его не видит:\n");
      for (String[] t : ts) b.append("• ").append(t[0]).append(" = ").append(t[1]).append("  ·  ").append("cloud".equals(t[2]) ? "от облака" : "от модели").append('\n');
    }
    final java.util.List<String[]> names = new java.util.ArrayList<>(svc.pendingNames);
    if (!names.isEmpty()) b.append("\nИмена от модели, которые можно добавить в свои слова: ").append(names.size());
    android.app.AlertDialog.Builder d = new android.app.AlertDialog.Builder(this)
        .setTitle("Тема и подсказки разговора").setMessage(b.toString()).setNegativeButton("закрыть", null);
    if (!ts.isEmpty()) d.setPositiveButton("убрать подсказки", (dd, w) -> { int n = svc.chats.clearTerms(); onLog("🗑 подсказки разговора убраны: " + n); refreshHint(); });
    if (!names.isEmpty()) d.setNeutralButton("имена → свои слова", (dd, w) -> namesDialog(names));
    d.show();
  }
  /** Имена собственные из облачного ответа — кандидаты в свои слова. Сами не добавляются:
   *  ложное имя в списке подменяет обычные слова, а промах безобиден. */
  void namesDialog(final java.util.List<String[]> names) {
    if (names == null || names.isEmpty() || svc == null) return;
    final String[] rows = new String[names.size()]; final boolean[] sel = new boolean[names.size()];
    for (int i = 0; i < rows.length; i++) { rows[i] = names.get(i)[0] + " ↔ " + names.get(i)[1]; sel[i] = true; }
    new android.app.AlertDialog.Builder(this).setTitle("Добавить в свои слова?")
        .setMultiChoiceItems(rows, sel, (d, w, on) -> sel[w] = on)
        .setPositiveButton("добавить", (d, w) -> {
          int n = 0;
          for (int i = 0; i < rows.length; i++) {
            final String pt = names.get(i)[0];
            svc.pendingNames.removeIf(x -> x[0].equalsIgnoreCase(pt));
            if (sel[i]) { svc.addWord(pt + " = " + names.get(i)[1]); n++; }
          }
          onLog("📝 добавлено имён: " + n);
        })
        .setNegativeButton("не сейчас", null).show();
  }

  /** Реплика на экране заново — после удаления последней. */
  void showLastTurn() {
    if (svc == null || svc.chats == null) return;
    String[] t = svc.chats.turn(svc.chats.size() - 1);
    if (t == null) { showBig("—", "pt2ru", false); smallRu.setText(""); return; }
    boolean srcPt = t[0].startsWith("pt");
    showBig(srcPt ? t[1] : t[2], t[0], !t[4].isEmpty()); smallRu.setText(srcPt ? t[2] : t[1]);
  }

  final java.util.List<String[]> histRows = new java.util.ArrayList<>();

  /** Реплики разговора, свежие сверху. Верхняя строка экрана показывает последнюю крупно,
   *  поэтому в списке её нет — иначе одно и то же читается дважды. */
  void refreshHist() {
    if (svc == null || svc.chats == null || histList == null) return;
    java.util.List<String[]> all = svc.chats.all();
    histRows.clear();
    for (int k = all.size() - 2; k >= 0; k--) histRows.add(all.get(k));
    histList.setAdapter(new ArrayAdapter<String[]>(this, 0, histRows) {
      @Override public View getView(int pos, View cv, ViewGroup parent) {
        String[] t = getItem(pos);
        boolean srcPt = t[0].startsWith("pt");
        LinearLayout row = new LinearLayout(MainActivity.this);
        row.setOrientation(LinearLayout.VERTICAL); row.setPadding(4, 10, 4, 10);
        TextView pt = new TextView(MainActivity.this);
        pt.setText(srcPt ? t[1] : t[2]); pt.setTextSize(Math.max(12, szRu)); pt.setTextColor(Color.DKGRAY);
        TextView ru = new TextView(MainActivity.this);
        ru.setText((srcPt ? t[2] : t[1]) + ("1".equals(t[4]) ? ("user".equals(t[5]) ? "  ✎" : "  ✓") : "")); pt.setTextColor(Color.DKGRAY);
        ru.setTextSize(Math.max(10, szRu - 3)); ru.setTextColor(Color.GRAY);
        row.addView(pt); row.addView(ru);
        return row;
      }
    });
    histList.setOnItemLongClickListener((p, vv, pos, id) -> {
      if (pos >= histRows.size()) return false;
      editTurn(Integer.parseInt(histRows.get(pos)[3]), histRows.get(pos)[1]);
      return true;
    });
  }

  void editTurn(int idx, String text) { turnMenu(idx); }

  /** Меню реплики. Правятся только реплики владельца на его языке (ru2pt): распознавание исказило
   *  сказанное по-русски, а фразу отдают собеседнику читать или читают вслух сами. Реплики
   *  собеседника — произнести ещё раз, удалить, перенести. */
  void turnMenu(final int idx) {
    if (svc == null || svc.chats == null) return;
    final String[] t = svc.chats.turn(idx); if (t == null) return;
    final boolean mine = t[0].startsWith("ru");
    final java.util.List<String> items = new java.util.ArrayList<>();
    if (mine) { items.add("Исправить текст"); items.add("Исправить перевод"); }
    items.add("Произнести ещё раз"); items.add("Сообщить о переводе"); items.add("Удалить реплику"); items.add("Перенести в другой разговор");
    String title = t[1].length() > 40 ? t[1].substring(0, 40) + "…" : t[1];
    new android.app.AlertDialog.Builder(this)
        .setTitle(title)
        .setItems(items.toArray(new String[0]), (d, w) -> {
          String it = items.get(w);
          if (it.equals("Исправить текст")) editSource(idx, t[1]);
          else if (it.equals("Исправить перевод")) editTranslation(idx, t[1], t[2]);
          else if (it.equals("Произнести ещё раз")) svc.sayTurn(idx);
          else if (it.equals("Сообщить о переводе")) reportTurn(t[0], t[1], t[2]);
          else if (it.equals("Удалить реплику")) { boolean wasLast = idx == svc.chats.size() - 1; if (svc.dropTurn(idx)) { refreshHist(); refreshChats(); if (wasLast) showLastTurn(); } }
          else moveTurnTo(idx);
        })
        .setNegativeButton("отмена", null).show();
  }
  /** «Сообщить о переводе»: готовое обсуждение на GitHub, человек только нажимает «отправить».
   *  Ни токенов, ни своего сервера — обычная ссылка в браузер. Разговор публичный, поэтому
   *  что именно уйдёт, написано в диалоге до того, как браузер открылся, а не после. */
  void reportTurn(final String dir, final String src, final String dst) {
    LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(40, 8, 40, 0);
    TextView was = new TextView(this); was.setTextSize(14);
    was.setText(src + "\n→ " + dst); box.addView(was);
    final EditText in = new EditText(this);
    in.setHint("как надо было перевести"); in.setMinLines(2); in.setTextSize(16); box.addView(in);
    TextView note = new TextView(this); note.setTextSize(12); note.setTextColor(Color.GRAY);
    note.setText("Откроется страница на GitHub с уже заполненным сообщением — останется нажать «отправить». "
               + "Обсуждение открытое: эта реплика, ваш вариант, версия приложения и модель телефона будут видны всем. "
               + "Остальной разговор не отправляется.");
    box.addView(note);
    new android.app.AlertDialog.Builder(this)
        .setTitle("Сообщить о переводе").setView(box)
        .setPositiveButton("открыть", (d, w) -> openReport(dir, src, dst, in.getText().toString().trim()))
        .setNegativeButton("отмена", null).show();
  }
  void openReport(String dir, String src, String dst, String mine) {
    String ver;
    try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { ver = "?"; }
    String body = "Направление: " + (dir.startsWith("pt") ? "португальский → русский" : "русский → португальский")
        + "\nИсходник: " + src
        + "\nПеревод приложения: " + dst
        + "\nКак правильно: " + (mine.isEmpty() ? "(не указано)" : mine)
        + "\n\nFalar " + ver + " · " + Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE;
    String title = "перевод: " + (src.length() > 60 ? src.substring(0, 60) + "…" : src);
    try {
      String url = "https://github.com/Annoyt/FALAR/issues/new?labels=translation"
          + "&title=" + java.net.URLEncoder.encode(title, "UTF-8")
          + "&body=" + java.net.URLEncoder.encode(body, "UTF-8");
      startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
    } catch (Exception e) { onLog("не открылось: " + e); }
  }
  void editSource(final int idx, String src) {
    final EditText in = new EditText(this);
    in.setText(src); in.setSelection(src.length()); in.setMinLines(2); in.setTextSize(16);
    new android.app.AlertDialog.Builder(this)
        .setTitle("Исправить сказанное").setView(in)
        .setPositiveButton("перевести", (d, w) -> { String x = in.getText().toString().trim(); if (!x.isEmpty() && svc != null) svc.reTranslate(idx, x); })
        .setNegativeButton("отмена", null).show();
  }
  void editTranslation(final int idx, String src, String dst) {
    LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(40, 8, 40, 0);
    TextView s = new TextView(this); s.setText(src); s.setTextSize(13); s.setTextColor(Color.GRAY); box.addView(s);
    final EditText in = new EditText(this);
    in.setText(dst); in.setSelection(dst.length()); in.setMinLines(2); in.setTextSize(16); box.addView(in);
    final CheckBox pin = new CheckBox(this); pin.setText("и запомнить как пин"); pin.setChecked(true); box.addView(pin);
    TextView note = new TextView(this); note.setTextSize(12); note.setTextColor(Color.GRAY);
    note.setText("Пин: та же русская фраза дальше переводится этим текстом мгновенно и без сети. "
               + "Числа и имена в правке должны совпасть с исходником, иначе пин не ляжет, а правка сохранится.");
    box.addView(note);
    new android.app.AlertDialog.Builder(this)
        .setTitle("Исправить перевод").setView(box)
        .setPositiveButton("сохранить", (d, w) -> {
          final String x = in.getText().toString().trim(); final boolean p = pin.isChecked();
          if (x.isEmpty() || svc == null) return;
          new Thread(() -> { final String r = svc.fixTranslation(idx, x, p); runOnUiThread(() -> onLog(r)); }, "fixtrans").start();
        })
        .setNegativeButton("отмена", null).show();
  }

  void moveTurnTo(int idx) {
    if (svc == null || svc.chats == null) return;
    java.util.List<String[]> l = svc.chats.list();
    final java.util.List<String> ids = new java.util.ArrayList<>();
    final java.util.List<String> names = new java.util.ArrayList<>();
    for (String[] c : l) {
      if (String.valueOf(svc.chats.current).equals(c[0])) continue;      // в себя переносить нечего
      ids.add(c[0]);
      names.add((c[2].isEmpty() ? "без имени" : c[2]) + " · " + c[1] + " реплик");
    }
    names.add("＋ в новый разговор");
    new android.app.AlertDialog.Builder(this)
        .setTitle("Куда перенести")
        .setItems(names.toArray(new String[0]), (d, w) -> {
          long to = w < ids.size() ? Long.parseLong(ids.get(w)) : System.currentTimeMillis();
          boolean wasLast = idx == svc.chats.size() - 1;
          if (svc.moveTurn(idx, to)) { refreshHist(); refreshChats(); if (wasLast) showLastTurn(); }
        })
        .setNegativeButton("отмена", null).show();
  }

  /** Объём и отклик на нажатие. Без этого на плоской кнопке не понять, попал ли в неё палец:
   *  фон меняется на время касания, есть рамка и скругление, и телефон коротко отзывается. */
  Button style(Button b) {
    android.graphics.drawable.GradientDrawable up = new android.graphics.drawable.GradientDrawable();
    up.setColor(0xFFF2F2F4); up.setCornerRadius(22); up.setStroke(2, 0xFFBFC3CC);
    android.graphics.drawable.GradientDrawable down = new android.graphics.drawable.GradientDrawable();
    down.setColor(0xFF4C7DF0); down.setCornerRadius(22); down.setStroke(2, 0xFF2F5BD0);
    android.graphics.drawable.GradientDrawable off = new android.graphics.drawable.GradientDrawable();
    off.setColor(0xFFEDEDF0); off.setCornerRadius(22); off.setStroke(2, 0xFFDDDDE2);
    android.graphics.drawable.StateListDrawable sl = new android.graphics.drawable.StateListDrawable();
    sl.addState(new int[]{-android.R.attr.state_enabled}, off);
    sl.addState(new int[]{android.R.attr.state_pressed}, down);
    sl.addState(new int[]{android.R.attr.state_checked}, down);
    sl.addState(new int[]{android.R.attr.state_selected}, down);
    sl.addState(new int[]{}, up);
    b.setBackground(sl);
    b.setTextColor(new android.content.res.ColorStateList(
        new int[][]{{-android.R.attr.state_enabled}, {android.R.attr.state_pressed},
                    {android.R.attr.state_checked}, {android.R.attr.state_selected}, {}},
        new int[]{0xFF9A9AA0, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFF1B1B1F}));
    b.setPadding(18, 26, 18, 26);
    b.setAllCaps(false);
    b.setHapticFeedbackEnabled(true);
    b.setStateListAnimator(null);
    b.setOnTouchListener(wrapHaptic(b));
    return b;
  }
  /** Нажатое состояние выставляем руками ТОЛЬКО для кнопок удержания: их обработчик возвращает
   *  true и съедает событие, поэтому View подсветку не ставит.
   *
   *  Обычным кнопкам это делать нельзя. View решает, был ли клик, по флагу «нажато» на отпускании,
   *  и если снять его раньше — клик не выдаётся вовсе. Один раз так и вышло: после «улучшения»
   *  перестали открываться и список разговоров, и вкладка «Система». */
  static void pressed(View v, MotionEvent e) {
    int a = e.getAction();
    if (a == MotionEvent.ACTION_DOWN) { v.setPressed(true); v.invalidate(); }
    else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) { v.setPressed(false); v.invalidate(); }
  }
  View.OnTouchListener wrapHaptic(final Button b) {
    return (v, e) -> {
      if (e.getAction() == MotionEvent.ACTION_DOWN)
        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
      return false;                                  // подсветку ставит сам View
    };
  }

  void buzz() {
    View v = getWindow().getDecorView();
    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
  }

  /** 0 разговор · 1 изучение · 2 система · 3 голоса. Голоса — отдельный режим, а не вкладка:
   *  его открывают один раз при настройке и больше туда не возвращаются, место в верхнем ряду
   *  такому не положено. Подсветка при этом остаётся на «Системе», откуда в него вошли. */
  void show(int tab) {
    talkView.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
    learnView.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
    sysView.setVisibility(tab == 2 ? View.VISIBLE : View.GONE);
    voiceView.setVisibility(tab == 3 ? View.VISIBLE : View.GONE);
    if (setupView != null) setupView.setVisibility(tab == 4 ? View.VISIBLE : View.GONE);
    if (tabsRow != null) tabsRow.setVisibility(tab == 4 ? View.GONE : View.VISIBLE);
    Button[] bs = {bTalk, bLearn, bSys};
    for (int k = 0; k < bs.length; k++) {
      boolean on = k == tab || (k == 2 && tab == 3);
      bs[k].setSelected(on); bs[k].setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
    }
  }

  /** Список разговоров: свежие сверху, галочка — улучшен ли перевод задним числом. */
  View buildSide() {
    LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(12, 8, 8, 8);
    TextView t = new TextView(this); t.setText("Разговоры"); t.setTextSize(14); t.setTextColor(Color.GRAY); v.addView(t);
    hintSide = new TextView(this); hintSide.setTextSize(11); hintSide.setTextColor(Color.GRAY); v.addView(hintSide);
    chatList = new ListView(this);
    v.addView(chatList, new LinearLayout.LayoutParams(-1, 0, 1f));
    return v;
  }

  final java.util.List<String> chatIds = new java.util.ArrayList<>();
  final java.util.List<String> chatTitles = new java.util.ArrayList<>();
  String rowsTitle(int pos) { return pos < chatTitles.size() ? chatTitles.get(pos) : ""; }
  void refreshChats() {
    if (svc == null || svc.chats == null) return;
    java.util.List<String[]> l = svc.chats.list();
    java.util.List<String> rows = new java.util.ArrayList<>();
    chatIds.clear();
    chatTitles.clear();
    // Текущий разговор отмечаем: без этого не видно, в каком из них ты сидишь, и только что
    // созданный пустой ничем не отличался от старых.
    long cur = svc.chats.current;
    for (String[] c : l) {
      boolean here = String.valueOf(cur).equals(c[0]);
      boolean empty = "0".equals(c[1]);
      chatIds.add(c[0]); chatTitles.add(c[2].isEmpty() ? (empty ? "новый разговор" : "без имени") : c[2]);
      String when = android.text.format.DateFormat.format("dd.MM HH:mm", Long.parseLong(c[0])).toString();
      rows.add((here ? "▶ " : "") + when + " · " + (empty ? "пока пусто" : c[1] + " реплик")
               + ("1".equals(c[3]) ? " ✓" : "") + "\n" + (c[2].isEmpty() && empty ? "новый разговор" : c[2]));
    }
    if (rows.isEmpty()) rows.add("пока пусто");
    hintSide.setText(chatIds.isEmpty() ? "" : "долгое нажатие — переименовать или удалить");
    chatList.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, rows) {
      @Override public View getView(int pos, View cv, ViewGroup parent) {
        TextView t = (TextView) super.getView(pos, cv, parent);
        t.setMaxLines(3); t.setEllipsize(android.text.TextUtils.TruncateAt.END); t.setTextSize(13);
        return t;
      }
    });
    chatList.setOnItemClickListener((p, vv, pos, id) -> { if (pos < chatIds.size()) openChat(Long.parseLong(chatIds.get(pos))); });
    chatList.setOnItemLongClickListener((p, vv, pos, id) -> {
      if (pos >= chatIds.size()) return false;
      final long cid = Long.parseLong(chatIds.get(pos));
      final String title = rowsTitle(pos);
      new android.app.AlertDialog.Builder(this)
          .setTitle(title)
          .setItems(new String[]{"Переименовать", "Удалить"}, (d, which) -> {
            if (which == 0) renameChat(cid, title); else confirmDelete(cid, title);
          })
          .setNegativeButton("отмена", null).show();
      return true;
    });
  }

  /** Название обычно даёт модель по содержанию разговора. Ручное переименование — на случай,
   *  когда она промахнулась; после него модель название не трогает. */
  void renameChat(long id, String was) {
    final EditText in = new EditText(this);
    in.setSingleLine(true); in.setText(was); in.setSelection(in.getText().length());
    new android.app.AlertDialog.Builder(this)
        .setTitle("Название разговора").setView(in)
        .setPositiveButton("сохранить", (d, w) -> {
          if (svc != null && svc.chats != null && svc.chats.rename(id, in.getText().toString().trim())) refreshChats();
        })
        .setNegativeButton("отмена", null).show();
  }

  void confirmDelete(long cid, String title) {
    new android.app.AlertDialog.Builder(this)
        .setTitle("Удалить разговор?")
        .setMessage(title + "\n\nУдаление окончательное: по разговорам идёт разбор слов для заучивания, "
                  + "и удалённый из него выпадет.")
        .setPositiveButton("удалить", (d, w) -> {
          if (svc == null || svc.chats == null) return;
          // Признак «удаляем тот, в котором сидим» снимаем ДО удаления: delete() сразу меняет
          // current на новый разговор, и сравнение после него было всегда ложным — экран
          // продолжал показывать реплики удалённого.
          boolean wasCurrent = svc.chats.current == cid;
          if (!svc.dropChat(cid)) return;
          onLog("разговор " + cid + " удалён");
          if (wasCurrent) clearTalk("Новый разговор");
          refreshChats();
        })
        .setNegativeButton("отмена", null).show();
  }

  /** Открыть разговор — значит продолжить его: сервис возвращает реплики в рабочую историю,
   *  а экран показывает, о чём уже говорили, с улучшённым переводом, если он посчитан.
   *
   *  Чтение с диска — в отдельном потоке: разговор на 42 реплики разбирался дважды прямо
   *  в потоке интерфейса, и по нему же открывался список. */
  void openChat(long id) {
    if (svc == null || svc.chats == null) return;
    new Thread(() -> {
      svc.openChat(id);
      final org.json.JSONObject o = svc.chats.load(id);
      runOnUiThread(() -> showChatAndGo(id, o));
    }, "openchat").start();
  }

  void showChat(long id, org.json.JSONObject o) {
    if (o == null) return;
    String who = o.optString("name", "");
    setHint((who.isEmpty() ? "разговор от " + android.text.format.DateFormat.format("dd.MM HH:mm", id) : who)
        + " · продолжаем" + (o.optBoolean("refined", false) ? " · перевод улучшен" : ""));
    // Наверху всегда последняя реплика: экран разговора для того и нужен, чтобы собеседник
    // её читал. Раньше после открытия там стояло «—», и верх экрана пустовал.
    org.json.JSONArray t = o.optJSONArray("turns");
    org.json.JSONObject last = t == null || t.length() == 0 ? null : t.optJSONObject(t.length() - 1);
    if (last == null) { showBig("—", "pt2ru", false); smallRu.setText(""); }
    else {
      boolean srcPt = last.optString("dir", "").startsWith("pt");
      String fixed = last.optString("fixed", "");
      String dst = fixed.isEmpty() ? last.optString("dst", "") : fixed;
      showBig(srcPt ? last.optString("src", "") : dst, last.optString("dir", "pt2ru"), !fixed.isEmpty());
      smallRu.setText(srcPt ? dst : last.optString("src", ""));
    }
    refreshHist(); refreshHint();
  }

  /** Открыть разговор по нажатию в списке: то же, плюс закрыть панель и уйти на экран разговора. */
  void showChatAndGo(long id, org.json.JSONObject o) {
    showChat(id, o);
    sidePanel.setVisibility(View.GONE);
    bMenu.setSelected(false); bMenu.setTypeface(null, Typeface.NORMAL);
    show(0);
  }

  /** Экран разговора: крупно португальский, мелко русский, ниже — предыдущие реплики. */
  View buildTalk() {
    LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(24, 16, 24, 16);
    hint = new TextView(this); hint.setTextSize(13); hint.setTextColor(Color.GRAY);
    hint.setText("Запуск…"); v.addView(hint);
    hint.setOnLongClickListener(x -> { glossaryDialog(); return true; });

    // Крупный текст — контейнер с переносом по словам: под каждым словом транскрипция для чтения
    // вслух. Касание прячет и возвращает её, долгое нажатие открывает меню правки реплики.
    bigBox = new FlowLayout(this); bigBox.setClickable(true); bigBox.setLongClickable(true);
    bigBox.setOnClickListener(x -> { if (bigText.length() < 2) return; cribManual = true; cribShown = !cribShown; showBig(bigText, bigDir, bigRefined); refreshHint(); });
    // Удержание крупного текста — «читаю вслух»: транскрипция показывается на время удержания,
    // и микрофон в это время не слушает. Иначе приложение слышит, как владелец произносит
    // португальскую фразу с экрана, считает его собеседником и переводит ему её же обратно.
    // Меню реплики переехало на русскую строку под текстом: жест удержания занят чтением.
    bigBox.setOnLongClickListener(x -> { if (bigText.length() < 2) return false; startReading(); return true; });
    bigBox.setOnTouchListener((bv, e) -> {
      int a = e.getAction();
      if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) stopReading();
      return false;                       // касание и удержание обрабатываются своими слушателями
    });
    v.addView(bigBox);

    smallRu = new TextView(this); smallRu.setTextSize(17); smallRu.setTextColor(Color.DKGRAY);
    smallRu.setPadding(0, 12, 0, 0); v.addView(smallRu);
    smallRu.setOnLongClickListener(x -> { if (svc == null || svc.chats == null || svc.chats.size() == 0) return false; turnMenu(svc.chats.size() - 1); return true; });

    // Прежние реплики — список, а не сплошной текст: каждую надо уметь удалить или перенести
    // в другой разговор. Случайная фраза из комнаты иначе остаётся в контексте уточнителя
    // и в разборе слов для заучивания.
    histList = new ListView(this);
    histList.setDivider(null);
    v.addView(histList, new LinearLayout.LayoutParams(-1, 0, 1f));

    // Низ экрана — ряд режимов, над ним панель выбранного. Раньше всё лежало вповалку тремя
    // рядами и съедало место у текста, который и есть главное на этом экране. Режим переключает
    // только панель: включённое прослушивание при переходе в «ввод» не выключается — можно
    // слушать монолог и одновременно сфотографировать вывеску или набрать фразу.
    panelBox = new FrameLayout(this);
    panelBox.addView(panelInput = buildInput());
    panelBox.addView(panelPtt = buildPtt());
    panelBox.addView(panelListen = buildListen());
    v.addView(panelBox);

    utilRow = new LinearLayout(this); utilRow.setOrientation(LinearLayout.HORIZONTAL);
    bBetter = new Button(this); bBetter.setText("☁ получше"); bBetter.setEnabled(false); bBetter.setTextSize(12);
    utilRow.addView(bBetter, new LinearLayout.LayoutParams(0, -2, 1f));
    bPin = new Button(this); bPin.setText("📌 запомнить"); bPin.setEnabled(false); bPin.setTextSize(12);
    utilRow.addView(bPin, new LinearLayout.LayoutParams(0, -2, 1f));
    bEnd = new Button(this); bEnd.setText("＋ новый"); bEnd.setTextSize(12);
    utilRow.addView(bEnd, new LinearLayout.LayoutParams(0, -2, 1f));
    v.addView(utilRow);

    LinearLayout bar = new LinearLayout(this); bar.setOrientation(LinearLayout.HORIZONTAL);
    bModeInput = new Button(this); bModeInput.setText("📷 ⌨"); bModeInput.setTextSize(15);
    bModePtt = new Button(this); bModePtt.setText("🎙 PT⇄RU"); bModePtt.setTextSize(14);
    bModeListen = new Button(this); bModeListen.setText("👂 слушать"); bModeListen.setTextSize(14);
    bFold = new Button(this); bFold.setText("▾"); bFold.setTextSize(15);
    bar.addView(bModeInput, new LinearLayout.LayoutParams(0, -2, 1f));
    bar.addView(bModePtt, new LinearLayout.LayoutParams(0, -2, 1.2f));
    bar.addView(bModeListen, new LinearLayout.LayoutParams(0, -2, 1.2f));
    bar.addView(bFold, new LinearLayout.LayoutParams(-2, -2));
    v.addView(bar);
    return v;
  }

  /** Ввод не голосом: снимок и набранная фраза. Доступен и во время прослушивания. */
  View buildInput() {
    LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.HORIZONTAL);
    bPhoto = new Button(this); bPhoto.setText("📷 снимок"); bPhoto.setEnabled(false); bPhoto.setTextSize(13);
    v.addView(bPhoto, new LinearLayout.LayoutParams(0, -2, 1f));
    bType = new Button(this); bType.setText("⌨ набрать"); bType.setEnabled(false); bType.setTextSize(13);
    v.addView(bType, new LinearLayout.LayoutParams(0, -2, 1f));
    return v;
  }

  /** Удержание. В подписи оба языка: кнопка принимает тот, на котором в неё говорят. */
  View buildPtt() {
    LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL);
    bAnswer = new Button(this); bAnswer.setText("🎙 удерживать · PT или RU"); bAnswer.setEnabled(false);
    v.addView(bAnswer, new LinearLayout.LayoutParams(-1, -2));
    TextView t = new TextView(this); t.setTextSize(11); t.setTextColor(Color.GRAY);
    t.setText("Направление определяется по сказанному, выбирать язык не нужно");
    v.addView(t);
    return v;
  }

  View buildListen() {
    LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL);
    LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
    tListenPt = new ToggleButton(this); tListenPt.setTextOn("Слушать PT ▶"); tListenPt.setTextOff("Слушать PT");
    tListenRu = new ToggleButton(this); tListenRu.setTextOn("Слушать RU ▶"); tListenRu.setTextOff("Слушать RU");
    row.addView(tListenPt, new LinearLayout.LayoutParams(0, -2, 1f));
    row.addView(tListenRu, new LinearLayout.LayoutParams(0, -2, 1f));
    v.addView(row);
    TextView t = new TextView(this); t.setTextSize(11); t.setTextColor(Color.GRAY);
    t.setText("Обе вместе — направление по языку каждой реплики. Микрофон открыт, только пока включено.");
    v.addView(t);
    return v;
  }

  /** Какой режим показан внизу; прослушивание при переключении не трогается. */
  void mode(int m) {
    modeNow = m;
    panelInput.setVisibility(m == 0 ? View.VISIBLE : View.GONE);
    panelPtt.setVisibility(m == 1 ? View.VISIBLE : View.GONE);
    panelListen.setVisibility(m == 2 ? View.VISIBLE : View.GONE);
    Button[] bs = {bModeInput, bModePtt, bModeListen};
    for (int k = 0; k < bs.length; k++) bs[k].setTypeface(null, k == m ? Typeface.BOLD : Typeface.NORMAL);
    for (int k = 0; k < bs.length; k++) bs[k].setSelected(k == m && !folded);
  }

  void fold(boolean on) {
    folded = on;
    panelBox.setVisibility(on ? View.GONE : View.VISIBLE);
    utilRow.setVisibility(on ? View.GONE : View.VISIBLE);
    bFold.setText(on ? "▴" : "▾");
    mode(modeNow);
  }

  /** Слушание отмечается прямо на кнопке режима: его видно и когда открыт другой режим. */
  void markListen() {
    boolean on = svc != null && (svc.listenPt || svc.listenRu);
    bModeListen.setText(on ? "👂 слушаю ▶" : "👂 слушать");
  }

  static final int REQ_PHOTO = 7;
  android.net.Uri photoUri;

  boolean cloudPhoto = false;

  void pickPhotoMenu() {
    new android.app.AlertDialog.Builder(this)
        .setTitle(cloudPhoto ? "Снимок → в облако" : "Снимок с текстом")
        .setItems(new String[]{"снять камерой", "из галереи"}, (d, w) -> pickPhoto(w == 0))
        .setNegativeButton("отмена", null).show();
  }

  void pickPhoto(boolean camera) {
    try {
      if (camera) {
        android.content.ContentValues cv = new android.content.ContentValues();
        cv.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "at_" + System.currentTimeMillis() + ".jpg");
        cv.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        // Через MediaStore, а не FileProvider: FileProvider живёт в AndroidX, которого в сборке нет.
        photoUri = getContentResolver().insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
        startActivityForResult(new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(android.provider.MediaStore.EXTRA_OUTPUT, photoUri), REQ_PHOTO);
      } else {
        startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
            .addCategory(Intent.CATEGORY_OPENABLE), REQ_PHOTO);
      }
    } catch (Throwable t) { onLog("📷 не вышло открыть: " + t); }
  }

  @Override protected void onActivityResult(int req, int res, Intent data) {
    super.onActivityResult(req, res, data);
    if (req != REQ_PHOTO || res != RESULT_OK) return;
    final android.net.Uri u = data != null && data.getData() != null ? data.getData() : photoUri;
    if (u == null || svc == null) return;
    new Thread(() -> {
      final byte[] jpeg = jpegOf(u);
      runOnUiThread(() -> {
        if (jpeg == null) { onLog("📷 снимок не прочитался"); return; }
        boolean offline = svc.ocr != null && svc.ocr.ready && !cloudPhoto;
        if (offline) { svc.photoText(jpeg, false); return; }   // офлайн — без спроса, наружу ничего не уходит
        // Офлайн-распознавания пока нет, значит снимок уйдёт наружу. Спрашиваем каждый раз:
        // на снимке может быть чужая переписка, а правило проекта — разговоры остаются здесь.
        new android.app.AlertDialog.Builder(this)
            .setTitle("Отправить снимок в облако?")
            .setMessage("Офлайн-распознавание текста ещё не подключено. Снимок (" + (jpeg.length / 1024) + " КБ) "
                      + "уйдёт бесплатной модели OpenRouter — это третья сторона. Перевод потом считается здесь, "
                      + "на устройстве.")
            .setPositiveButton("отправить", (d, w) -> svc.photoText(jpeg, true))
            .setNegativeButton("отмена", null).show();
      });
    }, "photo").start();
  }

  /** Снимок в JPEG разумного размера: 12 Мп ни распознавателю, ни каналу не нужны. */
  byte[] jpegOf(android.net.Uri u) {
    try {
      android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
      o.inJustDecodeBounds = true;
      try (java.io.InputStream in = getContentResolver().openInputStream(u)) { android.graphics.BitmapFactory.decodeStream(in, null, o); }
      // 1024 px и качество 75: бесплатные модели на крупном снимке отвечали словом «null»
      // и тратили на это до двух минут. Для печатного текста этого разрешения достаточно.
      int max = Math.max(o.outWidth, o.outHeight), s = 1;
      while (max / s > 1024) s *= 2;
      android.graphics.BitmapFactory.Options o2 = new android.graphics.BitmapFactory.Options(); o2.inSampleSize = s;
      android.graphics.Bitmap bm;
      try (java.io.InputStream in = getContentResolver().openInputStream(u)) { bm = android.graphics.BitmapFactory.decodeStream(in, null, o2); }
      if (bm == null) return null;
      java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
      bm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, bo);
      bm.recycle();
      return bo.toByteArray();
    } catch (Throwable t) { return null; }
  }

  /** Экран изучения: частотные слова португальской стороны собственных разговоров.
   *  Смысл в отборе: показываем не весь словарь, а то, что уже понадобилось несколько раз. */
  View buildLearn() {
    LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(24, 12, 24, 12);
    learnHint = new TextView(this); learnHint.setTextSize(13); learnHint.setTextColor(Color.GRAY);
    learnHint.setText("Слова из ваших разговоров, от частых к редким"); v.addView(learnHint);
    tKnown = new ToggleButton(this); tKnown.setTextOff("показываю: учу"); tKnown.setTextOn("показываю: знаю");
    tKnown.setChecked(false); v.addView(tKnown);
    tKnown.setOnCheckedChangeListener((vv, on) -> refreshWords());
    sMin = new SeekBar(this); sMin.setMax(9); sMin.setProgress(2); v.addView(sMin);   // порог: от 1 до 10 повторов
    sMin.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      public void onProgressChanged(SeekBar sb, int p, boolean u) { if (u) refreshWords(); }
      public void onStartTrackingTouch(SeekBar sb) {}
      public void onStopTrackingTouch(SeekBar sb) {}
    });
    bRevStart = new Button(this); bRevStart.setText("▶ повторение на слух"); v.addView(bRevStart);
    v.addView(reviewBox = buildReview());
    reviewBox.setVisibility(View.GONE);
    wordList = new ListView(this);
    v.addView(wordList, new LinearLayout.LayoutParams(-1, 0, 1f));
    // Свои слова — часть словаря, а не настройка приложения: имя, улица, название отеля.
    // Отсюда их видно рядом с тем, что уже выучено.
    LinearLayout rowW = new LinearLayout(this); rowW.setOrientation(LinearLayout.HORIZONTAL);
    wordIn = new EditText(this); wordIn.setHint("своё слово: отель, улица, имя"); wordIn.setSingleLine(true); wordIn.setTextSize(15);
    rowW.addView(wordIn, new LinearLayout.LayoutParams(0, -2, 1f));
    bWord = new Button(this); bWord.setText("＋"); bWord.setEnabled(false); rowW.addView(bWord, new LinearLayout.LayoutParams(-2, -2));
    v.addView(rowW);
    bClear = new Button(this); bClear.setText("🧹 очистить выученное"); bClear.setEnabled(false); bClear.setTextSize(13); v.addView(bClear);
    bRevStart.setOnClickListener(x -> { revOpen = !revOpen; reviewBox.setVisibility(revOpen ? View.VISIBLE : View.GONE);
      bRevStart.setText(revOpen ? "✕ закрыть повторение" : "▶ повторение на слух");
      if (revOpen) nextReview(); });
    return v;
  }

  /** Повторение на слух: приложение произносит слово, ответ закрыт, пока не попросят показать.
   *  Смысл именно в этом порядке — слово надо узнать ухом, а не прочитать. */
  View buildReview() {
    LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL);
    v.setPadding(18, 18, 18, 18);
    v.setBackgroundColor(0xFFF4F6FB);
    revStat = new TextView(this); revStat.setTextSize(12); revStat.setTextColor(Color.GRAY); v.addView(revStat);
    revWord = new TextView(this); revWord.setTextSize(26); revWord.setTypeface(null, Typeface.BOLD);
    revWord.setTextColor(Color.BLACK); revWord.setText("слушайте"); v.addView(revWord);
    revRu = new TextView(this); revRu.setTextSize(19); revRu.setTextColor(0xFF33507A); v.addView(revRu);
    revEx = new TextView(this); revEx.setTextSize(12); revEx.setTextColor(Color.GRAY); revEx.setMaxLines(3); v.addView(revEx);
    LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL);
    bRevPlay = new Button(this); bRevPlay.setText("🔊 ещё раз"); r.addView(bRevPlay, new LinearLayout.LayoutParams(0, -2, 1f));
    bRevShow = new Button(this); bRevShow.setText("показать"); r.addView(bRevShow, new LinearLayout.LayoutParams(0, -2, 1f));
    v.addView(r);
    LinearLayout r2 = new LinearLayout(this); r2.setOrientation(LinearLayout.HORIZONTAL);
    bRevOk = new Button(this); bRevOk.setText("помню"); r2.addView(bRevOk, new LinearLayout.LayoutParams(0, -2, 1f));
    bRevNo = new Button(this); bRevNo.setText("забыл"); r2.addView(bRevNo, new LinearLayout.LayoutParams(0, -2, 1f));
    v.addView(r2);
    for (Button b : new Button[]{bRevPlay, bRevShow, bRevOk, bRevNo, bRevStart}) if (b != null) style(b);
    bRevPlay.setOnClickListener(x -> { if (svc != null && revCur != null) svc.sayWord(revCur, "pt"); });
    bRevShow.setOnClickListener(x -> reveal());
    bRevOk.setOnClickListener(x -> answer(true));
    bRevNo.setOnClickListener(x -> answer(false));
    return v;
  }

  void nextReview() {
    if (svc == null || svc.learn == null) return;
    revCur = svc.learn.nextReview(revCur);
    if (revCur == null) {
      revWord.setText("нечего повторять");
      revRu.setText(""); revEx.setText(""); revStat.setText("Отметьте слова как известные — они попадут сюда");
      return;
    }
    revWord.setText("• • •");                       // ответ закрыт: слово надо узнать на слух
    revRu.setText(""); revEx.setText("");
    revStat.setText("Слушайте и вспоминайте · " + svc.learn.statOf(revCur));
    svc.sayWord(revCur, "pt");
  }

  void reveal() {
    if (svc == null || svc.learn == null || revCur == null) return;
    revWord.setText(revCur);
    String ru = svc.learn.wordRu.get(revCur);
    revRu.setText(ru == null ? "" : ru);
    for (Learn.Word w : svc.learn.knownWords()) if (w.w.equals(revCur) && !w.pt.isEmpty()) { revEx.setText(w.pt + "\n" + w.ru); break; }
  }

  void answer(boolean ok) {
    if (svc == null || svc.learn == null || revCur == null) return;
    svc.learn.review(revCur, ok);
    onLog(ok ? "«" + revCur + "» помню" : "«" + revCur + "» забыл — вернулось в изучение");
    nextReview();
    refreshWords();
  }

  final java.util.List<String> shownWords = new java.util.ArrayList<>();

  /** Строка изучения: слово крупно, рядом перевод самого слова, ниже мелко пример из разговора.
   *  Нажатие произносит слово — заучивают на слух, а не глазами. Долгое отмечает «знаю». */
  class WordRow extends ArrayAdapter<Learn.Word> {
    final boolean knownMode;
    WordRow(java.util.List<Learn.Word> l, boolean km) { super(MainActivity.this, 0, l); knownMode = km; }
    @Override public View getView(int pos, View cv, ViewGroup parent) {
      LinearLayout v = new LinearLayout(MainActivity.this);
      v.setOrientation(LinearLayout.VERTICAL); v.setPadding(12, 14, 12, 14);
      Learn.Word w = getItem(pos);
      LinearLayout top = new LinearLayout(MainActivity.this); top.setOrientation(LinearLayout.HORIZONTAL);
      TextView word = new TextView(MainActivity.this);
      word.setText(w.w); word.setTextSize(Math.max(18, szPt * 0.62f));
      word.setTypeface(null, Typeface.BOLD); word.setTextColor(Color.BLACK);
      top.addView(word, new LinearLayout.LayoutParams(0, -2, 1f));
      TextView cnt = new TextView(MainActivity.this);
      cnt.setText(knownMode ? "знаю" : w.n + "×"); cnt.setTextSize(13); cnt.setTextColor(Color.GRAY);
      top.addView(cnt, new LinearLayout.LayoutParams(-2, -2));
      v.addView(top);
      String ru = svc != null && svc.learn != null ? svc.learn.wordRu.get(w.w) : null;
      TextView tr = new TextView(MainActivity.this);
      tr.setText(ru == null ? "…" : ru); tr.setTextSize(Math.max(14, szRu * 1.1f)); tr.setTextColor(0xFF33507A);
      v.addView(tr);
      if (!w.pt.isEmpty()) {
        TextView ex = new TextView(MainActivity.this);
        ex.setText(w.pt + "\n" + w.ru); ex.setTextSize(Math.max(10, szRu - 3)); ex.setTextColor(Color.GRAY);
        ex.setMaxLines(3); ex.setEllipsize(android.text.TextUtils.TruncateAt.END);
        v.addView(ex);
      }
      return v;
    }
  }

  int wordsReq = 0;
  /** Разбор слов — в рабочем потоке: первый проход читает все разговоры с диска, дальше работает
   *  кэш по отпечатку файлов, и ползунок только фильтрует готовые счётчики. */
  void refreshWords() {
    if (svc == null || svc.learn == null) { learnHint.setText("движок ещё грузится"); return; }
    final boolean knownMode = tKnown != null && tKnown.isChecked();
    final int min = sMin.getProgress() + 1;
    final int req = ++wordsReq;
    sMin.setVisibility(knownMode ? View.GONE : View.VISIBLE);
    new Thread(() -> {
      final java.util.List<Learn.Word> l = knownMode ? svc.learn.knownWords() : svc.learn.top(min, 200);
      runOnUiThread(() -> { if (req == wordsReq) showWords(l, knownMode, min); });
    }, "words").start();
  }
  void showWords(java.util.List<Learn.Word> l, final boolean knownMode, int min) {
    if (svc == null || svc.learn == null) return;
    shownWords.clear();
    for (Learn.Word w : l) shownWords.add(w.w);
    if (knownMode) learnHint.setText(l.isEmpty() ? "Известных слов пока нет — отмечайте их долгим нажатием во вкладке «учу»"
                                                 : "Знаю: " + l.size() + " слов · нажатие произносит, долгое возвращает в изучение");
    else learnHint.setText(l.isEmpty()
          ? "Пока нечего показать: нужно, чтобы слово встретилось не меньше " + min + " раз"
          : "Слов от " + min + " повторов: " + l.size() + " · нажатие произносит, долгое — «знаю»");
    wordList.setAdapter(new WordRow(l, knownMode));
    wordList.setOnItemClickListener((p, vv, pos, id) -> {
      if (pos < shownWords.size() && svc != null) svc.sayWord(shownWords.get(pos), "pt");
    });
    wordList.setOnItemLongClickListener((p, vv, pos, id) -> {
      if (pos >= shownWords.size() || svc == null || svc.learn == null) return false;
      String w = shownWords.get(pos);
      svc.learn.setKnown(w, !knownMode);
      onLog(knownMode ? "«" + w + "» снова в изучении" : "«" + w + "» отмечено как известное");
      refreshWords();
      return true;
    });
    // Переводы отдельных слов считаются в фоне и подставляются, когда готовы.
    final java.util.List<String> miss = svc.learn.missingRu(l);
    if (!miss.isEmpty()) svc.translateWords(miss, this::refreshWords);
  }

  /** Экран системы: только то, что действительно настраивается, плюс состояние и журнал.
   *  Всё, что было здесь вперемешку, разъехалось по смыслу: «получше» и «запомнить» — к реплике,
   *  на экран разговора; свои слова и очистка — в «Изучение»; голоса — в отдельный режим.
   *  Причина не в красоте: среди восьми кнопок действия настройку не находили вовсе. */
  /** «Система» прокручивается целиком. Раньше это была колонка без прокрутки с журналом на
   *  остатке высоты: всё, что не влезало, молча обрезалось снизу — и при каждом новом переключателе
   *  «пропадали» настройки размера шрифта и журнал вместе с ответами приложения на нажатия.
   *  Теперь список настроек прокручивается, а журналу дана своя высота, а не остаток. */
  View buildSys() {
    LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(24, 12, 24, 12);
    status = new TextView(this); status.setTextSize(15); status.setText("Запуск сервиса…"); v.addView(status);
    TextView ml = new TextView(this); ml.setTextSize(13); ml.setTextColor(Color.GRAY);
    ml.setText("Вход: чувствительность и источник"); v.addView(ml);
    // Ползунок показывает то, что сервис применяет на самом деле. Раньше он всегда рисовал +0 дБ,
    // а работало сохранённое значение — настройка врала о собственном состоянии.
    int mg = (int) prefs.getFloat("micgain", 0);
    micLbl = new TextView(this); micLbl.setTextSize(13);
    micLbl.setText("Чувствительность микрофона: +" + mg + " дБ"); v.addView(micLbl);
    sMic = new SeekBar(this); sMic.setMax(24); sMic.setProgress(mg); v.addView(sMic);   // 0…+24 дБ
    sMic.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      public void onProgressChanged(SeekBar sb, int p, boolean u) { micLbl.setText("Чувствительность микрофона: +" + p + " дБ"); }
      public void onStartTrackingTouch(SeekBar sb) {}
      public void onStopTrackingTouch(SeekBar sb) { if (svc != null) startService(new Intent(MainActivity.this, TranslatorService.class)
          .putExtra("micgain", String.valueOf(sb.getProgress()))); }
    });
    tMicSrc = new ToggleButton(this);
    tMicSrc.setTextOff("микрофон: телефона"); tMicSrc.setTextOn("микрофон: наушников");
    // Пауза до озвучки. Перевод готовится сразу и сразу виден на экране — ждёт только голос,
    // иначе на длинном монологе перевод начинает звучать собеседнику в лицо посреди фразы.
    // Одна настройка на оба языка: длинная фраза бывает и по-русски, и по-португальски.
    int hold = prefs.getInt("hold", 1500);
    holdLbl = new TextView(this); holdLbl.setTextSize(13); v.addView(holdLbl);
    sHold = new SeekBar(this); sHold.setMax(24); sHold.setProgress(hold / 250); v.addView(sHold);   // 0…6,0 с шагом 0,25
    holdLbl.setText(holdText(hold));
    sHold.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      public void onProgressChanged(SeekBar sb, int p, boolean u) { holdLbl.setText(holdText(p * 250)); }
      public void onStartTrackingTouch(SeekBar sb) {}
      public void onStopTrackingTouch(SeekBar sb) {
        prefs.edit().putInt("hold", sb.getProgress() * 250).apply();
        startService(new Intent(MainActivity.this, TranslatorService.class).putExtra("hold", String.valueOf(sb.getProgress() * 250)));
      }
    });
    tMicSrc.setChecked("headset".equals(prefs.getString("micsrc", "builtin")));   // состояние ставим до слушателя, иначе он спросит подтверждение на ровном месте
    v.addView(tMicSrc);
    tMicSrc.setOnCheckedChangeListener((vv, on) -> {
      if (on) new android.app.AlertDialog.Builder(this)
          .setTitle("Микрофон наушников?")
          .setMessage("Наушники берут звук по профилю гарнитуры, и тогда вывод в них падает "
                    + "до телефонного качества. Годится, если телефон в кармане, а собеседник рядом с вами.")
          .setPositiveButton("включить", (d, w) -> startService(new Intent(this, TranslatorService.class).putExtra("micsrc", "headset")))
          .setNegativeButton("отмена", (d, w) -> tMicSrc.setChecked(false))
          .setOnCancelListener(d -> tMicSrc.setChecked(false))   // «назад» тоже отмена, иначе переключатель врёт
          .show();
      else startService(new Intent(this, TranslatorService.class).putExtra("micsrc", "builtin"));
    });
    // Направление здесь больше не настраивается: его задают кнопки «Слушать PT» и «Слушать RU»
    // на экране разговора. Два места для одного решения давали противоречие, и побеждало то,
    // что нажали последним.
    bVoices = new Button(this); bVoices.setText("🎤 голоса и авто-направление →"); v.addView(bVoices);
    TextView kl = new TextView(this); kl.setTextSize(13); kl.setTextColor(Color.GRAY);
    kl.setText("Ключ OpenRouter — только бесплатные модели; нужен для названий разговоров и кнопки «получше». "
             + "Пустое поле и «сохранить» — ключ убрать.");
    v.addView(kl);
    LinearLayout rowK = new LinearLayout(this); rowK.setOrientation(LinearLayout.HORIZONTAL);
    keyIn = new EditText(this); keyIn.setHint("sk-or-…"); keyIn.setSingleLine(true); keyIn.setTextSize(14);
    rowK.addView(keyIn, new LinearLayout.LayoutParams(0, -2, 1f));
    bKey = new Button(this); bKey.setText("сохранить"); bKey.setEnabled(false);
    rowK.addView(bKey, new LinearLayout.LayoutParams(-2, -2));
    v.addView(rowK);
    // Состояние ключа видно всегда. Поле после сохранения очищается, и без этой строки отличить
    // «сохранён» от «не сохранился» было нельзя — ключ вводили повторно, думая, что он не дошёл.
    keyState = new TextView(this); keyState.setTextSize(13); keyState.setTextColor(0xFF33507A); v.addView(keyState);
    bModels = new Button(this); bModels.setText("☁ модели и маршрут"); bModels.setTextSize(13); bModels.setEnabled(false);
    v.addView(bModels);
    // Файлы моделей на телефоне: что есть по манифесту, докачка необязательного, полная проверка.
    modelsLbl = new TextView(this); modelsLbl.setTextSize(13); modelsLbl.setTextColor(0xFF33507A); modelsLbl.setPadding(0, 16, 0, 0); modelsLbl.setText("Файлы моделей: проверяю…"); v.addView(modelsLbl);
    LinearLayout rowM = new LinearLayout(this); rowM.setOrientation(LinearLayout.HORIZONTAL);
    bModelsDl = new Button(this); bModelsDl.setText("⬇ скачать необязательное…"); bModelsDl.setTextSize(13); bModelsDl.setEnabled(false); rowM.addView(bModelsDl, new LinearLayout.LayoutParams(0, -2, 1f));
    bModelsStop = new Button(this); bModelsStop.setText("стоп"); bModelsStop.setTextSize(13); bModelsStop.setVisibility(View.GONE); rowM.addView(bModelsStop, new LinearLayout.LayoutParams(-2, -2));
    v.addView(rowM);
    tAnyNet = new ToggleButton(this); tAnyNet.setTextOn("качать и по мобильной сети: ВКЛ"); tAnyNet.setTextOff("качать и по мобильной сети: выкл");
    tAnyNet.setChecked(prefs.getBoolean("models_any_net", false)); v.addView(tAnyNet);
    tAnyNet.setOnCheckedChangeListener((vv, on) -> setAnyNet(on));
    bModelsVerify = new Button(this); bModelsVerify.setText("проверить файлы моделей"); bModelsVerify.setTextSize(13); bModelsVerify.setEnabled(false); v.addView(bModelsVerify);
    for (Button b : new Button[]{bModelsDl, bModelsStop, bModelsVerify}) style(b);
    style(tAnyNet);
    bModelsDl.setOnClickListener(vv -> optionalDialog());
    bModelsStop.setOnClickListener(vv -> { if (svc != null) svc.cancelModels(); });
    bModelsVerify.setOnClickListener(vv -> { if (svc != null) svc.verifyModels(); });
    tCtx = new ToggleButton(this); tCtx.setTextOn("🧠 контекст (LLM в фоне): ВКЛ"); tCtx.setTextOff("🧠 контекст (LLM в фоне): выкл"); tCtx.setChecked(false); tCtx.setEnabled(false); v.addView(tCtx);
    // Как часто разбирать контекст: локально (уточнитель 🧠) и в облаке (пересмотр всего разговора).
    // 0 — только по кнопке «получше». Кнопки перебирают значения по кругу: без AndroidX это проще
    // выпадающего списка и читается так же.
    bRefineEvery = new Button(this); bRefineEvery.setTextSize(13); bRefineEvery.setText("🧠 разбор контекста"); v.addView(bRefineEvery);
    bRefineEvery.setOnClickListener(vv -> { if (svc == null) return; svc.setRefineEvery(cycle(svc.refineEvery, new int[]{0, 1, 3, 10})); refreshIntervals(); });
    bCloudEvery = new Button(this); bCloudEvery.setTextSize(13); bCloudEvery.setText("☁ пересмотр в облаке"); v.addView(bCloudEvery);
    bCloudEvery.setOnClickListener(vv -> {
      if (svc == null) return;
      final int next = cycle(svc.cloudEvery, new int[]{0, 5, 10, 20});
      if (next > 0 && !svc.cloudConsent()) consentDialog(() -> { svc.setCloudEvery(next); refreshIntervals(); });
      else { svc.setCloudEvery(next); refreshIntervals(); }
    });
    tTranslitOther = new ToggleButton(this);
    tTranslitOther.setTextOn("транскрипция и для реплик собеседника: ВКЛ"); tTranslitOther.setTextOff("транскрипция и для реплик собеседника: выкл");
    tTranslitOther.setChecked(prefs.getBoolean("translit_other", false)); v.addView(tTranslitOther);
    tTranslitOther.setOnCheckedChangeListener((vv, on) -> { prefs.edit().putBoolean("translit_other", on).apply(); cribManual = false; showBig(bigText, bigDir, bigRefined); refreshHint(); });
    // Что считать чтением вслух. «Пока видна транскрипция» по умолчанию не ставим: она висит
    // на экране до следующей реплики, и в этом положении ответ собеседника пропускается.
    bReadGuard = new Button(this); bReadGuard.setTextSize(13); bReadGuard.setText("🔇 пока читаю вслух"); style(bReadGuard); v.addView(bReadGuard);
    bReadGuard.setOnClickListener(vv -> { if (svc == null) return; svc.setReadGuard((svc.readGuard + 1) % 3); refreshReadGuard(); syncGuard(); });
    sizeLbl = new TextView(this); sizeLbl.setTextSize(14); sizeLbl.setTextColor(Color.GRAY); v.addView(sizeLbl);
    sPt = new SeekBar(this); sPt.setMax(48); sPt.setProgress((int) szPt); v.addView(sPt);
    sRu = new SeekBar(this); sRu.setMax(48); sRu.setProgress((int) szRu); v.addView(sRu);
    SeekBar.OnSeekBarChangeListener sl = new SeekBar.OnSeekBarChangeListener() {
      public void onProgressChanged(SeekBar sb, int p, boolean u) { applySizes(); }
      public void onStartTrackingTouch(SeekBar sb) {}
      public void onStopTrackingTouch(SeekBar sb) { prefs.edit().putFloat("szPt", szPt).putFloat("szRu", szRu).apply(); }
    };
    sPt.setOnSeekBarChangeListener(sl); sRu.setOnSeekBarChangeListener(sl);
    // Журнал свёрнут: это отладочная простыня, которая занимала пол-экрана настроек. Но совсем
    // прятать ответы приложения нельзя, поэтому в свёрнутом виде видна последняя строка — то,
    // что приложение ответило на последнее нажатие.
    bLog = new Button(this); bLog.setTextSize(13); style(bLog); v.addView(bLog);
    logLast = new TextView(this); logLast.setTextSize(13); logLast.setTextColor(Color.DKGRAY);
    logLast.setSingleLine(true); logLast.setEllipsize(android.text.TextUtils.TruncateAt.END);
    logLast.setPadding(0, 2, 0, 0); v.addView(logLast);
    logView = new TextView(this); logView.setTextSize(13); logView.setMovementMethod(new ScrollingMovementMethod()); logView.setTextColor(Color.DKGRAY);
    logScroll = new ScrollView(this); logScroll.addView(logView);
    v.addView(logScroll, new LinearLayout.LayoutParams(-1, dp(260)));
    bLog.setOnClickListener(vv -> showLog(logScroll.getVisibility() != View.VISIBLE));
    showLog(prefs.getBoolean("log_open", false));
    ScrollView outer = new ScrollView(this); outer.addView(v);
    return outer;
  }
  int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
  /** Журнал под катом. Свёрнут — видна последняя строка; развёрнут — вся лента с прокруткой. */
  void showLog(boolean open) {
    if (logScroll == null) return;
    prefs.edit().putBoolean("log_open", open).apply();
    logScroll.setVisibility(open ? View.VISIBLE : View.GONE);
    logLast.setVisibility(open ? View.GONE : View.VISIBLE);
    // Треугольники ▾▴ на стендовом телефоне рисуются пустым квадратом: в системном шрифте их нет.
    // Точка-разделитель встречается по всему интерфейсу и отображается везде.
    bLog.setText(open ? "журнал · свернуть" : "журнал · развернуть");
    if (open) logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
  }

  /** Режим настройки голосов. Отдельный экран потому, что это разовая настройка со своим смыслом:
   *  приложение запоминает, как звучите вы и собеседник, и дальше выводит направление перевода
   *  из языка узнанного голоса. Состояние профилей показано прямо здесь — раньше оно было только
   *  в журнале, и снаружи запись голоса выглядела как кнопка, которая ничего не делает. */
  View buildVoice() {
    LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(24, 12, 24, 12);
    bVoiceBack = new Button(this); bVoiceBack.setText("← система"); bVoiceBack.setTextSize(13); v.addView(bVoiceBack);
    TextView t = new TextView(this); t.setTextSize(13); t.setTextColor(Color.GRAY);
    t.setText("Приложение запоминает голос и язык, на котором этот голос говорит. Дальше направление "
            + "перевода берётся из того, чей голос услышан, а не из кнопок. Держите кнопку и говорите "
            + "две-три секунды обычным голосом.");
    v.addView(t);
    voiceState = new TextView(this); voiceState.setTextSize(15); voiceState.setTextColor(Color.BLACK);
    voiceState.setPadding(0, 14, 0, 4); v.addView(voiceState);
    voiceMsg = new TextView(this); voiceMsg.setTextSize(13); voiceMsg.setTextColor(0xFF33507A); v.addView(voiceMsg);
    tLang = new ToggleButton(this); tLang.setTextOn("я говорю: PT"); tLang.setTextOff("я говорю: RU"); tLang.setChecked(false);
    v.addView(tLang);
    LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
    bVoice = new Button(this); bVoice.setText("🎤 мой голос\n(удерживать)"); bVoice.setEnabled(false);
    row.addView(bVoice, new LinearLayout.LayoutParams(0, -2, 1f));
    bVoice2 = new Button(this); bVoice2.setText("🎤 собеседник\n(удерживать)"); bVoice2.setEnabled(false);
    row.addView(bVoice2, new LinearLayout.LayoutParams(0, -2, 1f));
    v.addView(row);
    tAuto = new ToggleButton(this); tAuto.setTextOn("↔ авто-направление по голосу: ВКЛ"); tAuto.setTextOff("↔ авто-направление по голосу: выкл");
    tAuto.setChecked(false); tAuto.setEnabled(false); v.addView(tAuto);
    bForget = new Button(this); bForget.setText("забыть голоса"); bForget.setTextSize(13); bForget.setEnabled(false); v.addView(bForget);
    return v;
  }

  void refreshVoice() {
    if (voiceState == null) return;
    if (svc == null || svc.spk == null) { voiceState.setText("движок ещё грузится"); return; }
    if (!svc.spk.ready) {
      voiceState.setText("Модели отпечатка голоса нет — режим выключен");
      voiceMsg.setText("Нужен файл в models/speaker/*.onnx");
      bVoice.setEnabled(false); bVoice2.setEnabled(false); tAuto.setEnabled(false); bForget.setEnabled(false);
      return;
    }
    boolean me = svc.spk.has(Speaker.ME), other = svc.spk.has(Speaker.OTHER);
    voiceState.setText("мой голос: " + (me ? "записан" : "не записан")
                     + "\nсобеседник: " + (other ? "записан" : "не записан")
                     + (me || other ? "\nпрофили: " + svc.spk.describe() : ""));
    tAuto.setEnabled(me); bForget.setEnabled(me || other);
    tAuto.setChecked(svc.autoDir);
  }

  /** Строка состояния ключа: по началу и хвосту его можно опознать, не показывая целиком.
   *  Рядом — куда пойдёт следующий запрос: маршрут считается сам, и не видеть его результат нельзя. */
  void refreshKey(String note) {
    if (keyState == null) return;
    Cloud c = svc == null ? null : svc.cloud;
    String id = c == null ? "" : c.keyId();
    String base;
    if (id.isEmpty()) base = "ключа нет — названия разговоров и «получше» выключены";
    else {
      base = "ключ " + id + " · бесплатных моделей: " + c.modelCount()
           + "\n" + (c.auto() ? "маршрут авто → " : "закреплена ") + c.next();
      if (!c.lastUsed.isEmpty()) base += "\nпоследней отвечала " + c.lastUsed;
    }
    keyState.setText(note == null ? base : base + "\n" + note);
    if (bModels != null) bModels.setEnabled(c != null && c.modelCount() > 0);
  }

  /** Список моделей в том порядке, в котором их будет пробовать маршрутизация, с причиной порядка.
   *  Нажатие закрепляет модель, повторное — возвращает авто. Видеть список нужно потому, что
   *  «бесплатных 19» ничего не говорит: среди них визуальные и отраслевые ветки тех же семейств. */
  void showModels() {
    Cloud c = svc == null ? null : svc.cloud;
    if (c == null || c.modelCount() == 0) { refreshKey("моделей нет — сначала сохраните ключ"); return; }
    final String[] ord = c.order();
    final String[] rows = new String[ord.length + 1];
    rows[0] = "авто" + (c.auto() ? " ✓" : "");
    for (int i = 0; i < ord.length; i++) {
      Cloud.M m = c.metaOf(ord[i]);
      StringBuilder b = new StringBuilder(ord[i].replace(":free", ""));
      b.append("\n");
      b.append(m.image ? "принимает картинки" : "текстовая");
      if (Cloud.domainish(ord[i])) b.append(" · отраслевая");
      if (m.ok + m.fail > 0) b.append(" · ответов ").append(m.ok).append(", отказов ").append(m.fail);
      if (m.ms > 0) b.append(" · ").append(String.format(java.util.Locale.ROOT, "%.1f с", m.ms / 1000.0));
      if (ord[i].equals(c.pick)) b.append(" · закреплена");
      rows[i + 1] = b.toString();
    }
    new android.app.AlertDialog.Builder(this)
        .setTitle("Куда пойдёт запрос")
        .setItems(rows, (d, w) -> {
          c.choose(w == 0 ? "" : ord[w - 1]);
          onLog(w == 0 ? "☁ маршрут авто → " + c.next() : "☁ закреплена модель " + c.next());
          refreshKey(null);
        })
        .setNegativeButton("закрыть", null).show();
  }

  static int cycle(int cur, int[] opts) { for (int k = 0; k < opts.length; k++) if (opts[k] == cur) return opts[(k + 1) % opts.length]; return opts[0]; }
  static String holdText(int ms) {
    return ms == 0 ? "Пауза до озвучки: нет — говорить сразу"
                   : String.format(java.util.Locale.ROOT, "Пауза до озвучки: %.2f с тишины", ms / 1000.0);
  }

  void applySizes() {
    szPt = Math.max(12, sPt.getProgress()); szRu = Math.max(10, sRu.getProgress());
    showBig(bigText, bigDir, bigRefined); smallRu.setTextSize(szRu);
    if (histList != null && histList.getAdapter() != null) refreshHist();
    sizeLbl.setText("Размер шрифта: португальский " + (int) szPt + ", русский " + (int) szRu);
  }

  void startSvc() {
    if (svcStarted) return; svcStarted = true;
    Intent i = new Intent(this, TranslatorService.class);
    // Переносим все добавки без разбора: список имён молча терял новые флаги, и снаружи это
    // выглядело как «интент не работает».
    if (getIntent().getExtras() != null) i.putExtras(getIntent().getExtras());
    i.putExtra("fromUi", true);      // обычный запуск: сервис сбросит стендовые режимы
    startForegroundService(i); bindService(new Intent(this, TranslatorService.class), conn, Context.BIND_AUTO_CREATE);
  }
  /** Спрашиваем по имени, а не по первому ответу: когда микрофон уже разрешён и запрашиваются
   *  одни уведомления, отказ от них не должен мешать приложению запуститься. */
  @Override public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startSvc();
    else {
      status.setText("Нужен доступ к микрофону");
      // Ответ пришёл без диалога — «больше не спрашивать»: дальше только настройки приложения.
      micForever = !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO);
    }
    refreshSetup();
  }
  /** Экран ушёл — глушить микрофон больше не за что: палец с текста снят, а в положении
   *  «молчать, пока видна транскрипция» иначе приложение осталось бы глухим в кармане. */
  @Override protected void onPause() {
    super.onPause();
    holdingRead = false;
    if (svc != null) svc.setReadingAloud(false);
  }
  @Override protected void onResume() {
    super.onResume();
    syncGuard();
    if (setupView != null && setupView.getVisibility() == View.VISIBLE) {   // вернулись из настроек приложения
      if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { micForever = false; startSvc(); }
      refreshSetup();
    }
  }
  boolean enroll(MotionEvent e, String who, String lang) {
    if (svc == null) return false;
    if (e.getAction() == MotionEvent.ACTION_DOWN) { buzz(); svc.enrollStart(who, lang); return true; }
    if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) { svc.pttStop(); return true; }
    return false;
  }
  boolean ptt(MotionEvent e, String dir) {
    if (svc == null) return false;
    // Кнопкам удержания отклик ставим здесь: общий обработчик касания у них затирается своим.
    if (e.getAction() == MotionEvent.ACTION_DOWN) { buzz(); svc.pttStart(dir); return true; }
    if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) { svc.pttStop(); return true; }
    return false;
  }
  @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); if (i.hasExtra("ctx") && svc != null) { tCtx.setChecked(true); }
    if (i.getExtras() != null && !i.getExtras().isEmpty()) startService(new Intent(this, TranslatorService.class).putExtras(i));
    else startService(new Intent(this, TranslatorService.class).putExtra("fromUi", true)); }
  @Override public void onReady() {
    bPin.setEnabled(true); bBetter.setEnabled(true);
    tCtx.setEnabled(true); bClear.setEnabled(true); bKey.setEnabled(true); bVoice.setEnabled(true); bVoice2.setEnabled(true); bWord.setEnabled(true);
    bAnswer.setEnabled(true); bPhoto.setEnabled(true); bType.setEnabled(true);
    tCtx.setChecked(svc != null && svc.contextMode);
    if (svc != null) { tListenPt.setChecked(svc.listenPt); tListenRu.setChecked(svc.listenRu); }
    markListen();
    // Текущий разговор показываем сразу: при запуске список реплик оставался пустым, хотя
    // разговор продолжается — выглядело так, будто вся история пропала.
    if (svc != null && svc.chats != null) showChat(svc.chats.current, svc.chats.load(svc.chats.current));
    if (bigText.length() < 2)
      setHint(svc != null && (svc.listenPt || svc.listenRu) ? "Pode falar · здесь появится перевод"
                                                            : "Микрофон выключен — включите «Слушать» или удержание");
    refreshChats(); refreshKey(null); refreshVoice(); markListen(); refreshBetter(); refreshIntervals(); refreshReadGuard();
  }
  @Override public void onStatus(String s) { status.setText(s); }
  /** Журнал сам прокручивается к последней строке. Без этого он показывал три строки запуска
   *  и больше ничего: ответы приложения уходили за нижний край, и снаружи это выглядело так,
   *  будто кнопки молчат — именно так «потерялся» сохранённый ключ OpenRouter. */
  @Override public void onLog(String s) {
    logView.append(s + "\n\n");
    if (logLast != null) logLast.setText(s.replace('\n', ' '));   // свёрнутый журнал всё равно показывает последний ответ
    if (logScroll != null && logScroll.getVisibility() == View.VISIBLE) logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    if (s.startsWith("🎤") && voiceMsg != null) { voiceMsg.setText(s); refreshVoice(); }
    if (s.startsWith("🧠") && tCtx != null && svc != null && tCtx.isChecked() != svc.contextMode) tCtx.setChecked(svc.contextMode);
    if (s.startsWith("☁") && keyState != null && svc != null && svc.cloud != null) refreshKey(null);
    if (s.startsWith("☁") || s.startsWith("🧠")) refreshBetter();
  }
  @Override public void onHint(String s) { if (s != null) setHint(s); refreshBetter(); }
  @Override public void onHistory() { refreshHist(); refreshHint(); refreshChats(); }
  @Override public void onNames(java.util.List<String[]> names, boolean manual) {
    if (isFinishing() || isDestroyed()) return;
    if (manual) namesDialog(names);
    else onLog("📝 имена от модели: " + names.size() + " — долгое нажатие на подсказку, чтобы добавить в свои слова");
  }
  @Override public void onModels(ModelStore.State s) {
    mst = s; refreshSetup(); refreshModels();
    // Обязательное на месте и микрофон разрешён — первый экран больше не нужен; движки грузит сервис.
    if (setupView.getVisibility() == View.VISIBLE && s.checked && s.coreMissing == 0 && !s.busy()
        && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { show(0); setHint("Загружаю движки…"); }
  }

  // ---- первый запуск и файлы моделей ------------------------------------------------------
  /** Быстрая проверка по манифесту из APK: наличие и размер, без хэшей — решить, показывать ли первый экран. */
  boolean quickModelsOk() {
    try (java.io.InputStream in = getAssets().open("models_manifest.json")) {
      java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(); byte[] b = new byte[1 << 14]; int n;
      while ((n = in.read(b)) > 0) bo.write(b, 0, n);
      ModelStore ms = new ModelStore(new java.io.File(getExternalFilesDir(null), "models"), new String(bo.toByteArray(), "UTF-8"), () -> false, null, x -> {});
      return ms.quick("core").complete();
    } catch (Throwable t) { return true; }
  }
  View buildSetup() {
    ScrollView sv = new ScrollView(this);
    LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(40, 40, 40, 40); sv.addView(v);
    TextView t = new TextView(this); t.setText("Falar"); t.setTextSize(34); t.setTypeface(null, Typeface.BOLD); v.addView(t);
    TextView sub = new TextView(this); sub.setTextSize(16); sub.setText("Переводчик разговора: бразильский португальский ↔ русский, без интернета."); v.addView(sub);
    TextView warn = new TextView(this); warn.setTextSize(15); warn.setPadding(0, 24, 0, 24);
    warn.setText("Приложение слушает микрофон и распознаёт речь всех, кто рядом, — предупредите собеседника. "
               + "Распознавание, перевод и голос работают на телефоне: в интернет ничего не уходит, кроме загрузки моделей сейчас "
               + "и облачного пересмотра, который включается отдельно и с вашего согласия.");
    v.addView(warn);
    TextView h1 = new TextView(this); h1.setTextSize(17); h1.setTypeface(null, Typeface.BOLD); h1.setText("1. Микрофон"); v.addView(h1);
    setupMic = new TextView(this); setupMic.setTextSize(15); v.addView(setupMic);
    bSetupMic = new Button(this); bSetupMic.setText("разрешить микрофон"); v.addView(bSetupMic);
    TextView h2 = new TextView(this); h2.setTextSize(17); h2.setTypeface(null, Typeface.BOLD); h2.setPadding(0, 24, 0, 0); h2.setText("2. Модели"); v.addView(h2);
    setupText = new TextView(this); setupText.setTextSize(15); v.addView(setupText);
    tSetupAny = new ToggleButton(this); tSetupAny.setTextOn("качать и по мобильной сети: ВКЛ"); tSetupAny.setTextOff("качать и по мобильной сети: выкл");
    tSetupAny.setChecked(prefs.getBoolean("models_any_net", false)); v.addView(tSetupAny);
    tSetupAny.setOnCheckedChangeListener((vv, on) -> setAnyNet(on));
    LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
    bSetupDl = new Button(this); bSetupDl.setText("скачать"); bSetupDl.setEnabled(false); row.addView(bSetupDl, new LinearLayout.LayoutParams(0, -2, 1f));
    bSetupStop = new Button(this); bSetupStop.setText("стоп"); bSetupStop.setVisibility(View.GONE); row.addView(bSetupStop, new LinearLayout.LayoutParams(-2, -2));
    v.addView(row);
    setupBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); setupBar.setMax(1000); setupBar.setVisibility(View.GONE); v.addView(setupBar);
    setupProg = new TextView(this); setupProg.setTextSize(14); setupProg.setTextColor(Color.DKGRAY); v.addView(setupProg);
    for (Button b : new Button[]{bSetupMic, bSetupDl, bSetupStop}) style(b);
    bSetupMic.setOnClickListener(vv -> askMic());
    bSetupDl.setOnClickListener(vv -> { if (svc != null) svc.downloadModels("core"); });
    bSetupStop.setOnClickListener(vv -> { if (svc != null) svc.cancelModels(); });
    return sv;
  }
  void askMic() {
    if (micForever) {   // отказано с «больше не спрашивать» — система диалог не покажет
      startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + getPackageName())));
      return;
    }
    java.util.List<String> perms = new java.util.ArrayList<>();
    perms.add(Manifest.permission.RECORD_AUDIO);
    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) perms.add("android.permission.POST_NOTIFICATIONS");
    requestPermissions(perms.toArray(new String[0]), 1);
  }
  void setAnyNet(boolean on) {
    prefs.edit().putBoolean("models_any_net", on).apply();
    if (svc != null) svc.setAnyNet(on);
    if (tAnyNet != null && tAnyNet.isChecked() != on) tAnyNet.setChecked(on);
    if (tSetupAny != null && tSetupAny.isChecked() != on) tSetupAny.setChecked(on);
  }
  String progressLine(ModelStore.State s) {
    String d = ModelStore.describe(s);
    if (ModelStore.WAIT.equals(s.phase)) d += "\nНужен Wi-Fi без учёта трафика — или включите «качать и по мобильной сети»";
    if (!s.errors.isEmpty() && !s.busy()) d += "\n" + String.join("\n", s.errors);
    return d;
  }
  void refreshSetup() {
    if (setupView == null) return;
    boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    setupMic.setText(mic ? "✓ разрешён" : micForever ? "Доступ запрещён — включите микрофон в настройках приложения." : "Без микрофона слушать нечем. Разрешение спросит система.");
    bSetupMic.setText(micForever && !mic ? "открыть настройки приложения" : "разрешить микрофон"); bSetupMic.setVisibility(mic ? View.GONE : View.VISIBLE);
    ModelStore.State s = mst; boolean busy = s != null && s.busy();
    if (!mic) setupText.setText("Обязательные модели (около 2 ГБ, с Hugging Face) — после разрешения микрофона.");
    else if (svc == null || s == null || !s.checked) setupText.setText("Проверяю, что уже лежит на телефоне…");
    else if (s.coreMissing == 0 && !busy) setupText.setText("✓ модели на месте");
    else setupText.setText("Не хватает " + s.coreMissing + " файлов, " + ModelStore.mb(s.coreBytes) + " МБ. Качается с Hugging Face один раз; "
                         + "по умолчанию только по Wi-Fi. Можно остановить и продолжить позже с того же места.");
    bSetupDl.setEnabled(mic && svc != null && s != null && s.checked && !busy && s.coreMissing > 0);
    bSetupDl.setText(s != null && ModelStore.PAUSED.equals(s.phase) ? "продолжить" : "скачать" + (s != null && s.coreBytes > 0 ? " " + ModelStore.mb(s.coreBytes) + " МБ" : ""));
    bSetupStop.setVisibility(busy ? View.VISIBLE : View.GONE);
    setupBar.setVisibility(s != null && (busy || ModelStore.PAUSED.equals(s.phase)) ? View.VISIBLE : View.GONE);
    if (s != null && s.total > 0) setupBar.setProgress((int) (s.done * 1000 / s.total));
    setupProg.setText(s == null ? "" : progressLine(s));
  }
  void refreshModels() {
    if (modelsLbl == null) return;
    ModelStore.State s = mst;
    if (s == null || !s.checked) { modelsLbl.setText("Файлы моделей: проверяю…"); bModelsDl.setEnabled(false); bModelsVerify.setEnabled(false); bModelsStop.setVisibility(View.GONE); return; }
    StringBuilder sb = new StringBuilder("Файлы моделей " + (svc != null && svc.store != null ? svc.store.app : "") + ": обязательные "
      + (s.coreMissing == 0 ? "все на месте" : "нет " + s.coreMissing + " (" + ModelStore.mb(s.coreBytes) + " МБ)") + " · необязательные "
      + (s.optMissing == 0 ? "все на месте" : "нет " + s.optMissing + " (" + ModelStore.mb(s.optBytes) + " МБ)"));
    if (s.busy() || !s.message.isEmpty()) sb.append("\n").append(progressLine(s));
    modelsLbl.setText(sb);
    bModelsDl.setEnabled(!s.busy() && s.optMissing > 0);
    bModelsStop.setVisibility(ModelStore.CHECK.equals(s.phase) ? View.GONE : s.busy() ? View.VISIBLE : View.GONE);
    bModelsVerify.setEnabled(!s.busy());
    bModelsVerify.setText(ModelStore.CHECK.equals(s.phase) ? "проверяю…" : "проверить файлы моделей");
  }
  static String modelTitle(ModelStore.Item it) {
    String p = it.path;
    String t = p.startsWith("llm/") ? "🧠 контекстный уточнитель (LLM, нужен сильный телефон)" : p.startsWith("speaker/") ? "🎤 отпечаток голоса: авто-направление, разделение говорящих"
      : p.startsWith("denoiser/") ? "🔇 шумоподавитель" : p.equals("phrasebook_tatoeba.tsv") ? "📚 корпус фраз Tatoeba (190 тыс. пар)"
      : p.equals("common_words.txt") ? "📝 частотные слова: поиск имён в речи" : p;
    return t + " · " + ModelStore.mb(it.size) + " МБ";
  }
  /** Необязательное — по выбору: LLM на 1,1 ГБ слабому телефону ни к чему. */
  void optionalDialog() {
    if (svc == null || svc.store == null) return;
    new Thread(() -> { final java.util.List<ModelStore.Item> need = svc.store.check("optional").need; runOnUiThread(() -> {
      if (isFinishing() || isDestroyed()) return;
      if (need.isEmpty()) { onLog("⬇ необязательное всё на месте"); return; }
      final String[] names = new String[need.size()]; final boolean[] on = new boolean[need.size()];
      for (int k = 0; k < need.size(); k++) { names[k] = modelTitle(need.get(k)); on[k] = true; }
      new android.app.AlertDialog.Builder(this).setTitle("Скачать необязательное")
        .setMultiChoiceItems(names, on, (d, w, c) -> on[w] = c)
        .setPositiveButton("скачать", (d, w) -> { java.util.List<ModelStore.Item> sel = new java.util.ArrayList<>(); for (int k = 0; k < need.size(); k++) if (on[k]) sel.add(need.get(k)); if (!sel.isEmpty() && svc != null) svc.downloadModels(sel); })
        .setNegativeButton("отмена", null).show();
    }); }, "opt-list").start();
  }

  /** Португальская сторона всегда крупно, русская мелко — независимо от направления перевода. */
  @Override public void onTurn(String dir, String src, String dst, boolean refined) {
    boolean srcIsPt = dir.startsWith("pt");
    String pt = srcIsPt ? src : dst, ru = srcIsPt ? dst : src;
    refreshHist();                      // список строится из самого разговора, а не копится в строке
    showBig(pt, dir, refined);
    setHint((srcIsPt ? "собеседник" : "вы") + (refined ? " · уточнено" : ""));
    smallRu.setText(ru);
    refreshBetter();
  }
  @Override protected void onDestroy() { if (svc != null) { svc.setListener(null); unbindService(conn); } super.onDestroy(); }
}
