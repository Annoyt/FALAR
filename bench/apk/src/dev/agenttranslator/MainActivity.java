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

/** Экраны под общей шапкой. «Разговор» — то, что видит собеседник: португальская сторона крупно,
 *  русская мелко. Крупный португальский здесь не украшение, а замена озвучке: при подключённых
 *  наушниках вывести португальскую реплику в динамик нельзя ни одним штатным способом
 *  (results/2026-09-13-headphones.md), поэтому собеседник её читает. «Слова» и «Настройки» — из
 *  панели ☰ (решение владельца 01.10). Макеты — design/mockups/index.html. */
public class MainActivity extends Activity implements TranslatorService.Listener {
  TextView status, logView, smallRu, hint, keyState, voiceState, voiceMsg;
  /** Крупный текст словами: под каждым словом транскрипция для чтения вслух. */
  FlowLayout bigBox; String bigText = "—", bigDir = "pt2ru", hintBase = ""; boolean bigRefined = false, cribShown = false, cribManual = false;
  /** Настройки: строки и сегменты (Rows), переключатели. */
  Rows rows; Rows.Seg segRefine, segCloudEvery, segPrefer, segReadGuard, segMicSrc; Switch tTranslitOther;
  ListView histList;
  Button bPin, bVoice, bVoice2, bWord, bBetter, bClear, bKey, bVoiceBack, bForget; View bModels, bVoices;
  TextView logLast;
  MicButton bMic;
  /** «Слушать» в доке: половинки PT и RU, кольцо «как слышно». Состояние держат tListenPt/tListenRu. */
  ListenButton bListen;
  static final String LISTEN_HINT = "Обе вместе — направление по языку каждой реплики. Микрофон открыт, только пока включено.";
  final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
  boolean meterOn = false, resumed = false;
  EditText keyIn; SeekBar sMic, sHold; TextView micLbl, holdLbl, micVal; Switch cMicAuto;
  View reviewBox; TextView revWord, revRu, revEx, revStat; Button bRevPlay, bRevShow, bRevOk, bRevNo, bRevStart;
  String revCur; boolean revOpen;
  Switch tCtx; ToggleButton tAuto, tLang, tListenPt, tListenRu;
  View talkView, sysView, learnView, voiceView, modsView, cloudView, journalView; ScrollView logScroll;
  /** Какой раздел открыт: 0 разговор · 1 слова · 2 настройки · 3 голоса · 4 первый запуск ·
   *  5 модули и файлы · 6 облако · 7 журнал (5–7 открываются из настроек). */
  int screen = 0;
  ListView wordList; TextView learnHint, minVal;
  /** «Слова»: учу или знаю, порог «встречалось не меньше», строка порога, карточка повторения. */
  Rows.Seg segKnown; boolean wordsKnown; int wordsMin = 3; View stepperRow, revCard;
  TranslatorService svc;
  /** Панель ☰ поверх экрана и затемнение под ней. */
  ListView chatList; View sidePanel, scrim; SeekBar sPt, sRu;
  TextView sizeLbl, hintSide, prevPt, prevRu;
  /** Шапка: ☰ или «←», название разговора (строка hint под ним — его состояние), «＋»; полоса хода облака и загрузок. */
  View appBar, rootView; ImageView abNav, abAct; TextView abTitle; ProgressLine abProg;
  /** Док: «Снимок, текст» слева; подпись под ним. */
  View bInput; ImageView inputIcon; TextView inputLbl;
  /** Кто сказал текущую реплику; её действия — или ход её перевода на том же месте. */
  TextView whoLbl, busyLbl; View chipsRow, busyRow, bTurn; ProgressLine stageBar;
  SharedPreferences prefs;
  /** Цвета экранов: свои, день и ночь вслед за системой (Look). */
  Look look;
  float szPt = 34, szRu = 17;
  /** Экран первого запуска: разрешения и загрузка моделей по манифесту; блок моделей в «Системе». */
  View setupView; TextView setupMic, setupText, setupProg, modelsLbl; ProgressBar setupBar;
  Button bSetupMic, bSetupDl, bSetupStop, bModelsStop, bModelsVerify, bModelsUp; ToggleButton tSetupAny; Switch tAnyNet;
  /** Модули: переключатели в «Системе» и на экране первого запуска; контейнеры их настроек. */
  final java.util.Map<String, Switch> modChecks = new java.util.HashMap<>();
  final java.util.Map<String, CheckBox> setupChecks = new java.util.HashMap<>();
  LinearLayout ttsBox, cloudBox, llmBox, modBox, keysBox, addBox; View grpTr, bKeyHelp; Button bUnused; boolean setupSynced = false;
  Rows.Row rowVoices, rowCloud, rowMods, rowLog, rowRoute; TextView modsSum;
  final java.util.Map<String, ModRow> modRows = new java.util.HashMap<>();
  ModelStore.State mst; boolean micForever, svcStarted; ScrollView bigScroll;
  TextView updLbl, updTitle; Button bUpdate, bUpdateGo; LinearLayout updCard;
  /** Экран показывает состояние сервиса галочками, а setChecked дёргает обработчик так же, как
   *  палец. Без этого признака показ «контекст включён» сам же включал контекст ещё раз и
   *  записывал это как выбор человека — LLM стартовал дважды, а «само включилось» становилось
   *  неотличимо от «включил я». */
  boolean uiSync = false;
  /** Держат крупный текст и читают его вслух; и видна ли сейчас транскрипция. */
  boolean holdingRead = false, cribVisible = false;

  final ServiceConnection conn = new ServiceConnection() {
    public void onServiceConnected(ComponentName n, IBinder b) { svc = ((TranslatorService.LocalBinder) b).get(); svc.setListener(MainActivity.this); }
    public void onServiceDisconnected(ComponentName n) { svc = null; }
  };

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    look = new Look(this); rows = new Rows(this, look);
    // Камера занимает много памяти, и пока она открыта, система выгружает Falar; снимок потом
    // приходит в заново созданный экран. Адрес снимка и ждущий снимок — из сохранённого состояния,
    // иначе снимок терялся молча (владелец, 29.09: «реальная картинка из камеры не прилетела»).
    if (b != null) {
      String pu = b.getString(K_PHOTO_URI), pp = b.getString(K_PENDING);
      if (pu != null) photoUri = android.net.Uri.parse(pu);
      if (pp != null) pendingPhoto = android.net.Uri.parse(pp);
      cloudPhoto = b.getBoolean(K_CLOUD_PHOTO, false); pendingCloud = b.getBoolean(K_PENDING_CLOUD, false);
    }
    prefs = getSharedPreferences("at", MODE_PRIVATE);
    szPt = prefs.getFloat("szPt", 34); szRu = prefs.getFloat("szRu", 17);

    // Шапка и под ней один из разделов. Вкладок больше нет: они занимали ряд над текстом, а
    // «Слова» и «Настройки» посреди разговора не нужны — они в панели ☰ (решение владельца 01.10).
    // Панель выезжает поверх экрана. Раньше без AndroidX она была колонкой, делила ширину с
    // текстом, и нижние кнопки обрезались: «получ…», «запо…» (стенд, 30.09).
    FrameLayout top = new FrameLayout(this); top.setBackgroundColor(look.bg);
    LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
    top.addView(root, new FrameLayout.LayoutParams(-1, -1));
    root.addView(appBar = buildAppBar());
    FrameLayout box = new FrameLayout(this);
    box.addView(talkView = buildTalk());
    box.addView(learnView = buildLearn());
    box.addView(sysView = buildSys());
    box.addView(voiceView = buildVoice());
    box.addView(modsView = buildMods());
    box.addView(cloudView = buildCloud());
    box.addView(journalView = buildLog());
    box.addView(setupView = buildSetup());
    root.addView(box, new LinearLayout.LayoutParams(-1, 0, 1f));
    scrim = new View(this); scrim.setBackgroundColor(0x80180814); scrim.setVisibility(View.GONE);
    scrim.setOnClickListener(v -> drawer(false));
    top.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
    sidePanel = buildSide(); sidePanel.setVisibility(View.GONE);
    top.addView(sidePanel, new FrameLayout.LayoutParams(dp(318), -1, Gravity.START));
    for (Button btn : new Button[]{bVoice, bVoice2, bVoiceBack, bForget}) style(btn);
    for (ToggleButton tg : new ToggleButton[]{tAuto, tLang}) style(tg);
    setContentView(top); rootView = top;
    show(0); applySizes();

    // Первый запуск: без микрофона или без обязательных моделей — экран с объяснением и кнопками,
    // а не системный диалог с порога. Всё на месте — как раньше: сервис сразу.
    boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    if (!mic || !quickModelsOk()) { show(4); refreshSetup(); }
    if (mic) {
      if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
      else startSvc();
    }

    // «Говорить» принимает любой язык: направление определяется по сказанному.
    // Позицию кнопки ставит удержание, а не нажатость вида: палец, съехавший с кнопки, запись не
    // останавливает, и пульс не должен гаснуть, пока микрофон ещё пишет.
    bMic.setOnTouchListener((v, e) -> {
      boolean r = ptt(e, "ru2pt");
      if (r) { bMic.setActive(e.getAction() == MotionEvent.ACTION_DOWN); meter(); }
      return r;
    });
    CompoundButton.OnCheckedChangeListener lis = (v, on) -> {
      if (svc == null || uiSync) return;
      svc.setListen(tListenPt.isChecked(), tListenRu.isChecked());
      setHint(!tListenPt.isChecked() && !tListenRu.isChecked() ? "Микрофон выключен"
          : tListenPt.isChecked() && tListenRu.isChecked() ? "Pode falar · слушаю оба языка, направление по реплике"
          : tListenPt.isChecked() ? "Pode falar · слушаю португальский" : "Говорите · слушаю русский");
      markListen();
    };
    tListenPt.setOnCheckedChangeListener(lis); tListenRu.setOnCheckedChangeListener(lis);
    bPin.setOnClickListener(v -> { if (svc != null && !svc.pinLast()) onLog("нечего запоминать"); });
    bBetter.setOnClickListener(v -> improve());
    tCtx.setOnCheckedChangeListener((v, on) -> { if (svc != null && !uiSync) svc.setContext(on); });
    bVoice.setOnTouchListener((v, e) -> { pressed(v, e); return enroll(e, "я", tLang.isChecked() ? "pt" : "ru"); });
    bVoice2.setOnTouchListener((v, e) -> { pressed(v, e); return enroll(e, "собеседник", tLang.isChecked() ? "ru" : "pt"); });
    tAuto.setOnCheckedChangeListener((v, on) -> {
      if (svc == null) return;
      svc.setAutoDir(on);
      if (on && !svc.autoDir) { v.setChecked(false); voiceMsg.setText("Сначала запишите свой голос — без профиля направление выводить не из чего."); }
      else voiceMsg.setText(on ? "Направление берётся из языка опознанного голоса." : "Направление задают кнопки «Слушать» на экране разговора.");
    });
    bModels.setOnClickListener(v -> showModels());
    // «Снимок, текст»: одно касание — камера, галерея или набрать фразу. Долгое нажатие — снимок
    // сразу в облако, минуя офлайн-распознавание: нужно, когда оно не справилось.
    bInput.setOnClickListener(v -> inputMenu());
    bInput.setOnLongClickListener(v -> { if (svc == null || !svc.mod(Modules.CLOUD)) return false; cloudPhoto = true; pickPhotoMenu(); return true; });
    // Половинки «Слушать» переключают те же PT и RU, что прежние две кнопки: одну, другую или обе.
    bListen.onHalf = pt -> { if (pt) tListenPt.toggle(); else tListenRu.toggle(); };
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
      // Пустое поле — ничего не делаем. Раньше «сохранить» с пустым полем стирал сохранённый ключ:
      // поле после сохранения очищается, и второе нажатие молча убирало рабочий ключ (владелец, 29.09).
      if (k.isEmpty()) { refreshKey("поле пустое — сохранённый ключ не тронут; ключ — кнопкой «как получить ключ»"); return; }
      refreshKey("проверяю ключ у OpenRouter…");
      bKey.setEnabled(false);
      // Сервис сам пишет итог в журнал, второй раз не дублируем.
      new Thread(() -> { final String r = svc.setCloudKey(k);
        runOnUiThread(() -> { keyIn.setText(""); bKey.setEnabled(true); if (r.contains("сохранён")) addBox.setVisibility(View.GONE); refreshKey(r); }); }).start();
    });
    bKeyHelp.setOnClickListener(v -> keyHelpDialog());
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
    showBig("—", "pt2ru", false); smallRu.setText(""); setWho(null); setHint(h); refreshHist();
  }

  void newChat() {
    if (svc == null) return;
    svc.newChat("");                       // имя даст модель по содержанию, спрашивать нечего
    clearTalk("Новый разговор");
    refreshChats();
  }

  /** «Снимок, текст»: камера, галерея или набрать фразу. Без чтения снимков — сразу набор. */
  void inputMenu() {
    if (svc == null || !Modules.photo(svc.modules)) { typeDialog(); return; }
    new android.app.AlertDialog.Builder(this)
        .setItems(new String[]{"Снять камерой", "Из галереи", "Набрать фразу"}, (d, w) -> {
          if (w == 2) typeDialog(); else { cloudPhoto = false; pickPhoto(w == 0); }
        }).show();
  }
  void typeDialog() {
    final EditText in = new EditText(this);
    in.setHint("что перевести"); in.setMinLines(2); in.setTextSize(16);
    new android.app.AlertDialog.Builder(this)
        .setTitle("Набрать фразу").setView(in)
        .setPositiveButton("перевести", (d, w) -> { if (svc != null) svc.typedText(in.getText().toString()); })
        .setNegativeButton("отмена", null).show();
  }

  // ---- шапка, панель ☰, значки ------------------------------------------------------------
  android.graphics.drawable.Drawable icon(int res, int color) {
    android.graphics.drawable.Drawable d = getDrawable(res).mutate(); d.setTint(color); return d;
  }
  android.graphics.drawable.GradientDrawable round(int color, float radiusDp, int strokeColor) {
    android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
    g.setColor(color); g.setCornerRadius(dp((int) radiusDp)); if (strokeColor != 0) g.setStroke(Math.max(1, dp(1)), strokeColor);
    return g;
  }
  /** Фон с откликом на касание: обычный и нажатый. */
  android.graphics.drawable.StateListDrawable states(android.graphics.drawable.Drawable up, android.graphics.drawable.Drawable down) {
    android.graphics.drawable.StateListDrawable sl = new android.graphics.drawable.StateListDrawable();
    sl.addState(new int[]{android.R.attr.state_pressed}, down); sl.addState(new int[]{}, up); return sl;
  }
  final View.OnTouchListener haptic = (v, e) -> {
    if (e.getAction() == MotionEvent.ACTION_DOWN) v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
    return false;
  };
  /** Круглая кнопка шапки со значком; fill — на полупрозрачном круге («＋»). */
  ImageView barButton(int res, boolean fill) {
    ImageView b = new ImageView(this); b.setScaleType(ImageView.ScaleType.CENTER);
    b.setImageDrawable(icon(res, 0xFFFFFFFF));
    b.setBackground(states(round(fill ? 0x24FFFFFF : 0, 22, 0), round(0x40FFFFFF, 22, 0)));
    b.setClickable(true); b.setOnTouchListener(haptic);
    return b;
  }

  /** Шапка — сливовая, одна на все разделы (решение владельца 01.10). Слева ☰ или «←»; посередине
   *  название разговора и строка его состояния; справа «＋» — новый разговор. Касание названия,
   *  как прежде касание строки-подсказки, открывает «Память разговора». По нижнему краю — ход того,
   *  что идёт со всем разговором: пересмотра в облаке и загрузок (ход реплики — в самой реплике). */
  View buildAppBar() {
    FrameLayout bar = new FrameLayout(this);
    bar.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
        look.night ? new int[]{0xFF4A1530, 0xFF1D0816} : new int[]{Look.PLUM, Look.DEEP}));
    LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(dp(4), 0, dp(6), 0);
    abNav = barButton(app.falar.R.drawable.ic_menu, false);
    row.addView(abNav, new LinearLayout.LayoutParams(dp(44), dp(44)));
    LinearLayout ttl = new LinearLayout(this); ttl.setOrientation(LinearLayout.VERTICAL); ttl.setId(app.falar.R.id.hint);
    ttl.setPadding(dp(6), 0, dp(6), 0); ttl.setGravity(Gravity.CENTER_VERTICAL);
    abTitle = new TextView(this); abTitle.setTextSize(17); abTitle.setTextColor(0xFFFFFFFF); abTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    abTitle.setSingleLine(true); abTitle.setEllipsize(android.text.TextUtils.TruncateAt.END); abTitle.setText("Falar");
    hint = new TextView(this); hint.setTextSize(12.5f); hint.setTextColor(0xC0FFFFFF); hint.setText("Запуск…");
    hint.setSingleLine(true); hint.setEllipsize(android.text.TextUtils.TruncateAt.END);
    ttl.addView(abTitle); ttl.addView(hint);
    row.addView(ttl, new LinearLayout.LayoutParams(0, dp(56), 1f));
    abAct = barButton(app.falar.R.drawable.ic_plus, true); abAct.setContentDescription("Новый разговор");
    row.addView(abAct, new LinearLayout.LayoutParams(dp(44), dp(44)));
    bar.addView(row, new FrameLayout.LayoutParams(-1, dp(56)));
    abProg = new ProgressLine(this).colors(Look.MINT, 0x29FFFFFF, Look.MINT, 0x29FFFFFF);
    abProg.setVisibility(View.GONE);
    bar.addView(abProg, new FrameLayout.LayoutParams(-1, dp(4), Gravity.BOTTOM));
    ttl.setOnClickListener(x -> { if (screen == 0) memoDialog(); });
    ttl.setOnLongClickListener(x -> { if (screen != 0) return false; memoDialog(); return true; });
    abNav.setOnClickListener(x -> { if (screen == 0) drawer(sidePanel.getVisibility() != View.VISIBLE); else onBackPressed(); });
    abAct.setOnClickListener(x -> { if (screen == 0) newChat(); else if (screen == 1) wordsMenu(); });
    return bar;
  }

  /** «⋯» в шапке «Слов»: очистить выученное — редкое и необратимое, поэтому не на экране. */
  void wordsMenu() {
    PopupMenu pm = new PopupMenu(this, abAct);
    pm.getMenu().add(0, 1, 0, "Очистить выученное…").setEnabled(bClear != null && bClear.isEnabled());
    pm.setOnMenuItemClickListener(it -> { if (it.getItemId() == 1) bClear.performClick(); return true; });
    pm.show();
  }

  /** Шапка под раздел: у разговора — ☰, название и «＋»; у «Слов» — «←» и «⋯»; у остальных — «←». */
  void refreshAppBar() {
    if (abTitle == null) return;
    boolean talk = screen == 0;
    abNav.setImageDrawable(icon(talk ? app.falar.R.drawable.ic_menu : app.falar.R.drawable.ic_back, 0xFFFFFFFF));
    abNav.setContentDescription(talk ? "Разговоры, слова, настройки" : "Назад");
    abAct.setVisibility(talk || screen == 1 ? View.VISIBLE : View.GONE);
    abAct.setImageDrawable(icon(talk ? app.falar.R.drawable.ic_plus : app.falar.R.drawable.ic_more, 0xFFFFFFFF));
    abAct.setContentDescription(talk ? "Новый разговор" : "Ещё");
    if (talk) { refreshHint(); return; }
    abTitle.setText(screen == 1 ? "Слова" : screen == 2 ? "Настройки" : screen == 3 ? "Голоса" : screen == 5 ? "Модули и файлы" : screen == 6 ? "Облако" : screen == 7 ? "Журнал" : "Falar");
    hint.setText(screen == 1 ? "из ваших разговоров" : ""); hint.setVisibility(screen == 1 ? View.VISIBLE : View.GONE);
    abProg.setVisibility(View.GONE);
  }

  /** Название в шапке: имя разговора (его даёт модель или человек), иначе тема, иначе дата. */
  String chatTitle() {
    if (svc == null || svc.chats == null) return "Falar";
    Chats c = svc.chats;
    if (!c.name.isEmpty()) return c.name;
    if (!c.topic.isEmpty()) return c.topic;
    return c.size() == 0 ? "Новый разговор" : "Разговор от " + android.text.format.DateFormat.format("dd.MM HH:mm", c.current);
  }

  /** Панель ☰ выезжает поверх экрана за 200 мс — одна анимация на открытие, не постоянная. */
  void drawer(boolean open) {
    if (sidePanel == null || open == (sidePanel.getVisibility() == View.VISIBLE && sidePanel.getTranslationX() == 0)) return;
    float w = dp(318);
    sidePanel.animate().cancel(); scrim.animate().cancel();
    if (open) {
      refreshChats();
      sidePanel.setVisibility(View.VISIBLE); scrim.setVisibility(View.VISIBLE);
      sidePanel.setTranslationX(-w); scrim.setAlpha(0);
      sidePanel.animate().translationX(0).setDuration(200).start(); scrim.animate().alpha(1).setDuration(200).start();
    } else {
      sidePanel.animate().translationX(-w).setDuration(180).withEndAction(() -> sidePanel.setVisibility(View.GONE)).start();
      scrim.animate().alpha(0).setDuration(180).withEndAction(() -> scrim.setVisibility(View.GONE)).start();
    }
  }

  /** «Назад»: закрыть панель; из «Голосов» — в настройки; из «Слов» и «Настроек» — к разговору. */
  @Override public void onBackPressed() {
    if (sidePanel != null && sidePanel.getVisibility() == View.VISIBLE) { drawer(false); return; }
    if (screen == 3 || screen >= 5) { show(2); return; }
    if (screen == 1 || screen == 2) { show(0); return; }
    super.onBackPressed();
  }

  /** Кто сказал текущую реплику — точкой цвета: слива — вы, золото — собеседник. */
  void setWho(String dir) {
    if (whoLbl == null) return;
    if (dir == null || bigText.length() < 2) { whoLbl.setText(""); whoLbl.setCompoundDrawables(null, null, null, null); return; }
    boolean me = dir.startsWith("ru");
    android.graphics.drawable.GradientDrawable dot = round(me ? Look.PLUM : Look.GOLD, 4, 0);
    dot.setBounds(0, 0, dp(8), dp(8));
    whoLbl.setCompoundDrawables(dot, null, null, null);
    whoLbl.setText(me ? "вы · по-португальски" : "собеседник");
  }

  /** Кнопка-«чип» под репликой: скруглённая рамка, значок слева. */
  Button chip(String text, int res) {
    Button b = new Button(this); b.setAllCaps(false); b.setTextSize(13); b.setText(text);
    b.setMinHeight(0); b.setMinimumHeight(0); b.setMinWidth(0); b.setMinimumWidth(0);
    b.setPadding(dp(10), 0, dp(12), 0); b.setStateListAnimator(null); b.setOnTouchListener(haptic);
    b.setTextColor(new android.content.res.ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}}, new int[]{look.offText, look.fg}));
    b.setBackground(states(round(look.bg, 16, look.line), round(look.tint, 16, look.line)));
    b.setCompoundDrawablePadding(dp(6));
    chipIcon(b, res);
    return b;
  }
  void chipIcon(Button b, int res) {
    android.graphics.drawable.Drawable d = icon(res, look.night ? Look.GOLD : Look.PLUM);
    d.setBounds(0, 0, dp(16), dp(16)); b.setCompoundDrawables(d, null, null, null);
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
    if (bigScroll != null && newTurn) bigScroll.post(() -> bigScroll.scrollTo(0, 0));
    boolean crib = cribShown && pt.length() > 1 && pt.matches("(?s).*\\p{L}.*");
    cribVisible = crib; syncGuard();
    if (!crib) {
      TextView t = new TextView(this); t.setTextSize(szPt); t.setTypeface(null, Typeface.BOLD);
      t.setTextColor(look.fg); t.setLineSpacing(0, 1.05f); t.setText(pt);
      bigBox.addView(t); return;
    }
    for (String w : pt.trim().split("\\s+")) {
      LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
      TextView t = new TextView(this); t.setTextSize(szPt); t.setTypeface(null, Typeface.BOLD); t.setTextColor(look.fg); t.setText(w);
      TextView c = new TextView(this); c.setTextSize(Math.max(9, szPt * 0.45f)); c.setTextColor(look.soft); c.setText(Translit.say(w));
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
    if (segReadGuard == null || svc == null) return;
    segReadGuard.sel(Math.max(0, Math.min(2, svc.readGuard)));
  }

  /** Строка под названием в шапке: состояние, пометка о спрятанной транскрипции, тема разговора.
   *  Пока с разговором идёт облако или загрузка, строка — их ход (convBusy). */
  void setHint(String s) { hintBase = s == null ? "" : s; refreshHint(); }
  void refreshHint() {
    if (hint == null || screen != 0) return;
    String title = chatTitle();
    abTitle.setText(title);
    String h = hintBase;
    if (holdingRead) h = "читаете вслух · микрофон не слушает";
    else if (cribDefault() && !cribShown && bigText.length() > 1) h += (h.isEmpty() ? "" : " · ") + "транскрипция скрыта · касание вернёт";
    // Причина, по которой «получше» серая, — здесь же: в журнал её никто не пойдёт читать посреди разговора.
    if (svc != null && svc.eng != null && !svc.cloudBusy) {
      String m = svc.improveMode();
      if (!m.equals("cloud") && !m.equals("local")) h += (h.isEmpty() ? "" : " · ") + "получше недоступно: " + m;
    }
    String topic = svc != null && svc.chats != null ? svc.chats.topic : "";
    if (!topic.isEmpty() && !h.contains(topic) && !title.equals(topic)) h += (h.isEmpty() ? "" : " · ") + topic;
    if (convBusy != null) h = convBusy;
    hint.setText(h); hint.setVisibility(h.isEmpty() ? View.GONE : View.VISIBLE);
    abProg.setVisibility(convBusy != null ? View.VISIBLE : View.GONE);
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
    // Чем улучшит — значком: облако или уточнитель на телефоне; слово на чипе одно.
    chipIcon(bBetter, m.equals("cloud") ? app.falar.R.drawable.ic_cloud : m.equals("local") ? app.falar.R.drawable.ic_wand : app.falar.R.drawable.ic_spark);
    bBetter.setEnabled(!svc.cloudBusy && (m.equals("cloud") || m.equals("local")));
    refreshHint();
  }
  void refreshIntervals() {
    if (segRefine == null || svc == null) return;
    segRefine.sel(indexOf(new int[]{0, 1, 3, 10}, svc.refineEvery));
    segCloudEvery.sel(indexOf(new int[]{0, 5, 10, 20}, svc.cloudEvery));
    segPrefer.sel(svc.cloudQuality() ? 1 : 0);
    refreshCloudRow();
  }

  static int indexOf(int[] a, int v) { for (int k = 0; k < a.length; k++) if (a[k] == v) return k; return 0; }
  /** Строка «Облако» в настройках: сколько ключей и как часто пересмотр. */
  void refreshCloudRow() {
    if (rowCloud == null || svc == null || svc.cloud == null) return;
    int n = svc.cloud.keys().size();
    rowCloud.sub.setText((n == 0 ? "ключа нет" : n == 1 ? "1 ключ" : n + (n < 5 ? " ключа" : " ключей"))
        + " · пересмотр " + (svc.cloudEvery == 0 ? "по кнопке" : "каждые " + svc.cloudEvery + " реплик"));
    rowCloud.sub.setVisibility(View.VISIBLE);
  }
  /** «Память разговора» — по касанию строки с названием: что уточнитель знает о разговоре сверх
   *  последних реплик. Сырой перевод понятен редко, итог решает уточнитель, а он видит только
   *  свежий кусок разговора и эту память — поэтому её надо видеть и уметь поправить. Отсюда же
   *  глоссарий, «убрать подсказки» и имена-кандидаты в свои слова. */
  void memoDialog() {
    if (svc == null || svc.chats == null) return;
    final Chats c = svc.chats;
    StringBuilder b = new StringBuilder();
    String who = svc.whoLine(), kw = c.topic.isEmpty() ? svc.topicLine() : "";
    b.append(who.isEmpty() ? "Кто говорит — пока не ясно: нет реплик, где человек говорит о себе («obrigada», «я поняла»)."
                           : who + " Посчитано по тому, как люди говорят о себе: «obrigada», «estou cansada», «я понял».").append("\n\n");
    if (c.memo.isEmpty()) b.append("Ключевые детали — пока нет. Их пишет облачный пересмотр («получше» или по интервалу) и дополняет каждый раз; можно вписать самому.");
    else b.append("Ключевые детали ").append(Chats.BY_USER.equals(c.memoBy) ? "(ваши — автоматика их не меняет)" : Chats.BY_CLOUD.equals(c.memoBy) ? "(от облака)" : "(от модели)")
          .append(":\n").append(c.memo);
    b.append("\n\nТема: ").append(!c.topic.isEmpty() ? c.topic : kw.isEmpty() ? "нет" : "нет, вместо неё частые слова: " + kw);
    b.append("\n\nУточнитель получает эту память и последние реплики, до ").append(Brief.FRESH_CHARS)
     .append(" знаков. Всё, что раньше, он знает только отсюда.\n\n");
    final java.util.List<String[]> ts = c.terms();
    if (ts.isEmpty()) b.append("Глоссарий разговора пуст.");
    else {
      b.append("Глоссарий разговора — подсказка уточнителю и облаку, OPUS-MT его не видит:\n");
      for (String[] t : ts) b.append("• ").append(t[0]).append(" = ").append(t[1]).append("  ·  ").append("cloud".equals(t[2]) ? "от облака" : "от модели").append('\n');
    }
    final java.util.List<String[]> names = new java.util.ArrayList<>(svc.pendingNames);
    if (!names.isEmpty()) b.append("\nИмена от модели, которые можно добавить в свои слова: ").append(names.size());
    TextView tv = new TextView(this); tv.setText(b.toString()); tv.setTextSize(15); tv.setPadding(48, 24, 48, 8); tv.setTextIsSelectable(true);
    ScrollView sv = new ScrollView(this); sv.addView(tv);
    final java.util.List<String> more = new java.util.ArrayList<>();
    if (Chats.BY_USER.equals(c.memoBy)) more.add("вернуть память облаку");
    if (!ts.isEmpty()) more.add("убрать подсказки (" + ts.size() + ")");
    if (!names.isEmpty()) more.add("имена → свои слова (" + names.size() + ")");
    android.app.AlertDialog.Builder d = new android.app.AlertDialog.Builder(this)
        .setTitle("Память разговора").setView(sv)
        .setPositiveButton("изменить память", (dd, w) -> editMemo())
        .setNegativeButton("закрыть", null);
    if (!more.isEmpty()) d.setNeutralButton("ещё…", (dd, w) -> new android.app.AlertDialog.Builder(this)
        .setItems(more.toArray(new String[0]), (d2, k) -> {
          String it = more.get(k);
          if (it.startsWith("вернуть")) { svc.setMemoByUser(""); refreshHint(); }
          else if (it.startsWith("убрать")) { int n = c.clearTerms(); onLog("🗑 подсказки разговора убраны: " + n); refreshHint(); }
          else namesDialog(names);
        }).setNegativeButton("отмена", null).show());
    d.show();
  }
  /** Вписать или поправить ключевые детали. Вписанное человеком автоматика больше не меняет;
   *  пустое поле возвращает память облаку. */
  void editMemo() {
    if (svc == null || svc.chats == null) return;
    LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(40, 8, 40, 0);
    final EditText in = new EditText(this);
    in.setText(svc.chats.memo); in.setMinLines(3); in.setTextSize(16);
    in.setHint("Например: хозяйка квартиры Мария (женщина) показывает мне квартиру; договорились о 2500 реалах в месяц");
    box.addView(in);
    TextView note = new TextView(this); note.setTextSize(12); note.setTextColor(look.soft);
    note.setText("Кто с кем говорит, где, о чём договорились, имена. Уточнитель читает это перед каждой репликой. "
               + "Вписанное вами облако больше не меняет; пустое поле вернёт память облаку.");
    box.addView(note);
    new android.app.AlertDialog.Builder(this)
        .setTitle("Память разговора").setView(box)
        .setPositiveButton("сохранить", (d, w) -> { svc.setMemoByUser(in.getText().toString()); refreshHint(); })
        .setNegativeButton("отмена", null).show();
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
    if (t == null) { showBig("—", "pt2ru", false); smallRu.setText(""); setWho(null); return; }
    boolean srcPt = t[0].startsWith("pt"), photo = Chats.PHOTO.equals(t[8]);
    showBig((photo ? "📷 " : "") + (srcPt ? t[1] : t[2]), t[0], !t[4].isEmpty()); smallRu.setText(srcPt ? t[2] : t[1]);
    setWho(t[0]);
  }

  final java.util.List<String[]> histRows = new java.util.ArrayList<>();

  /** Реплики разговора, свежие сверху. Верхняя строка экрана показывает последнюю крупно,
   *  поэтому в списке её нет — иначе одно и то же читается дважды.
   *
   *  Реплики — карточками: собеседник слева, вы справа, как в переписке. Раньше строки шли одна
   *  под другой одинаково, и кто что сказал, было не видно. Нажимается вся карточка — меню
   *  реплики; значок «•••» больше не нужен: карточка сама видна как нажимаемая. */
  void refreshHist() {
    if (svc == null || svc.chats == null || histList == null) return;
    java.util.List<String[]> all = svc.chats.all();
    histRows.clear();
    for (int k = all.size() - 2; k >= 0; k--) histRows.add(all.get(k));
    final int maxW = Math.round(getResources().getDisplayMetrics().widthPixels * 0.80f);
    histList.setAdapter(new ArrayAdapter<String[]>(this, 0, histRows) {
      @Override public View getView(int pos, View cv, ViewGroup parent) {
        String[] t = getItem(pos);
        boolean srcPt = t[0].startsWith("pt"), photo = Chats.PHOTO.equals(t[8]), me = !srcPt && !photo;
        LinearLayout bub = new LinearLayout(MainActivity.this);
        bub.setOrientation(LinearLayout.VERTICAL); bub.setPadding(dp(12), dp(8), dp(12), dp(9));
        android.graphics.drawable.GradientDrawable bg = round(me ? look.tint : look.card, 16, me ? 0 : look.line);
        float r = dp(16), s = dp(6);
        bg.setCornerRadii(me ? new float[]{r, r, s, s, r, r, r, r} : new float[]{s, s, r, r, r, r, r, r});
        bub.setBackground(bg);
        TextView pt = new TextView(MainActivity.this);
        pt.setText((photo ? "📷 " : "") + (srcPt ? t[1] : t[2])); pt.setTextSize(Math.max(12, szRu)); pt.setTextColor(look.fg);
        pt.setMaxWidth(maxW); if (photo) pt.setMaxLines(3);
        TextView ru = new TextView(MainActivity.this);
        ru.setText((srcPt ? t[2] : t[1]) + ("1".equals(t[4]) ? ("user".equals(t[5]) ? "  ✎" : "  ✓") : ""));
        ru.setTextSize(Math.max(10, szRu - 3)); ru.setTextColor(look.dim); ru.setMaxWidth(maxW);
        bub.addView(pt); bub.addView(ru);
        LinearLayout row = new LinearLayout(MainActivity.this);
        row.setOrientation(LinearLayout.HORIZONTAL); row.setPadding(0, dp(4), 0, dp(4));
        row.setGravity(me ? Gravity.END : Gravity.START);
        row.addView(bub, new LinearLayout.LayoutParams(-2, -2));
        return row;
      }
    });
    histList.setOnItemClickListener((p, vv, pos, id) -> {
      if (pos >= histRows.size()) return;
      String[] t = histRows.get(pos);
      if (Chats.PHOTO.equals(t[8])) openPhoto(Integer.parseInt(t[3]));      // снимок — перевод поверх фото, меню — долгим нажатием
      else editTurn(Integer.parseInt(t[3]), t[1]);
    });
    histList.setOnItemLongClickListener((p, vv, pos, id) -> {   // прежний жест оставлен: к нему привыкли
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
    final boolean mine = t[0].startsWith("ru"), photo = Chats.PHOTO.equals(t[8]);
    final java.util.List<String> items = new java.util.ArrayList<>();
    if (photo) items.add("Показать снимок");
    if (mine) { items.add("Исправить текст"); items.add("Исправить перевод"); }
    if (!photo && svc.mod(Modules.TTS)) items.add("Произнести ещё раз");   // снимок не озвучивается; без модуля «Озвучка» — нечем
    items.add("Сообщить о переводе"); items.add("Удалить реплику"); items.add("Перенести в другой разговор");
    String title = t[1].length() > 40 ? t[1].substring(0, 40) + "…" : t[1];
    new android.app.AlertDialog.Builder(this)
        .setTitle(title)
        .setItems(items.toArray(new String[0]), (d, w) -> {
          String it = items.get(w);
          if (it.equals("Показать снимок")) openPhoto(idx);
          else if (it.equals("Исправить текст")) editSource(idx, t[1]);
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
    TextView note = new TextView(this); note.setTextSize(12); note.setTextColor(look.soft);
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
    TextView s = new TextView(this); s.setText(src); s.setTextSize(13); s.setTextColor(look.soft); box.addView(s);
    final EditText in = new EditText(this);
    in.setText(dst); in.setSelection(dst.length()); in.setMinLines(2); in.setTextSize(16); box.addView(in);
    final CheckBox pin = new CheckBox(this); pin.setText("и запомнить как пин"); pin.setChecked(true); box.addView(pin);
    TextView note = new TextView(this); note.setTextSize(12); note.setTextColor(look.soft);
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
    up.setColor(look.btn); up.setCornerRadius(22); up.setStroke(2, look.btnLine);
    android.graphics.drawable.GradientDrawable down = new android.graphics.drawable.GradientDrawable();
    down.setColor(Look.PLUM); down.setCornerRadius(22); down.setStroke(2, Look.DEEP);   // выбор — сливой, как на кнопке удержания, а не синим
    android.graphics.drawable.GradientDrawable off = new android.graphics.drawable.GradientDrawable();
    off.setColor(look.offBg); off.setCornerRadius(22); off.setStroke(2, look.offLine);
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
        new int[]{look.offText, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, look.fg}));
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

  /** 0 разговор · 1 слова · 2 настройки · 3 голоса · 4 первый запуск · 5 модули и файлы · 6 облако · 7 журнал. «Слова» и «Настройки»
   *  открываются из панели ☰, голоса — из настроек: их настраивают один раз. У первого запуска
   *  шапки нет — у него свой заголовок. */
  void show(int tab) {
    screen = tab;
    talkView.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
    learnView.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
    sysView.setVisibility(tab == 2 ? View.VISIBLE : View.GONE);
    if (tab == 2) micLabel();
    voiceView.setVisibility(tab == 3 ? View.VISIBLE : View.GONE);
    if (modsView != null) { modsView.setVisibility(tab == 5 ? View.VISIBLE : View.GONE); cloudView.setVisibility(tab == 6 ? View.VISIBLE : View.GONE); journalView.setVisibility(tab == 7 ? View.VISIBLE : View.GONE); }
    if (tab == 6) refreshKey(null);
    if (tab == 7 && logScroll != null) logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    if (setupView != null) setupView.setVisibility(tab == 4 ? View.VISIBLE : View.GONE);
    if (appBar != null) appBar.setVisibility(tab == 4 ? View.GONE : View.VISIBLE);
    refreshAppBar();
  }

  /** Панель ☰: «＋ Новый разговор», разговоры (свежие сверху, галочка — перевод улучшен задним
   *  числом), внизу «Слова» и «Настройки». Лежит поверх экрана; касания под неё не проходят. */
  View buildSide() {
    LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL);
    android.graphics.drawable.GradientDrawable bg = round(look.bg, 0, 0);
    float r = dp(24); bg.setCornerRadii(new float[]{0, 0, r, r, r, r, 0, 0});
    v.setBackground(bg); v.setElevation(dp(12)); v.setClipToOutline(true); v.setClickable(true);
    LinearLayout head = new LinearLayout(this); head.setOrientation(LinearLayout.HORIZONTAL); head.setGravity(Gravity.CENTER_VERTICAL);
    head.setPadding(dp(18), dp(18), dp(18), dp(16));
    head.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
        look.night ? new int[]{0xFF4A1530, 0xFF1D0816} : new int[]{Look.PLUM, Look.DEEP}));
    ImageView logo = new ImageView(this); logo.setImageResource(app.falar.R.mipmap.ic_launcher);
    head.addView(logo, new LinearLayout.LayoutParams(dp(46), dp(46)));
    LinearLayout ht = new LinearLayout(this); ht.setOrientation(LinearLayout.VERTICAL); ht.setPadding(dp(12), 0, 0, 0);
    TextView hn = new TextView(this); hn.setText("Falar"); hn.setTextSize(20); hn.setTypeface(null, Typeface.BOLD); hn.setTextColor(0xFFFFFFFF);
    String ver; try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { ver = ""; }
    TextView hv = new TextView(this); hv.setText(ver + " · pt-BR ⇄ ru без сети"); hv.setTextSize(12.5f); hv.setTextColor(0xC0FFFFFF);
    ht.addView(hn); ht.addView(hv); head.addView(ht);
    v.addView(head);
    Button bNew = new Button(this); bNew.setAllCaps(false); bNew.setText("＋  Новый разговор"); bNew.setTextSize(15); bNew.setTypeface(null, Typeface.BOLD);
    bNew.setTextColor(0xFFFFFFFF); bNew.setStateListAnimator(null); bNew.setOnTouchListener(haptic);
    bNew.setBackground(states(round(Look.PLUM, 22, 0), round(Look.DEEP, 22, 0)));
    LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, dp(44)); np.setMargins(dp(14), dp(14), dp(14), dp(6));
    v.addView(bNew, np);
    bNew.setOnClickListener(x -> { newChat(); drawer(false); show(0); });
    TextView t = new TextView(this); t.setText("Разговоры"); t.setTextSize(12); t.setTextColor(look.soft); t.setPadding(dp(18), dp(10), dp(18), 0); v.addView(t);
    hintSide = new TextView(this); hintSide.setTextSize(11); hintSide.setTextColor(look.soft); hintSide.setPadding(dp(18), 0, dp(18), dp(4)); v.addView(hintSide);
    chatList = new ListView(this); chatList.setDivider(null);
    v.addView(chatList, new LinearLayout.LayoutParams(-1, 0, 1f));
    View div = new View(this); div.setBackgroundColor(look.line); v.addView(div, new LinearLayout.LayoutParams(-1, Math.max(1, dp(1))));
    TextView nWords = navRow(app.falar.R.drawable.ic_book, "Слова"), nSys = navRow(app.falar.R.drawable.ic_tune, "Настройки");
    v.addView(nWords); v.addView(nSys);
    nWords.setOnClickListener(x -> { drawer(false); show(1); refreshWords(); });
    nSys.setOnClickListener(x -> { drawer(false); show(2); });
    return v;
  }
  TextView navRow(int res, String text) {
    TextView t = new TextView(this); t.setText(text); t.setTextSize(15); t.setTextColor(look.fg); t.setGravity(Gravity.CENTER_VERTICAL);
    t.setPadding(dp(18), dp(12), dp(18), dp(12)); t.setCompoundDrawablePadding(dp(14));
    android.graphics.drawable.Drawable d = icon(res, look.night ? Look.GOLD : Look.PLUM); d.setBounds(0, 0, dp(24), dp(24));
    t.setCompoundDrawables(d, null, null, null);
    t.setBackground(states(round(0, 0, 0), round(look.tint, 0, 0))); t.setClickable(true); t.setOnTouchListener(haptic);
    return t;
  }

  final java.util.List<String> chatIds = new java.util.ArrayList<>();
  final java.util.List<String> chatTitles = new java.util.ArrayList<>();
  String rowsTitle(int pos) { return pos < chatTitles.size() ? chatTitles.get(pos) : ""; }
  void refreshChats() {
    if (svc == null || svc.chats == null) return;
    java.util.List<String[]> l = svc.chats.list();
    final java.util.List<String[]> rows = new java.util.ArrayList<>();   // название, дата · реплики, улучшен, текущий
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
      rows.add(new String[]{c[2].isEmpty() ? (empty ? "Новый разговор" : "Без имени") : c[2],
          when + " · " + (empty ? "пока пусто" : c[1] + " реплик"), "1".equals(c[3]) ? "1" : "", here ? "1" : ""});
    }
    if (rows.isEmpty()) rows.add(new String[]{"Пока пусто", "", "", ""});
    hintSide.setText(chatIds.isEmpty() ? "" : "долгое нажатие — переименовать или удалить");
    chatList.setAdapter(new ArrayAdapter<String[]>(this, 0, rows) {
      @Override public View getView(int pos, View cv, ViewGroup parent) {
        String[] r = getItem(pos);
        LinearLayout row = new LinearLayout(MainActivity.this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(9), dp(16), dp(9));
        if (!r[3].isEmpty()) row.setBackgroundColor(look.tint);                // текущий — подложкой
        LinearLayout col = new LinearLayout(MainActivity.this); col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(MainActivity.this); t.setText(r[0]); t.setTextSize(15); t.setTextColor(look.fg);
        t.setSingleLine(true); t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (!r[3].isEmpty()) t.setTypeface(null, Typeface.BOLD);
        TextView m = new TextView(MainActivity.this); m.setText(r[1]); m.setTextSize(12); m.setTextColor(look.soft);
        col.addView(t); if (!r[1].isEmpty()) col.addView(m);
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        if (!r[2].isEmpty()) { ImageView ok = new ImageView(MainActivity.this); ok.setImageDrawable(icon(app.falar.R.drawable.ic_check, look.night ? Look.MINT : 0xFF1E9E77));
          row.addView(ok, new LinearLayout.LayoutParams(dp(18), dp(18))); }
        return row;
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
    // Название разговора — в шапке; здесь только его состояние.
    setHint("продолжаем" + (o.optBoolean("refined", false) ? " · перевод улучшен" : ""));
    // Наверху всегда последняя реплика: экран разговора для того и нужен, чтобы собеседник
    // её читал. Раньше после открытия там стояло «—», и верх экрана пустовал.
    org.json.JSONArray t = o.optJSONArray("turns");
    org.json.JSONObject last = t == null || t.length() == 0 ? null : t.optJSONObject(t.length() - 1);
    if (last == null) { showBig("—", "pt2ru", false); smallRu.setText(""); setWho(null); }
    else {
      boolean srcPt = last.optString("dir", "").startsWith("pt");
      String fixed = last.optString("fixed", "");
      String dst = fixed.isEmpty() ? last.optString("dst", "") : fixed;
      showBig(srcPt ? last.optString("src", "") : dst, last.optString("dir", "pt2ru"), !fixed.isEmpty());
      smallRu.setText(srcPt ? dst : last.optString("src", ""));
      setWho(last.optString("dir", "pt2ru"));
    }
    refreshHist(); refreshHint();
  }

  /** Открыть разговор по нажатию в списке: то же, плюс закрыть панель и уйти на экран разговора. */
  void showChatAndGo(long id, org.json.JSONObject o) {
    showChat(id, o);
    drawer(false);
    show(0);
  }

  /** Экран разговора. Сверху — текущая реплика: кто сказал, португальский крупно, русский мелко;
   *  под ней её действия («Получше», «Запомнить», «⋯») или, пока она распознаётся, переводится и
   *  уточняется, — ход этого на том же месте (решение владельца 01.10: ход — в реплике). Ниже —
   *  прежние реплики карточками. Внизу док: «Снимок, текст», кнопка удержания, «Слушать» PT | RU.
   *  Режимов (ввод · удержание · слушать) и сворачивания больше нет: док занимает 76 dp, а три
   *  прежних ряда внизу — 170 dp (стенд, 30.09), и всё нужное в нём видно сразу.
   *
   *  Облако вокруг кнопки удержания расходится до трёх её радиусов — за край списка: пусть уходит
   *  под боковые кнопки дока (они рисуются поверх), а не обрезается ровной линией. Цена: экран не
   *  режет детей по краю, и всё, что прокручивается, сидит в своей режущей рамке — у прокрутки без
   *  отступов своей обрезки нет. Таких двое: крупный текст (bigClip) и список реплик (listClip).
   *  В 0.24.0 крупный текст без рамки рисовался поверх названия и списка. */
  View buildTalk() {
    FrameLayout f = new FrameLayout(this); f.setClipChildren(false);
    LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setClipChildren(false);
    f.addView(v, new FrameLayout.LayoutParams(-1, -1));

    LinearLayout rep = new LinearLayout(this); rep.setOrientation(LinearLayout.VERTICAL); rep.setPadding(dp(16), dp(12), dp(16), 0);
    whoLbl = new TextView(this); whoLbl.setTextSize(12); whoLbl.setTextColor(look.soft); whoLbl.setCompoundDrawablePadding(dp(6));
    whoLbl.setGravity(Gravity.CENTER_VERTICAL); whoLbl.setPadding(0, 0, 0, dp(4));
    rep.addView(whoLbl);
    // Крупный текст — контейнер с переносом по словам: под каждым словом транскрипция для чтения
    // вслух. Касание прячет и возвращает её.
    bigBox = new FlowLayout(this); bigBox.setClickable(true); bigBox.setLongClickable(true);
    bigBox.setOnClickListener(x -> { if (bigText.length() < 2) return; cribManual = true; cribShown = !cribShown; showBig(bigText, bigDir, bigRefined); refreshHint(); });
    // Удержание крупного текста — «читаю вслух»: транскрипция показывается на время удержания,
    // и микрофон в это время не слушает. Иначе приложение слышит, как владелец произносит
    // португальскую фразу с экрана, считает его собеседником и переводит ему её же обратно.
    bigBox.setOnLongClickListener(x -> { if (bigText.length() < 2) return false; startReading(); return true; });
    bigBox.setOnTouchListener((bv, e) -> {
      int a = e.getAction();
      if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) stopReading();
      return false;                       // касание и удержание обрабатываются своими слушателями
    });
    // Крупный текст прокручивается и не растёт выше половины экрана. Без этого длинная фраза
    // (а с транскрипцией каждое слово занимает две строки) выдавливала за нижний край список
    // реплик и кнопки внизу. В прокрутку уходит вся текущая реплика: и крупный текст, и русская
    // строка — перевод длинного монолога сам по себе занимает шесть строк.
    bigScroll = new ScrollView(this) {
      @Override protected void onMeasure(int wSpec, int hSpec) {
        int max = Math.round(getResources().getDisplayMetrics().heightPixels * 0.5f);
        super.onMeasure(wSpec, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
      }
    };
    bigScroll.setFillViewport(true);
    LinearLayout turnCol = new LinearLayout(this); turnCol.setOrientation(LinearLayout.VERTICAL);
    turnCol.addView(bigBox);
    smallRu = new TextView(this); smallRu.setTextSize(17); smallRu.setTextColor(look.dim); smallRu.setPadding(0, dp(8), 0, 0);
    turnCol.addView(smallRu);
    bigScroll.addView(turnCol);
    FrameLayout bigClip = new FrameLayout(this);                // режет прокрутку по краю — см. выше
    bigClip.addView(bigScroll, new FrameLayout.LayoutParams(-1, -2));
    rep.addView(bigClip);
    smallRu.setOnLongClickListener(x -> { if (svc == null || svc.chats == null || svc.chats.size() == 0) return false; turnMenu(svc.chats.size() - 1); return true; });

    // Действия с репликой и ход её перевода — на одном месте одной высоты: пока идёт ход, чипов нет,
    // и крупный текст не подпрыгивает, когда он начинается и кончается.
    FrameLayout act = new FrameLayout(this);
    LinearLayout chips = new LinearLayout(this); chips.setOrientation(LinearLayout.HORIZONTAL); chips.setGravity(Gravity.CENTER_VERTICAL);
    bBetter = chip("Получше", app.falar.R.drawable.ic_spark); bBetter.setEnabled(false);
    bPin = chip("Запомнить", app.falar.R.drawable.ic_pin); bPin.setEnabled(false);
    Button more = chip("", app.falar.R.drawable.ic_more); more.setPadding(dp(8), 0, 0, 0); more.setContentDescription("Меню реплики");
    LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, dp(32)); cp.setMarginEnd(dp(8));
    chips.addView(bBetter, cp);
    chips.addView(bPin, new LinearLayout.LayoutParams(cp));
    chips.addView(more, new LinearLayout.LayoutParams(dp(32), dp(32)));
    bTurn = more;
    // Меню последней реплики — видимой кнопкой «⋯»: долгое нажатие само по себе не видно, а жест
    // удержания крупного текста занят чтением вслух.
    bTurn.setOnClickListener(x -> {
      if (svc == null || svc.chats == null || svc.chats.size() == 0) return;
      int last = svc.chats.size() - 1; String[] t = svc.chats.turn(last);
      if (t != null && Chats.PHOTO.equals(t[8])) openPhoto(last); else turnMenu(last);   // снимок — сразу перевод поверх фото
    });
    chipsRow = chips;
    // Ход реплики: отрезки «распознаю · перевожу · уточняю» и подпись этапа. Стенд находит подпись
    // по id busy (test_busy_device.sh).
    LinearLayout busy = new LinearLayout(this); busy.setOrientation(LinearLayout.VERTICAL); busy.setId(app.falar.R.id.busy);
    busy.setGravity(Gravity.CENTER_VERTICAL);
    stageBar = new ProgressLine(this).colors(look.night ? Look.GOLD : Look.PLUM, Look.MINT, look.night ? Look.GOLD : Look.PLUM, look.line);
    busy.addView(stageBar, new LinearLayout.LayoutParams(-1, dp(8)));
    busyLbl = new TextView(this); busyLbl.setTextSize(12.5f); busyLbl.setTextColor(look.accent); busyLbl.setTypeface(null, Typeface.BOLD);
    busyLbl.setSingleLine(true); busyLbl.setEllipsize(android.text.TextUtils.TruncateAt.END); busyLbl.setPadding(0, dp(6), 0, 0);
    busy.addView(busyLbl);
    busy.setVisibility(View.INVISIBLE); busyRow = busy;
    act.addView(chips, new FrameLayout.LayoutParams(-1, -1));
    act.addView(busy, new FrameLayout.LayoutParams(-1, -1));
    LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, dp(40)); ap.topMargin = dp(10);
    rep.addView(act, ap);
    v.addView(rep);

    // «ранее»: черта, слово, черта.
    LinearLayout sep = new LinearLayout(this); sep.setOrientation(LinearLayout.HORIZONTAL); sep.setGravity(Gravity.CENTER_VERTICAL);
    sep.setPadding(dp(16), dp(6), dp(16), dp(2));
    for (int i = 0; i < 3; i++) {
      if (i == 1) { TextView s1 = new TextView(this); s1.setText("ранее"); s1.setTextSize(12); s1.setTextColor(look.soft); s1.setPadding(dp(10), 0, dp(10), 0); sep.addView(s1); }
      else { View l1 = new View(this); l1.setBackgroundColor(look.line); sep.addView(l1, new LinearLayout.LayoutParams(0, Math.max(1, dp(1)), 1f)); }
    }
    v.addView(sep);

    // Прежние реплики — список, а не сплошной текст: каждую надо уметь удалить или перенести
    // в другой разговор. Случайная фраза из комнаты иначе остаётся в контексте уточнителя
    // и в разборе слов для заучивания. Низ списка не прячется под кнопкой: она заходит на него.
    histList = new ListView(this);
    histList.setDivider(null); histList.setClipToPadding(false); histList.setPadding(dp(12), dp(2), dp(12), dp(64));
    FrameLayout listClip = new FrameLayout(this);
    listClip.addView(histList, new FrameLayout.LayoutParams(-1, -1));
    v.addView(listClip, new LinearLayout.LayoutParams(-1, 0, 1f));

    // Док: фон полупрозрачный — облако кнопки видно и под ним.
    View dockBg = new View(this);
    android.graphics.drawable.GradientDrawable db = round((look.bg & 0x00FFFFFF) | 0xDB000000, 0, 0);
    android.graphics.drawable.GradientDrawable dl = round(look.line, 0, 0);
    android.graphics.drawable.LayerDrawable dock = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{dl, db});
    dock.setLayerInset(1, 0, Math.max(1, dp(1)), 0, 0);
    dockBg.setBackground(dock);
    v.addView(dockBg, new LinearLayout.LayoutParams(-1, dp(76)));

    // Кнопка удержания — всегда в центре дока, под большим пальцем: центр на 8 dp ниже края дока
    // (док 76 dp, вид 128 dp, снизу 4 dp). Касание мимо круга кнопка отдаёт дальше.
    bMic = new MicButton(this); bMic.setEnabled(false);
    bMic.setContentDescription("Удерживайте и говорите — по-португальски или по-русски");
    FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(dp(128), dp(128), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
    mp.bottomMargin = dp(4);
    f.addView(bMic, mp);

    // Боковые кнопки дока — поверх облака кнопки.
    FrameLayout side = new FrameLayout(this);
    LinearLayout in = new LinearLayout(this); in.setOrientation(LinearLayout.VERTICAL); in.setGravity(Gravity.CENTER_HORIZONTAL);
    in.setPadding(0, dp(6), 0, 0);
    inputIcon = new ImageView(this); inputIcon.setScaleType(ImageView.ScaleType.CENTER);
    inputIcon.setImageDrawable(icon(app.falar.R.drawable.ic_camera, look.night ? Look.GOLD : Look.PLUM));
    inputIcon.setBackground(round(look.card, 22, look.line));
    in.addView(inputIcon, new LinearLayout.LayoutParams(dp(44), dp(44)));
    inputLbl = new TextView(this); inputLbl.setText("Снимок, текст"); inputLbl.setTextSize(11); inputLbl.setTextColor(look.dim);
    inputLbl.setSingleLine(true); inputLbl.setPadding(0, dp(3), 0, 0);
    in.addView(inputLbl);
    in.setClickable(true); in.setLongClickable(true); in.setOnTouchListener(haptic); in.setEnabled(false);
    in.setContentDescription("Снимок или набрать фразу");
    bInput = in;
    side.addView(in, new FrameLayout.LayoutParams(dp(92), -1, Gravity.START));
    LinearLayout ls = new LinearLayout(this); ls.setOrientation(LinearLayout.VERTICAL); ls.setGravity(Gravity.CENTER_HORIZONTAL);
    bListen = new ListenButton(this); bListen.setEnabled(false);
    bListen.setContentDescription(LISTEN_HINT);            // её читают TalkBack и стенд (test_hearing_device.sh)
    ls.addView(bListen, new LinearLayout.LayoutParams(dp(108), dp(54)));
    TextView ll = new TextView(this); ll.setText("Слушать"); ll.setTextSize(11); ll.setTextColor(look.dim);
    ls.addView(ll);
    side.addView(ls, new FrameLayout.LayoutParams(dp(108), -1, Gravity.END));
    side.setPadding(dp(14), 0, dp(12), 0);
    f.addView(side, new FrameLayout.LayoutParams(-1, dp(76), Gravity.BOTTOM));

    // Состояние «Слушать PT» и «Слушать RU» держат прежние переключатели: на экране их больше нет,
    // их половинки — в bListen, а обработчик и синхронизация с сервисом остались как были.
    tListenPt = new ToggleButton(this); tListenRu = new ToggleButton(this);
    return f;
  }

  /** Слушание отмечается прямо на кнопке режима: его видно и когда открыт другой режим. */
  /** Подпись чувствительности: в авто — сколько она сейчас, вручную — положение ползунка. */
  void micLabel() {
    if (micLbl == null) return;
    boolean auto = cMicAuto.isChecked();
    micLbl.setText(auto ? "под голос" + (svc == null ? "" : String.format(java.util.Locale.ROOT, " · сейчас %+.0f дБ", svc.autoDb)) : "выключено — действует ползунок");
    if (micVal != null) micVal.setText("+" + sMic.getProgress());
  }

  /** Что слушаем — на самой кнопке «Слушать»: мятные половинки PT и RU. */
  void markListen() {
    if (bListen != null) bListen.setState(svc != null && svc.listenPt, svc != null && svc.listenRu);
    meter();
  }

  /** Опрос уровня входа: 5 раз в секунду, пока слушаем и виден разговор, 10 — пока держат кнопку
   *  (кольцо следует за голосом). Сам останавливается, когда ни того, ни другого, или экран ушёл. */
  final Runnable meterTick = new Runnable() { public void run() {
    boolean listening = svc != null && (svc.listenPt || svc.listenRu), holding = svc != null && svc.recording;
    if (!resumed || (!listening && !holding)) { meterOn = false; return; }
    if (holding) bMic.setLevel(svc.levelDb, svc.liveQ);
    else if (screen == 0) bListen.setLevel(svc.levelDb, svc.liveQ, svc.liveSpeech);
    ui.postDelayed(this, holding ? 100 : 200);
  }};
  void meter() { if (!meterOn && resumed) { meterOn = true; ui.post(meterTick); } }

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

  static final String K_PHOTO_URI = "photo_uri", K_PENDING = "photo_pending", K_CLOUD_PHOTO = "cloud_photo", K_PENDING_CLOUD = "photo_pending_cloud";

  /** Снимок с камеры камера пишет в общую галерею: без FileProvider (его нет без AndroidX) ей больше
   *  некуда. Копия для разговора — у нас (savePhoto), и в галерее снимок оставаться не должен: он
   *  там виден всем приложениям и не удаляется вместе с репликой, а договорились хранить снимки в
   *  приложении. Удаляется только своя запись (photoUri) — снимок, выбранный из галереи, не трогаем.
   *  Если снимок не скопировался, запись остаётся: его можно выбрать из галереи ещё раз. */
  void dropCapture(android.net.Uri u) {
    if (u == null || photoUri == null || !u.equals(photoUri)) return;
    try { getContentResolver().delete(u, null, null); } catch (Throwable t) { onLog("📷 снимок камеры в галерее не удалился: " + t); }
    photoUri = null;
  }
  /** Снимок пришёл раньше, чем поднялся сервис с движками: прочитаем в onReady. */
  android.net.Uri pendingPhoto; boolean pendingCloud;

  @Override protected void onSaveInstanceState(Bundle out) {
    super.onSaveInstanceState(out);
    if (photoUri != null) out.putString(K_PHOTO_URI, photoUri.toString());
    if (pendingPhoto != null) out.putString(K_PENDING, pendingPhoto.toString());
    out.putBoolean(K_CLOUD_PHOTO, cloudPhoto); out.putBoolean(K_PENDING_CLOUD, pendingCloud);
  }

  @Override protected void onActivityResult(int req, int res, Intent data) {
    super.onActivityResult(req, res, data);
    if (req != REQ_PHOTO) return;
    if (res != RESULT_OK) {                          // пустая запись, заведённая под снимок, в галерее не нужна
      if (res != RESULT_CANCELED) onLog("📷 камера вернула отказ (" + res + ")");
      dropCapture(photoUri); return;
    }
    final android.net.Uri u = data != null && data.getData() != null ? data.getData() : photoUri;
    if (u == null) { onLog("📷 снимок не вернулся: камера не отдала файл"); return; }
    if (svc == null || svc.eng == null) {
      pendingPhoto = u; pendingCloud = cloudPhoto;
      setHint("📷 снимок получен — прочитаю, как только приложение загрузится");
      onLog("📷 снимок получен до загрузки приложения — ждёт движков");
      return;
    }
    handlePhoto(u, cloudPhoto);
  }

  /** Прочитать снимок u: офлайн — без спроса, облаком — с согласием на каждый снимок. */
  void handlePhoto(final android.net.Uri u, final boolean cloudPhoto) {
    if (svc == null) return;
    final boolean offline = svc.ocr != null && svc.ocr.ready() && svc.mod(Modules.OCR) && !cloudPhoto;
    if (!offline && !svc.cloudReady()) {             // читать нечем: модели снимков ещё качаются, а облака нет
      onLog(svc.mod(Modules.OCR) ? "📷 чтение снимков ещё докачивается — снимок прочитается, когда модели придут; попробуйте позже"
                                 : "📷 снимок читать нечем: включите «Чтение снимков» или «Облако» в «Системе» → «Модули»");
      return;
    }
    new Thread(() -> {
      if (offline) {                         // офлайн — без спроса: наружу ничего не уходит
        final java.io.File f = svc.chats == null ? null : savePhoto(u, svc.chats.photos());
        if (f != null) dropCapture(u);
        runOnUiThread(() -> { if (f == null) onLog("📷 снимок не прочитался"); else svc.photoRead(f); });
        return;
      }
      final byte[] jpeg = jpegOf(u);
      if (jpeg != null) dropCapture(u);
      runOnUiThread(() -> {
        if (jpeg == null) { onLog("📷 снимок не прочитался"); return; }
        // Облако — третья сторона. Спрашиваем каждый раз: на снимке может быть чужая переписка,
        // а правило проекта — разговоры остаются здесь.
        boolean noModels = svc.ocr == null || !svc.ocr.ready();
        new android.app.AlertDialog.Builder(this)
            .setTitle("Отправить снимок в облако?")
            .setMessage((noModels ? "Модели офлайн-чтения ещё не скачаны. " : "")
                      + "Снимок (" + (jpeg.length / 1024) + " КБ) уйдёт бесплатной модели OpenRouter — это третья сторона. "
                      + "Перевод потом считается здесь, на устройстве.")
            .setPositiveButton("отправить", (d, w) -> svc.photoText(jpeg, true))
            .setNegativeButton("отмена", null).show();
      });
    }, "photo").start();
  }

  /** Длинная сторона снимка для офлайн-чтения. Замер на наборе вывесок (bench/ocr): при 1000 px
   *  слов прочитано на 3 пункта меньше, чем при 1280, при 800 — на 10; детектор всё равно
   *  смотрит на 960, а строки вырезаются из полного снимка. */
  static final int PHOTO_MAX = 2048;

  /** Снимок для офлайн-чтения — в каталог снимков разговоров: до PHOTO_MAX по длинной стороне,
   *  повёрнутый по EXIF (камера пишет портрет как пейзаж с пометкой, а BitmapFactory её не
   *  применяет — распознаватель читает строки только горизонтально), JPEG 90. */
  java.io.File savePhoto(android.net.Uri u, java.io.File dir) {
    try {
      android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
      o.inJustDecodeBounds = true;
      try (java.io.InputStream in = getContentResolver().openInputStream(u)) { android.graphics.BitmapFactory.decodeStream(in, null, o); }
      int max = Math.max(o.outWidth, o.outHeight), sz = 1;
      if (max <= 0) return null;
      while (max / (sz * 2) >= 1600) sz *= 2;           // грубо — не ниже 1600, точно — дальше матрицей
      android.graphics.BitmapFactory.Options o2 = new android.graphics.BitmapFactory.Options(); o2.inSampleSize = sz;
      android.graphics.Bitmap bm;
      try (java.io.InputStream in = getContentResolver().openInputStream(u)) { bm = android.graphics.BitmapFactory.decodeStream(in, null, o2); }
      if (bm == null) return null;
      bm = oriented(bm, exifRotation(u), PHOTO_MAX);
      dir.mkdirs();
      java.io.File f = new java.io.File(dir, "p" + System.currentTimeMillis() + ".jpg");
      try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) { bm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out); }
      bm.recycle();
      return f;
    } catch (Throwable t) { return null; }
  }

  /** Поворот снимка по EXIF, градусы по часовой. */
  int exifRotation(android.net.Uri u) {
    try (java.io.InputStream in = getContentResolver().openInputStream(u)) {
      int o = new android.media.ExifInterface(in).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL);
      return o == android.media.ExifInterface.ORIENTATION_ROTATE_90 ? 90 : o == android.media.ExifInterface.ORIENTATION_ROTATE_180 ? 180
           : o == android.media.ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
    } catch (Throwable t) { return 0; }
  }

  /** Повернуть и уменьшить до max по длинной стороне одной матрицей; исходник освобождается. */
  static android.graphics.Bitmap oriented(android.graphics.Bitmap bm, int rot, int max) {
    float k = Math.min(1f, (float) max / Math.max(bm.getWidth(), bm.getHeight()));
    if (k >= 1f && rot == 0) return bm;
    android.graphics.Matrix m = new android.graphics.Matrix(); m.postScale(k, k); m.postRotate(rot);
    android.graphics.Bitmap r = android.graphics.Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
    if (r != bm) bm.recycle();
    return r;
  }

  /** Перевод поверх снимка во весь экран (PhotoView). Касание абзаца — его оригинал и перевод
   *  текстом внизу, удержание — снимок без перевода, «✕» или «назад» — закрыть. */
  void openPhoto(int idx) {
    if (svc == null || svc.chats == null) return;
    org.json.JSONObject p = svc.chats.photo(idx);
    if (p == null) return;
    java.io.File f = new java.io.File(svc.chats.photos(), p.optString("file", ""));
    final android.graphics.Bitmap bm = f.isFile() ? android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath()) : null;
    if (bm == null) { onLog("📷 файла снимка нет — осталась только реплика с текстом"); return; }
    final android.app.Dialog d = new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
    FrameLayout root = new FrameLayout(this); root.setBackgroundColor(Color.BLACK);
    final PhotoView pv = new PhotoView(this, bm, p.optJSONArray("blocks"));
    root.addView(pv, new FrameLayout.LayoutParams(-1, -1));
    final String tip = "касание абзаца — его текст здесь · удержание — оригинал · щипок — крупнее";
    final TextView info = new TextView(this); info.setTextColor(Color.WHITE); info.setTextSize(16);
    info.setBackgroundColor(0xE0202020); info.setPadding(32, 20, 32, 28); info.setText(tip);
    info.setMaxLines(8); info.setMovementMethod(new ScrollingMovementMethod());
    root.addView(info, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
    Button close = new Button(this); close.setText("✕"); close.setTextSize(20);
    close.setOnClickListener(v -> d.dismiss());
    root.addView(close, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END));
    pv.onPick = (src, dst) -> {
      info.scrollTo(0, 0);
      info.setText(src == null ? tip : src + "\n→ " + (dst == null || dst.isEmpty() ? "(оставлено как есть: не португальский или служебное слово)" : dst));
    };
    d.setContentView(root);
    d.setOnDismissListener(x -> bm.recycle());
    d.show();
  }

  @Override public void onPhoto(long chatId, long at) {
    if (isFinishing() || isDestroyed() || svc == null || svc.chats == null || svc.chats.current != chatId) return;
    showLastTurn();
    java.util.List<String[]> all = svc.chats.all();
    for (int k = all.size() - 1; k >= 0; k--) if (all.get(k)[6].equals(String.valueOf(at))) { openPhoto(k); break; }
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
      bm = oriented(bm, exifRotation(u), 1024);          // облаку тоже — портрет с камеры иначе лежит на боку
      java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
      bm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, bo);
      bm.recycle();
      return bo.toByteArray();
    } catch (Throwable t) { return null; }
  }

  /** «Слова» (прежде «Изучение»): частотные слова португальской стороны собственных разговоров.
   *  Смысл в отборе: показываем не весь словарь, а то, что уже понадобилось несколько раз.
   *
   *  Сверху — «Учу / Знаю» сегментами со счётчиками (прежде кнопка «показываю: учу») и порог
   *  «встречалось не меньше N раз» кнопками −/+ (прежде ползунок без подписи). Ниже — карточка
   *  «Повторение на слух», слова карточками, внизу — своё слово. «Очистить выученное» — в «⋯» шапки:
   *  действие редкое и необратимое. Макет — design/mockups/index.html. */
  View buildLearn() {
    LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setBackgroundColor(look.bg);
    LinearLayout top = new LinearLayout(this); top.setOrientation(LinearLayout.VERTICAL); top.setPadding(dp(12), dp(12), dp(12), dp(4));
    segKnown = rows.seg(top, new String[]{"Учу", "Знаю"}, i -> { wordsKnown = i == 1; refreshWords(); });
    segKnown.sel(0);
    LinearLayout st = new LinearLayout(this); st.setOrientation(LinearLayout.HORIZONTAL); st.setGravity(Gravity.CENTER_VERTICAL);
    st.setPadding(dp(4), dp(10), 0, 0);
    TextView a = new TextView(this); a.setText("встречалось не меньше"); a.setTextSize(13.5f); a.setTextColor(look.dim);
    st.addView(a);
    LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.HORIZONTAL); box.setGravity(Gravity.CENTER_VERTICAL);
    box.setBackground(rows.round(look.bg, 18, look.line));
    TextView minus = stepBtn("−"), plus = stepBtn("+");
    minVal = new TextView(this); minVal.setTextSize(15); minVal.setTypeface(null, Typeface.BOLD); minVal.setTextColor(look.fg);
    minVal.setGravity(Gravity.CENTER); minVal.setMinWidth(dp(24)); minVal.setText(String.valueOf(wordsMin));
    box.addView(minus, new LinearLayout.LayoutParams(dp(36), dp(34))); box.addView(minVal); box.addView(plus, new LinearLayout.LayoutParams(dp(36), dp(34)));
    LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2); bp.leftMargin = dp(10); bp.rightMargin = dp(10);
    st.addView(box, bp);
    TextView b = new TextView(this); b.setText("раз"); b.setTextSize(13.5f); b.setTextColor(look.dim); st.addView(b);
    top.addView(st); stepperRow = st;
    // Порог — от 1 до 10 повторов, как у прежнего ползунка.
    minus.setOnClickListener(x -> { if (wordsMin > 1) { wordsMin--; minVal.setText(String.valueOf(wordsMin)); refreshWords(); } });
    plus.setOnClickListener(x -> { if (wordsMin < 10) { wordsMin++; minVal.setText(String.valueOf(wordsMin)); refreshWords(); } });
    learnHint = new TextView(this); learnHint.setTextSize(12); learnHint.setTextColor(look.soft); learnHint.setPadding(dp(4), dp(8), dp(4), 0);
    learnHint.setText("Слова из ваших разговоров, от частых к редким"); top.addView(learnHint);
    v.addView(top);
    v.addView(revCard = buildReview());
    wordList = new ListView(this); wordList.setDivider(null);
    v.addView(wordList, new LinearLayout.LayoutParams(-1, 0, 1f));
    // Свои слова — часть словаря, а не настройка приложения: имя, улица, название отеля.
    // Отсюда их видно рядом с тем, что уже выучено.
    LinearLayout rowW = new LinearLayout(this); rowW.setOrientation(LinearLayout.HORIZONTAL); rowW.setGravity(Gravity.CENTER_VERTICAL);
    rowW.setPadding(dp(12), dp(10), dp(12), dp(10));
    android.graphics.drawable.GradientDrawable top1 = rows.round(look.bg, 0, 0);
    android.graphics.drawable.LayerDrawable bar = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{rows.round(look.hair, 0, 0), top1});
    bar.setLayerInset(1, 0, Math.max(1, dp(1)), 0, 0); rowW.setBackground(bar);
    wordIn = new EditText(this); wordIn.setHint("Своё слово: отель, улица, имя"); wordIn.setSingleLine(true); wordIn.setTextSize(14.5f);
    wordIn.setBackground(rows.round(look.card, 22, look.line)); wordIn.setPadding(dp(16), dp(8), dp(16), dp(8));
    rowW.addView(wordIn, new LinearLayout.LayoutParams(0, dp(44), 1f));
    bWord = new Button(this); bWord.setText("+"); bWord.setTextSize(22); bWord.setTextColor(0xFFFFFFFF); bWord.setEnabled(false);
    bWord.setPadding(0, 0, 0, dp(2)); bWord.setStateListAnimator(null); bWord.setMinWidth(0); bWord.setMinimumWidth(0); bWord.setMinHeight(0); bWord.setMinimumHeight(0);
    android.graphics.drawable.StateListDrawable wb = new android.graphics.drawable.StateListDrawable();
    wb.addState(new int[]{-android.R.attr.state_enabled}, rows.round(look.offBg, 22, look.offLine));
    wb.addState(new int[]{android.R.attr.state_pressed}, rows.round(Look.DEEP, 22, 0));
    wb.addState(new int[]{}, rows.round(Look.PLUM, 22, 0));
    bWord.setBackground(wb); bWord.setContentDescription("Добавить своё слово");
    LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(dp(44), dp(44)); wp.leftMargin = dp(8);
    rowW.addView(bWord, wp);
    v.addView(rowW);
    // «Очистить выученное» — из «⋯» шапки; сама кнопка на экране больше не стоит.
    bClear = new Button(this); bClear.setEnabled(false);
    return v;
  }
  TextView stepBtn(String t) {
    TextView x = new TextView(this); x.setText(t); x.setTextSize(19); x.setTypeface(null, Typeface.BOLD); x.setGravity(Gravity.CENTER);
    x.setTextColor(look.night ? Look.GOLD : Look.PLUM); x.setBackground(rows.press(0)); x.setClickable(true); x.setOnTouchListener(haptic);
    return x;
  }

  /** Повторение на слух: приложение произносит слово, ответ закрыт, пока не попросят показать.
   *  Смысл именно в этом порядке — слово надо узнать ухом, а не прочитать. Карточка — сливовая, как
   *  шапка: свёрнутая — приглашение и «Начать», развёрнутая — само повторение. */
  View buildReview() {
    LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
    android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
        look.night ? new int[]{0xFF4A1530, 0xFF1D0816} : new int[]{Look.PLUM, Look.DEEP});
    g.setCornerRadius(dp(18)); card.setBackground(g); card.setPadding(dp(16), dp(14), dp(14), dp(14));
    LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2); cp.setMargins(dp(12), dp(8), dp(12), dp(6)); card.setLayoutParams(cp);
    LinearLayout head = new LinearLayout(this); head.setOrientation(LinearLayout.HORIZONTAL); head.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout ht = new LinearLayout(this); ht.setOrientation(LinearLayout.VERTICAL);
    TextView t1 = new TextView(this); t1.setText("Повторение на слух"); t1.setTextSize(16); t1.setTypeface(null, Typeface.BOLD); t1.setTextColor(0xFFFFFFFF);
    TextView t2 = new TextView(this); t2.setText("слово звучит — ответ скрыт, пока не попросите"); t2.setTextSize(12.5f); t2.setTextColor(0xCCFFFFFF);
    ht.addView(t1); ht.addView(t2);
    head.addView(ht, new LinearLayout.LayoutParams(0, -2, 1f));
    bRevStart = cardBtn("▶  Начать", true);
    head.addView(bRevStart, new LinearLayout.LayoutParams(-2, dp(40)));
    card.addView(head);
    LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(0, dp(12), 0, 0);
    revStat = new TextView(this); revStat.setTextSize(12); revStat.setTextColor(0xB3FFFFFF); v.addView(revStat);
    revWord = new TextView(this); revWord.setTextSize(28); revWord.setTypeface(null, Typeface.BOLD);
    revWord.setTextColor(0xFFFFFFFF); revWord.setText("слушайте"); v.addView(revWord);
    revRu = new TextView(this); revRu.setTextSize(19); revRu.setTextColor(Look.GOLD); v.addView(revRu);
    revEx = new TextView(this); revEx.setTextSize(12.5f); revEx.setTextColor(0xB3FFFFFF); revEx.setMaxLines(3); v.addView(revEx);
    LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); r.setPadding(0, dp(10), 0, 0);
    bRevPlay = cardBtn("Ещё раз", false); bRevShow = cardBtn("Показать", false);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1f); lp.rightMargin = dp(8);
    r.addView(bRevPlay, lp); r.addView(bRevShow, new LinearLayout.LayoutParams(0, dp(40), 1f));
    v.addView(r);
    LinearLayout r2 = new LinearLayout(this); r2.setOrientation(LinearLayout.HORIZONTAL); r2.setPadding(0, dp(8), 0, 0);
    bRevOk = cardBtn("Помню", true); bRevNo = cardBtn("Забыл", false);
    r2.addView(bRevOk, new LinearLayout.LayoutParams(lp)); r2.addView(bRevNo, new LinearLayout.LayoutParams(0, dp(40), 1f));
    v.addView(r2);
    card.addView(v);
    reviewBox = v; reviewBox.setVisibility(View.GONE);
    bRevPlay.setOnClickListener(x -> { if (svc != null && revCur != null) svc.sayWord(revCur, "pt"); });
    bRevShow.setOnClickListener(x -> reveal());
    bRevOk.setOnClickListener(x -> answer(true));
    bRevNo.setOnClickListener(x -> answer(false));
    bRevStart.setOnClickListener(x -> { revOpen = !revOpen; reviewBox.setVisibility(revOpen ? View.VISIBLE : View.GONE);
      bRevStart.setText(revOpen ? "Закрыть" : "▶  Начать");
      if (revOpen) nextReview(); });
    return card;
  }
  /** Кнопка на сливовой карточке: золотая (главная) или белой рамкой. */
  Button cardBtn(String text, boolean gold) {
    Button b = new Button(this); b.setAllCaps(false); b.setText(text); b.setTextSize(14); b.setTypeface(null, Typeface.BOLD);
    b.setStateListAnimator(null); b.setMinHeight(0); b.setMinimumHeight(0); b.setPadding(dp(16), 0, dp(16), 0); b.setOnTouchListener(haptic);
    android.graphics.drawable.StateListDrawable sl = new android.graphics.drawable.StateListDrawable();
    sl.addState(new int[]{android.R.attr.state_pressed}, rows.round(gold ? 0xFFC99F5E : 0x33FFFFFF, 20, gold ? 0 : 0x66FFFFFF));
    sl.addState(new int[]{}, rows.round(gold ? Look.GOLD : 0, 20, gold ? 0 : 0x66FFFFFF));
    b.setBackground(sl); b.setTextColor(gold ? Look.DEEP : 0xFFFFFFFF);
    return b;
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

  /** Строка «Слов»: слово крупно, рядом сколько раз встретилось, ниже перевод самого слова и мелко —
   *  пример из разговора. Нажатие произносит слово — заучивают на слух, а не глазами; значок 🔊 справа
   *  только напоминает об этом (есть, когда есть озвучка): отдельная кнопка в строке списка отнимала
   *  бы касание у самой строки. Долгое нажатие — «знаю» или назад в изучение. */
  class WordRow extends ArrayAdapter<Learn.Word> {
    final boolean knownMode, say;
    WordRow(java.util.List<Learn.Word> l, boolean km) { super(MainActivity.this, 0, l); knownMode = km; say = svc != null && svc.mod(Modules.TTS); }
    @Override public View getView(int pos, View cv, ViewGroup parent) {
      LinearLayout row = new LinearLayout(MainActivity.this); row.setOrientation(LinearLayout.HORIZONTAL);
      row.setPadding(dp(16), dp(11), dp(14), dp(11));
      android.graphics.drawable.LayerDrawable bl = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{rows.round(look.hair, 0, 0), rows.round(look.bg, 0, 0)});
      bl.setLayerInset(1, 0, 0, 0, Math.max(1, dp(1))); row.setBackground(bl);
      LinearLayout v = new LinearLayout(MainActivity.this); v.setOrientation(LinearLayout.VERTICAL);
      Learn.Word w = getItem(pos);
      LinearLayout top = new LinearLayout(MainActivity.this); top.setOrientation(LinearLayout.HORIZONTAL); top.setBaselineAligned(true);
      TextView word = new TextView(MainActivity.this);
      word.setText(w.w); word.setTextSize(20); word.setTypeface(null, Typeface.BOLD); word.setTextColor(look.fg);
      top.addView(word);
      TextView cnt = new TextView(MainActivity.this);
      cnt.setText(knownMode ? "знаю" : w.n + "×"); cnt.setTextSize(12); cnt.setTextColor(look.soft); cnt.setPadding(dp(8), 0, 0, 0);
      top.addView(cnt);
      v.addView(top);
      String ru = svc != null && svc.learn != null ? svc.learn.wordRu.get(w.w) : null;
      TextView tr = new TextView(MainActivity.this);
      tr.setText(ru == null ? "…" : ru); tr.setTextSize(16); tr.setTextColor(look.night ? Look.GOLD : Look.PLUM);
      v.addView(tr);
      if (!w.pt.isEmpty()) {
        TextView ex = new TextView(MainActivity.this);
        ex.setText(w.pt + "\n" + w.ru); ex.setTextSize(12.5f); ex.setTextColor(look.dim); ex.setLineSpacing(0, 1.1f); ex.setPadding(0, dp(3), 0, 0);
        ex.setMaxLines(3); ex.setEllipsize(android.text.TextUtils.TruncateAt.END);
        v.addView(ex);
      }
      row.addView(v, new LinearLayout.LayoutParams(0, -2, 1f));
      if (say) {
        ImageView sp = new ImageView(MainActivity.this); sp.setScaleType(ImageView.ScaleType.CENTER);
        android.graphics.drawable.Drawable d = icon(app.falar.R.drawable.ic_speaker, look.night ? Look.GOLD : Look.PLUM); d.setBounds(0, 0, dp(18), dp(18));
        sp.setImageDrawable(d); sp.setBackground(rows.round(look.card, 18, look.line));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(dp(36), dp(36)); sl.topMargin = dp(4); sl.leftMargin = dp(10);
        row.addView(sp, sl);
      }
      return row;
    }
  }

  int wordsReq = 0;
  /** Разбор слов — в рабочем потоке: первый проход читает все разговоры с диска, дальше работает
   *  кэш по отпечатку файлов, и ползунок только фильтрует готовые счётчики. */
  void refreshWords() {
    if (svc == null || svc.learn == null) { learnHint.setText("движок ещё грузится"); return; }
    final boolean knownMode = wordsKnown;
    final int min = wordsMin;
    final int req = ++wordsReq;
    if (stepperRow != null) stepperRow.setVisibility(knownMode ? View.GONE : View.VISIBLE);
    new Thread(() -> {
      final java.util.List<Learn.Word> l = knownMode ? svc.learn.knownWords() : svc.learn.top(min, 200);
      // Счётчики на сегментах — «Учу · 200», «Знаю · 48»: второй список тоже из кэша разбора.
      final int nLearn = knownMode ? svc.learn.top(min, 200).size() : l.size(), nKnown = knownMode ? l.size() : svc.learn.knownWords().size();
      runOnUiThread(() -> { if (req != wordsReq) return;
        showWords(l, knownMode, min);
        Rows.label(segKnown, 0, "Учу · " + nLearn); Rows.label(segKnown, 1, "Знаю · " + nKnown); });
    }, "words").start();
  }
  void showWords(java.util.List<Learn.Word> l, final boolean knownMode, int min) {
    if (svc == null || svc.learn == null) return;
    shownWords.clear();
    for (Learn.Word w : l) shownWords.add(w.w);
    String say = svc.mod(Modules.TTS) ? "нажатие произносит, " : "";
    if (knownMode) learnHint.setText(l.isEmpty() ? "Известных слов пока нет — отмечайте их долгим нажатием в «Учу»"
                                                 : "Знаю: " + l.size() + " слов · " + say + "долгое возвращает в изучение");
    else learnHint.setText(l.isEmpty()
          ? "Пока нечего показать: нужно, чтобы слово встретилось не меньше " + min + " раз"
          : "Слов от " + min + " повторов: " + l.size() + " · " + say + "долгое — «знаю»");
    wordList.setAdapter(new WordRow(l, knownMode));
    wordList.setOnItemClickListener((p, vv, pos, id) -> {
      if (pos < shownWords.size() && svc != null && svc.mod(Modules.TTS)) svc.sayWord(shownWords.get(pos), "pt");
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

  /** Настройки (прежде «Система»): четыре группы на главном экране — экран разговора, микрофон и
   *  звук, перевод, приложение — и отдельные экраны для редкого: «Модули и файлы», «Облако»,
   *  «Журнал». Раньше это была одна колонка из 23 кнопок, переключателей и ползунков вперемешку с
   *  подписями: «ВКЛ/выкл» — текстом на кнопке во всю ширину, интервалы — нажатием по кругу, и
   *  других значений было не видно (стенд, 30.09). Теперь переключатель — это переключатель, а выбор
   *  из нескольких значений — сегменты. Ничего не убрано: где что теперь — в таблице макета
   *  design/mockups/index.html. Пункты модулей прячутся без модуля, как раньше. */
  View buildSys() {
    Rows r = rows;
    LinearLayout v = r.page();
    // Обновление — первой карточкой: магазина нет, и это единственное место, где видно новую версию.
    updCard = new LinearLayout(this); updCard.setOrientation(LinearLayout.HORIZONTAL); updCard.setGravity(Gravity.CENTER_VERTICAL);
    updCard.setPadding(dp(14), dp(12), dp(12), dp(12));
    ImageView ui = new ImageView(this); ui.setImageDrawable(icon(app.falar.R.drawable.ic_download, look.night ? Look.GOLD : 0xFF9A6B1F));
    updCard.addView(ui, new LinearLayout.LayoutParams(dp(24), dp(24)));
    LinearLayout ut = new LinearLayout(this); ut.setOrientation(LinearLayout.VERTICAL); ut.setPadding(dp(14), 0, dp(8), 0);
    updTitle = new TextView(this); updTitle.setTextSize(15.5f); updTitle.setTextColor(look.fg); updTitle.setTypeface(null, Typeface.BOLD); updTitle.setText("Falar");
    updLbl = new TextView(this); updLbl.setTextSize(12.5f); updLbl.setTextColor(look.dim);
    ut.addView(updTitle); ut.addView(updLbl);
    updCard.addView(ut, new LinearLayout.LayoutParams(0, -2, 1f));
    bUpdate = r.button("Проверить", false); bUpdateGo = r.button("Обновить", true); bUpdateGo.setVisibility(View.GONE);
    updCard.addView(bUpdate, new LinearLayout.LayoutParams(-2, dp(40))); updCard.addView(bUpdateGo, new LinearLayout.LayoutParams(-2, dp(40)));
    LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(-1, -2); up.topMargin = dp(12);
    v.addView(updCard, up);
    bUpdate.setOnClickListener(vv -> { if (svc != null) svc.checkUpdates(true); });
    bUpdateGo.setOnClickListener(vv -> { if (svc != null) svc.installUpdate(); });
    status = new TextView(this); status.setTextSize(12); status.setTextColor(look.soft); status.setPadding(dp(8), dp(8), dp(8), 0);
    status.setText("Запуск сервиса…"); v.addView(status);

    // ---- экран разговора
    LinearLayout c1 = r.group(v, "Экран разговора");
    sizeLbl = r.value("");
    r.row(c1, app.falar.R.drawable.ic_textsize, "Размер текста", null, sizeLbl);
    LinearLayout s1 = r.sub(c1, true);
    LinearLayout prev = new LinearLayout(this); prev.setOrientation(LinearLayout.VERTICAL); prev.setBackground(r.round(look.card, 12, look.line));
    prev.setPadding(dp(12), dp(8), dp(12), dp(10));
    prevPt = new TextView(this); prevPt.setText("Quanto custa?"); prevPt.setTypeface(null, Typeface.BOLD); prevPt.setTextColor(look.fg);
    prevRu = new TextView(this); prevRu.setText("Сколько стоит?"); prevRu.setTextColor(look.dim);
    prev.addView(prevPt); prev.addView(prevRu); s1.addView(prev);
    sPt = new SeekBar(this); sPt.setMax(48); sPt.setProgress((int) szPt);
    sRu = new SeekBar(this); sRu.setMax(48); sRu.setProgress((int) szRu);
    s1.addView(sliderRow("PT", sPt)); s1.addView(sliderRow("RU", sRu));
    SeekBar.OnSeekBarChangeListener sl = new SeekBar.OnSeekBarChangeListener() {
      public void onProgressChanged(SeekBar sb, int p, boolean u) { applySizes(); }
      public void onStartTrackingTouch(SeekBar sb) {}
      public void onStopTrackingTouch(SeekBar sb) { prefs.edit().putFloat("szPt", szPt).putFloat("szRu", szRu).apply(); }
    };
    sPt.setOnSeekBarChangeListener(sl); sRu.setOnSeekBarChangeListener(sl);
    tTranslitOther = r.sw(); tTranslitOther.setChecked(prefs.getBoolean("translit_other", false));
    r.row(c1, app.falar.R.drawable.ic_type, "Транскрипция и у собеседника", "русскими буквами под словами его реплик", tTranslitOther);
    tTranslitOther.setOnCheckedChangeListener((vv, on) -> { prefs.edit().putBoolean("translit_other", on).apply(); cribManual = false; showBig(bigText, bigDir, bigRefined); refreshHint(); });
    // Что считать чтением вслух. «Пока видно» по умолчанию не ставим: транскрипция висит на экране
    // до следующей реплики, и в этом положении ответ собеседника пропускается.
    r.row(c1, app.falar.R.drawable.ic_hush, "Пока вы читаете вслух", "микрофон молчит, чтобы не перевести вас же", null);
    LinearLayout s2 = r.sub(c1, true);
    segReadGuard = r.seg(s2, new String[]{"Слушать", "Пока держу", "Пока видно"}, i -> { if (svc == null) return; svc.setReadGuard(i); refreshReadGuard(); syncGuard(); });
    r.caption(s2, "«Пока держу» — пока палец на крупном тексте; «пока видно» — пока видна транскрипция.");

    // ---- микрофон и звук
    LinearLayout c2 = r.group(v, "Микрофон и звук");
    // Авто по умолчанию: одна ручка не подходит разом тихому и обычному собеседнику — +24 дБ
    // спасают тихого и портят обычного (results/2026-09-29-mic-gain.md). Ручная остаётся.
    cMicAuto = r.sw(); cMicAuto.setChecked(prefs.getBoolean("micauto", true));
    Rows.Row sens = r.row(c2, app.falar.R.drawable.ic_mic, "Чувствительность — авто", "", cMicAuto);
    micLbl = sens.sub; micLbl.setVisibility(View.VISIBLE);
    LinearLayout s3 = r.sub(c2, true);
    // Ползунок показывает то, что сервис применяет на самом деле: раньше он рисовал +0 дБ, а
    // работало сохранённое значение — настройка врала о собственном состоянии.
    sMic = new SeekBar(this); sMic.setMax(24); sMic.setProgress((int) prefs.getFloat("micgain", 0)); sMic.setEnabled(!cMicAuto.isChecked());   // 0…+24 дБ
    micVal = new TextView(this);
    s3.addView(sliderRow("вручную", sMic, micVal));
    micLabel();
    cMicAuto.setOnCheckedChangeListener((b, on) -> {
      sMic.setEnabled(!on); micLabel();
      if (svc != null) startService(new Intent(this, TranslatorService.class).putExtra("micauto", on ? "1" : "0"));
    });
    sMic.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      public void onProgressChanged(SeekBar sb, int p, boolean u) { micLabel(); }
      public void onStartTrackingTouch(SeekBar sb) {}
      public void onStopTrackingTouch(SeekBar sb) { if (svc != null) startService(new Intent(MainActivity.this, TranslatorService.class)
          .putExtra("micgain", String.valueOf(sb.getProgress()))); }
    });
    r.row(c2, app.falar.R.drawable.ic_phones, "Микрофон", null, null);
    LinearLayout s4 = r.sub(c2, true);
    segMicSrc = r.seg(s4, new String[]{"Телефона", "Наушников"}, i -> {
      if (i == 0) { startService(new Intent(this, TranslatorService.class).putExtra("micsrc", "builtin")); return; }
      new android.app.AlertDialog.Builder(this)
          .setTitle("Микрофон наушников?")
          .setMessage("Наушники берут звук по профилю гарнитуры, и тогда вывод в них падает "
                    + "до телефонного качества. Годится, если телефон в кармане, а собеседник рядом с вами.")
          .setPositiveButton("включить", (d, w) -> startService(new Intent(this, TranslatorService.class).putExtra("micsrc", "headset")))
          .setNegativeButton("отмена", (d, w) -> segMicSrc.sel(0))
          .setOnCancelListener(d -> segMicSrc.sel(0))   // «назад» тоже отмена, иначе выбор врёт
          .show();
    });
    segMicSrc.sel("headset".equals(prefs.getString("micsrc", "builtin")) ? 1 : 0);
    // Пауза до озвучки. Перевод готовится сразу и сразу виден на экране — ждёт только голос,
    // иначе на длинном монологе перевод начинает звучать собеседнику в лицо посреди фразы.
    ttsBox = r.part(c2);
    int hold = prefs.getInt("hold", 1500);
    holdLbl = r.value(holdText(hold));
    r.row(ttsBox, app.falar.R.drawable.ic_speaker, "Пауза до озвучки", "перевод виден сразу, голос ждёт тишины", holdLbl);
    LinearLayout s5 = r.sub(ttsBox, true);
    sHold = new SeekBar(this); sHold.setMax(24); sHold.setProgress(hold / 250); s5.addView(sHold);   // 0…6,0 с шагом 0,25
    sHold.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      public void onProgressChanged(SeekBar sb, int p, boolean u) { holdLbl.setText(holdText(p * 250)); }
      public void onStartTrackingTouch(SeekBar sb) {}
      public void onStopTrackingTouch(SeekBar sb) {
        prefs.edit().putInt("hold", sb.getProgress() * 250).apply();
        startService(new Intent(MainActivity.this, TranslatorService.class).putExtra("hold", String.valueOf(sb.getProgress() * 250)));
      }
    });
    rowVoices = r.nav(c2, app.falar.R.drawable.ic_users, "Голоса и авто-направление", "", null);
    bVoices = rowVoices.v;

    // ---- перевод
    LinearLayout c3 = r.group(v, "Перевод"); grpTr = (View) c3.getTag();
    llmBox = r.part(c3);
    tCtx = r.sw(); tCtx.setEnabled(false);
    r.row(llmBox, app.falar.R.drawable.ic_wand, "Уточнитель в фоне", "переводит заново с учётом разговора", tCtx);
    r.row(llmBox, app.falar.R.drawable.ic_refresh, "Разбор контекста", "как часто уточнитель пересматривает реплики", null);
    LinearLayout s6 = r.sub(llmBox, true);
    final int[] refVals = {0, 1, 3, 10};
    segRefine = r.seg(s6, new String[]{"По кнопке", "1", "3", "10"}, i -> { if (svc == null) return; svc.setRefineEvery(refVals[i]); refreshIntervals(); });
    r.caption(s6, "реплик между разборами");
    cloudBox = r.part(c3);
    rowCloud = r.nav(cloudBox, app.falar.R.drawable.ic_cloud, "Облако", "", x -> show(6));

    // ---- приложение
    LinearLayout c4 = r.group(v, "Приложение");
    rowMods = r.nav(c4, app.falar.R.drawable.ic_scan, "Модули и файлы", "", x -> { show(5); refreshModules(); refreshModels(); });
    rowLog = r.nav(c4, app.falar.R.drawable.ic_list, "Журнал", "", x -> show(7));
    logLast = rowLog.sub; logLast.setVisibility(View.VISIBLE); logLast.setSingleLine(true); logLast.setEllipsize(android.text.TextUtils.TruncateAt.END);

    ScrollView outer = new ScrollView(this); outer.setBackgroundColor(look.page); outer.addView(v);
    return outer;
  }

  /** Ползунок с подписью слева и значением справа. */
  View sliderRow(String label, SeekBar sb) { return sliderRow(label, sb, null); }
  View sliderRow(String label, SeekBar sb, TextView val) {
    LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(0, dp(6), 0, 0);
    TextView l = new TextView(this); l.setText(label); l.setTextSize(13); l.setTextColor(look.dim); l.setMinWidth(dp(36));
    row.addView(l);
    row.addView(sb, new LinearLayout.LayoutParams(0, -2, 1f));
    if (val != null) { val.setTextSize(13); val.setTypeface(null, Typeface.BOLD); val.setTextColor(look.fg); val.setMinWidth(dp(44)); val.setGravity(Gravity.END); row.addView(val); }
    return row;
  }

  /** Строки модулей в «Модулях и файлах»: переключатель, размер, метка состояния, полоса загрузки. */
  static final class ModRow { Switch sw; TextView size, pill, barLbl; ProgressLine bar; View barBox; }
  static int modIcon(String m) {
    switch (m) {
      case Modules.TTS: return app.falar.R.drawable.ic_speaker;
      case Modules.OCR: return app.falar.R.drawable.ic_scan;
      case Modules.LLM: return app.falar.R.drawable.ic_wand;
      case Modules.CLOUD: return app.falar.R.drawable.ic_cloud;
      case Modules.SPEAKER: return app.falar.R.drawable.ic_voice;
      case Modules.CORPUS: return app.falar.R.drawable.ic_books;
      default: return app.falar.R.drawable.ic_mic;
    }
  }

  /** «Модули и файлы»: что лежит на телефоне. Сверху одна строка — «Все модели установлены ·
   *  14 из 14» или ход докачки со «Стоп» (владелец 30.09: пояснения «всё на месте» отдельной
   *  карточкой не нужны). У каждого модуля — своя полоса загрузки. Выключенный модуль прячет свои
   *  кнопки и не держит память; его файлы остаются, пока их не удалить отдельной кнопкой
   *  (решение владельца 28.09). */
  View buildMods() {
    Rows r = rows;
    LinearLayout v = r.page();
    TextView h = new TextView(this); h.setText("Модули"); h.setTextSize(13); h.setTypeface(null, Typeface.BOLD); h.setTextColor(r.ink());
    h.setPadding(dp(8), dp(16), dp(8), dp(6)); v.addView(h);
    LinearLayout sum = new LinearLayout(this); sum.setOrientation(LinearLayout.HORIZONTAL); sum.setGravity(Gravity.CENTER_VERTICAL);
    sum.setPadding(dp(8), 0, dp(4), dp(8));
    modsSum = new TextView(this); modsSum.setTextSize(13); modsSum.setTextColor(look.dim); modsSum.setCompoundDrawablePadding(dp(8));
    sum.addView(modsSum, new LinearLayout.LayoutParams(0, -2, 1f));
    bModelsStop = r.button("Стоп", false); bModelsStop.setVisibility(View.GONE);
    sum.addView(bModelsStop, new LinearLayout.LayoutParams(-2, dp(34)));
    v.addView(sum);
    modelsLbl = new TextView(this); modelsLbl.setTextSize(12); modelsLbl.setTextColor(look.soft); modelsLbl.setPadding(dp(8), 0, dp(8), dp(8));
    modelsLbl.setVisibility(View.GONE); v.addView(modelsLbl);
    modBox = new LinearLayout(this); modBox.setOrientation(LinearLayout.VERTICAL); modBox.setBackground(r.round(look.bg, 16, look.line));
    v.addView(modBox);
    java.util.List<String> all = new java.util.ArrayList<>(); all.add(Modules.BASE); all.addAll(Modules.CHOICE);
    for (final String m : all) {
      if (modBox.getChildCount() > 0) r.divider(modBox);
      ModRow mr = new ModRow();
      LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setPadding(dp(14), dp(13), dp(12), dp(13));
      ImageView tile = new ImageView(this); tile.setScaleType(ImageView.ScaleType.CENTER);
      tile.setImageDrawable(icon(modIcon(m), r.ink())); tile.setBackground(r.round(look.tint, 12, 0));
      row.addView(tile, new LinearLayout.LayoutParams(dp(40), dp(40)));
      LinearLayout t = new LinearLayout(this); t.setOrientation(LinearLayout.VERTICAL); t.setPadding(dp(12), 0, dp(8), 0);
      TextView name = new TextView(this); name.setText(Modules.title(m).replaceFirst("^[^\\p{L}]+", "")); name.setTextSize(15.5f); name.setTypeface(null, Typeface.BOLD); name.setTextColor(look.fg);
      TextView what = new TextView(this); what.setText(Modules.what(m)); what.setTextSize(12.5f); what.setTextColor(look.dim); what.setLineSpacing(0, 1.1f);
      t.addView(name); t.addView(what);
      LinearLayout meta = new LinearLayout(this); meta.setOrientation(LinearLayout.HORIZONTAL); meta.setGravity(Gravity.CENTER_VERTICAL); meta.setPadding(0, dp(7), 0, 0);
      mr.size = new TextView(this); mr.size.setTextSize(12); mr.size.setTextColor(look.soft); mr.size.setPadding(0, 0, dp(8), 0);
      mr.pill = r.pill();
      meta.addView(mr.size); meta.addView(mr.pill); t.addView(meta);
      LinearLayout bb = new LinearLayout(this); bb.setOrientation(LinearLayout.VERTICAL); bb.setPadding(0, dp(9), 0, 0);
      mr.bar = new ProgressLine(this).colors(r.ink(), look.line, r.ink(), look.line);
      bb.addView(mr.bar, new LinearLayout.LayoutParams(-1, dp(7)));
      mr.barLbl = new TextView(this); mr.barLbl.setTextSize(12); mr.barLbl.setTextColor(look.soft); mr.barLbl.setPadding(0, dp(4), 0, 0);
      bb.addView(mr.barLbl); bb.setVisibility(View.GONE); mr.barBox = bb;
      t.addView(bb);
      row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
      mr.sw = r.sw();
      LinearLayout.LayoutParams swp = new LinearLayout.LayoutParams(-2, -2); swp.topMargin = dp(6);
      row.addView(mr.sw, swp);
      modBox.addView(row);
      modRows.put(m, mr);
      if (Modules.BASE.equals(m)) { mr.sw.setChecked(true); mr.sw.setEnabled(false); continue; }   // перевод речи нужен всегда
      modChecks.put(m, mr.sw);
      mr.sw.setOnCheckedChangeListener((vv, on) -> {
        if (uiSync || svc == null) return;
        if (on && svc.store != null && svc.store.bytes(m) > 0 && !svc.store.onPhone(m))
          onLog("🧩 " + Modules.title(m) + ": включён, докачивается " + ModelStore.mb(svc.store.bytes(m)) + " МБ"
                + (prefs.getBoolean("models_any_net", false) ? "" : " — по Wi-Fi"));
        svc.setModule(m, on); applyModules();
      });
    }
    LinearLayout c = r.group(v, "Загрузка");
    tAnyNet = r.sw(); tAnyNet.setChecked(prefs.getBoolean("models_any_net", false));
    r.row(c, app.falar.R.drawable.ic_download, "Качать и по мобильной сети", "иначе — только по Wi-Fi", tAnyNet);
    tAnyNet.setOnCheckedChangeListener((vv, on) -> setAnyNet(on));
    LinearLayout s = r.sub(c, false);
    FlowLayout btns = new FlowLayout(this);
    bModelsVerify = r.button("Проверить файлы", false); bModelsVerify.setEnabled(false);
    bUnused = r.button("Удалить неиспользуемые", false);
    // Появляется, когда у перевода есть файлы полегче, а на телефоне — прежние (обновление 0.23).
    bModelsUp = r.button("Облегчить перевод", false); bModelsUp.setVisibility(View.GONE);
    for (Button b : new Button[]{bModelsVerify, bUnused, bModelsUp}) {
      FrameLayout w = new FrameLayout(this); w.setPadding(0, dp(4), dp(8), dp(4));
      w.addView(b, new FrameLayout.LayoutParams(-2, dp(40))); btns.addView(w);
    }
    s.addView(btns);
    bModelsUp.setOnClickListener(vv -> upgradeDialog());
    bModelsStop.setOnClickListener(vv -> { if (svc != null) svc.cancelModels(); });
    bModelsVerify.setOnClickListener(vv -> { if (svc != null) svc.verifyModels(); });
    bUnused.setOnClickListener(vv -> unusedDialog());
    ScrollView outer = new ScrollView(this); outer.setBackgroundColor(look.page); outer.addView(v);
    return outer;
  }

  /** «Облако»: ключи OpenRouter — у каждого своё состояние и своя кнопка «убрать» (владелец 30.09:
   *  «удалить ключ перенести к ключу, сделать возможность добавить несколько ключей»), куда пойдёт
   *  запрос, как часто пересматривать разговор и что облаку важнее. */
  View buildCloud() {
    Rows r = rows;
    LinearLayout v = r.page();
    TextView note = new TextView(this); note.setTextSize(12.5f); note.setTextColor(look.dim); note.setLineSpacing(0, 1.1f);
    note.setText("Falar ходит только к бесплатным моделям OpenRouter (:free, цена 0 в каждом запросе). Деньги с ключей не списываются.");
    note.setPadding(dp(12), dp(10), dp(12), dp(10));
    android.graphics.drawable.GradientDrawable nb = r.round(look.bg, 12, 0);
    android.graphics.drawable.LayerDrawable nl = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{r.round(Look.GOLD, 12, 0), nb});
    nl.setLayerInset(1, dp(3), 0, 0, 0); note.setBackground(nl);
    LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2); np.topMargin = dp(12);
    v.addView(note, np);
    LinearLayout c = r.group(v, "Ключи");
    keysBox = new LinearLayout(this); keysBox.setOrientation(LinearLayout.VERTICAL); c.addView(keysBox);
    r.divider(c);
    TextView add = new TextView(this); add.setText("Добавить ключ"); add.setTextSize(15); add.setTypeface(null, Typeface.BOLD); add.setTextColor(r.ink());
    add.setCompoundDrawablePadding(dp(12)); add.setCompoundDrawables(r.icon(app.falar.R.drawable.ic_plus, r.ink(), 22), null, null, null);
    add.setPadding(dp(14), dp(13), dp(14), dp(13)); add.setBackground(r.press(0)); add.setClickable(true);
    c.addView(add);
    addBox = new LinearLayout(this); addBox.setOrientation(LinearLayout.HORIZONTAL); addBox.setGravity(Gravity.CENTER_VERTICAL);
    addBox.setPadding(dp(14), 0, dp(14), dp(12));
    keyIn = new EditText(this); keyIn.setHint("sk-or-…"); keyIn.setSingleLine(true); keyIn.setTextSize(14.5f);
    keyIn.setBackground(r.round(look.card, 22, look.line)); keyIn.setPadding(dp(16), dp(8), dp(16), dp(8));
    addBox.addView(keyIn, new LinearLayout.LayoutParams(0, dp(44), 1f));
    bKey = r.button("Сохранить", true); bKey.setEnabled(false);
    LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(-2, dp(44)); kp.leftMargin = dp(8);
    addBox.addView(bKey, kp);
    addBox.setVisibility(View.GONE); c.addView(addBox);
    add.setOnClickListener(x -> addBox.setVisibility(addBox.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
    // Состояние ключей и ответ на последнее действие видны всегда. Поле после сохранения очищается,
    // и без этой строки отличить «сохранён» от «не сохранился» было нельзя — ключ вводили повторно.
    LinearLayout ks = r.sub(c, false);
    keyState = new TextView(this); keyState.setTextSize(12.5f); keyState.setTextColor(look.accent); ks.addView(keyState);
    r.caption(ks, "Запрос идёт с первого рабочего ключа; если OpenRouter его не принял — со следующего. "
        + "Лимит бесплатных запросов OpenRouter считает на аккаунт: второй ключ его не поднимет.");
    bKeyHelp = r.nav(c, app.falar.R.drawable.ic_key, "Как получить ключ", "openrouter.ai → Keys → Create Key", null).v;
    LinearLayout c2 = r.group(v, "Куда пойдёт запрос");
    rowRoute = r.nav(c2, app.falar.R.drawable.ic_cloud, "Модели и маршрут", "", null);
    bModels = rowRoute.v;
    // Как часто разбирать разговор в облаке. 0 — только по кнопке «Получше».
    LinearLayout c3 = r.group(v, "Пересмотр разговора");
    LinearLayout s3 = r.sub(c3, false); s3.setPadding(dp(14), dp(14), dp(14), dp(14));
    final int[] cloudVals = {0, 5, 10, 20};
    segCloudEvery = r.seg(s3, new String[]{"По кнопке", "5", "10", "20"}, i -> {
      if (svc == null) return;
      final int next = cloudVals[i];
      if (next > 0 && !svc.cloudConsent()) { consentDialog(() -> { svc.setCloudEvery(next); refreshIntervals(); }); refreshIntervals(); }
      else { svc.setCloudEvery(next); refreshIntervals(); }
    });
    r.caption(s3, "реплик между пересмотрами. Разговор уходит в облако целиком, до 6000 знаков с конца; согласие спрашивается один раз.");
    // Что важнее облаку: быстрый ответ или точный. «Точнее» ставит вперёд крупные модели — ответ
    // приходит за полминуты, зато правок и деталей в памяти больше.
    LinearLayout c4 = r.group(v, "Облако выбирает");
    LinearLayout s4 = r.sub(c4, false); s4.setPadding(dp(14), dp(14), dp(14), dp(14));
    segPrefer = r.seg(s4, new String[]{"Быстрее", "Точнее"}, i -> { if (svc == null) return; svc.setCloudQuality(i == 1); refreshIntervals(); });
    r.caption(s4, "«Точнее» — сначала крупные модели: ответ до полуминуты, зато больше правок и деталей в памяти разговора.");
    ScrollView outer = new ScrollView(this); outer.setBackgroundColor(look.page); outer.addView(v);
    return outer;
  }

  /** «Журнал» — отдельным экраном: это отладочная лента, а ответ приложения на последнее действие
   *  виден и без него — последней строкой в пункте «Журнал» настроек. */
  View buildLog() {
    logView = new TextView(this); logView.setTextSize(13); logView.setTextColor(look.dim); logView.setTextIsSelectable(true);
    logView.setPadding(dp(16), dp(12), dp(16), dp(24)); logView.setLineSpacing(0, 1.1f);
    logScroll = new ScrollView(this); logScroll.setBackgroundColor(look.bg); logScroll.addView(logView);
    return logScroll;
  }
  int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
  int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

  /** Режим настройки голосов. Отдельный экран потому, что это разовая настройка со своим смыслом:
   *  приложение запоминает, как звучите вы и собеседник, и дальше выводит направление перевода
   *  из языка узнанного голоса. Состояние профилей показано прямо здесь — раньше оно было только
   *  в журнале, и снаружи запись голоса выглядела как кнопка, которая ничего не делает. */
  View buildVoice() {
    LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(24, 12, 24, 12);
    bVoiceBack = new Button(this); bVoiceBack.setText("← настройки"); bVoiceBack.setTextSize(13); bVoiceBack.setVisibility(View.GONE);   // назад — стрелкой шапки
    TextView t = new TextView(this); t.setTextSize(13); t.setTextColor(look.soft);
    t.setText("Приложение запоминает голос и язык, на котором этот голос говорит. Дальше направление "
            + "перевода берётся из того, чей голос услышан, а не из кнопок. Держите кнопку и говорите "
            + "две-три секунды обычным голосом.");
    v.addView(t);
    voiceState = new TextView(this); voiceState.setTextSize(15); voiceState.setTextColor(look.fg);
    voiceState.setPadding(0, 14, 0, 4); v.addView(voiceState);
    voiceMsg = new TextView(this); voiceMsg.setTextSize(13); voiceMsg.setTextColor(look.accent); v.addView(voiceMsg);
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
    if (rowVoices != null) { rowVoices.sub.setText((me ? "мой голос записан" : "мой голос не записан") + " · собеседник — " + (other ? "записан" : "нет")); rowVoices.sub.setVisibility(View.VISIBLE); }
    voiceState.setText("мой голос: " + (me ? "записан" : "не записан")
                     + "\nсобеседник: " + (other ? "записан" : "не записан")
                     + (me || other ? "\nпрофили: " + svc.spk.describe() : ""));
    tAuto.setEnabled(me); bForget.setEnabled(me || other);
    tAuto.setChecked(svc.autoDir);
  }

  /** Ключи: у каждого — состояние и своя корзина. Опознаётся по началу и хвосту, целиком на экране
   *  не нужен. Первый — рабочий, остальные — запасные; ключ, который OpenRouter не принял, — с
   *  пометкой. Ниже — куда пойдёт следующий запрос: маршрут считается сам, и не видеть его нельзя. */
  String keyNoteText = "";
  void refreshKey(String note) {
    if (keyState == null) return;
    Cloud c = svc == null ? null : svc.cloud;
    java.util.List<String> ks = c == null ? new java.util.ArrayList<>() : c.keys();
    keysBox.removeAllViews();
    for (int i = 0; i < ks.size(); i++) {
      final String k = ks.get(i); String n = c.note(k);
      if (i > 0) rows.divider(keysBox);
      LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
      row.setPadding(dp(14), dp(10), dp(6), dp(10));
      View dot = new View(this); dot.setBackground(rows.round(!n.isEmpty() ? 0xFFC8245A : i == 0 ? (look.night ? Look.MINT : 0xFF1E9E77) : Look.GOLD, 5, 0));
      row.addView(dot, new LinearLayout.LayoutParams(dp(10), dp(10)));
      LinearLayout t = new LinearLayout(this); t.setOrientation(LinearLayout.VERTICAL); t.setPadding(dp(14), 0, dp(8), 0);
      TextView a = new TextView(this); a.setText("Ключ " + Cloud.idOf(k)); a.setTextSize(15.5f); a.setTextColor(look.fg);
      TextView b = new TextView(this); b.setTextSize(12.5f); b.setTextColor(look.dim);
      b.setText(!n.isEmpty() ? n : i == 0 ? "рабочий · запросы идут с него" : "запасной");
      t.addView(a); t.addView(b);
      row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
      ImageView del = new ImageView(this); del.setScaleType(ImageView.ScaleType.CENTER); del.setContentDescription("Убрать ключ");
      del.setImageDrawable(icon(app.falar.R.drawable.ic_trash, rows.ink())); del.setBackground(rows.press(0)); del.setClickable(true);
      del.setOnClickListener(x -> new android.app.AlertDialog.Builder(this).setTitle("Убрать ключ " + Cloud.idOf(k) + "?")
          .setMessage(ks.size() > 1 ? "Запросы пойдут со следующего ключа. Ключ на сайте OpenRouter останется — его можно вставить снова."
                                    : "Облако перестанет работать: «Получше» облаком, пересмотр разговора и названия разговоров. "
                                    + "Ключ на сайте OpenRouter останется — его можно вставить снова.")
          .setPositiveButton("убрать", (d, w) -> new Thread(() -> { final String r = svc.removeCloudKey(k);
              runOnUiThread(() -> refreshKey(r)); }).start())
          .setNegativeButton("отмена", null).show());
      row.addView(del, new LinearLayout.LayoutParams(dp(44), dp(44)));
      keysBox.addView(row);
    }
    if (ks.isEmpty()) {
      TextView none = new TextView(this); none.setText("Ключа нет — названия разговоров и «Получше» облаком выключены");
      none.setTextSize(13); none.setTextColor(look.dim); none.setPadding(dp(14), dp(12), dp(14), dp(12));
      keysBox.addView(none);
      if (addBox != null) addBox.setVisibility(View.VISIBLE);
    }
    // Ответ на последнее действие с ключом держится до следующего: журнал облака его не стирает.
    if (note != null) keyNoteText = note;
    keyState.setText(keyNoteText); keyState.setVisibility(keyNoteText.isEmpty() ? View.GONE : View.VISIBLE);
    if (rowRoute != null) {
      boolean any = c != null && c.modelCount() > 0;
      rowRoute.title.setText(!any ? "Моделей нет — сначала ключ" : (c.auto() ? "Авто → " : "Закреплена ") + c.next().replace(":free", ""));
      rowRoute.sub.setText(!any ? "" : "бесплатных моделей: " + c.modelCount() + (c.lastUsed.isEmpty() ? "" : " · последней отвечала " + c.lastUsed.replace(":free", "")));
      rowRoute.sub.setVisibility(any ? View.VISIBLE : View.GONE);
      bModels.setEnabled(any);
    }
    refreshCloudRow();
  }

  /** Как получить ключ OpenRouter и что даёт пополнение. Лимиты — по справке OpenRouter на
   *  29.09.2026: бесплатные модели — 20 запросов в минуту и 50 в сутки; после покупки кредитов
   *  на $10 и больше (за всё время) — 1000 в сутки, навсегда. Falar ходит только к моделям с
   *  нулевой ценой (Cloud: суффикс :free и max_price 0 в каждом запросе), так что пополнение
   *  поднимает лимит, а не тратится. */
  void keyHelpDialog() {
    TextView t = new TextView(this); t.setTextSize(15); t.setPadding(48, 24, 48, 8);
    t.setText("1. Зарегистрируйтесь на openrouter.ai — можно через Google.\n"
        + "2. Откройте «Keys» и нажмите «Create Key». Лимит расходов ключа можно поставить 0,01 $: "
        + "Falar ходит только к бесплатным моделям, лимит лишь страхует.\n"
        + "3. Скопируйте ключ (sk-or-…), вставьте в поле и нажмите «сохранить».\n\n"
        + "Бесплатно: до 50 запросов в сутки и 20 в минуту. Один ключ можно дать нескольким людям, "
        + "но лимит у них общий — вдвоём-втроём его хватает на пересмотр разговоров, но не с запасом.\n\n"
        + "Если один раз пополнить счёт на 10 $ (с комиссией около 11 $), бесплатных запросов станет "
        + "1000 в сутки — в 20 раз больше, и насовсем, даже когда деньги на счёте кончатся. Отказов «превышен лимит» "
        + "станет намного меньше, и облако будет отвечать почти всегда. Деньги Falar при этом не тратит.");
    new android.app.AlertDialog.Builder(this).setTitle("Ключ OpenRouter").setView(t)
        .setPositiveButton("открыть openrouter.ai", (d, w) -> {
          try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://openrouter.ai/keys"))); }
          catch (Exception e) { onLog("не открылось: " + e); }
        })
        .setNegativeButton("закрыть", null).show();
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
        .setTitle("Куда пойдёт запрос · " + (c.preferQuality ? "точнее" : "быстрее"))
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
    sizeLbl.setText((int) szPt + " · " + (int) szRu);
    if (prevPt != null) { prevPt.setTextSize(szPt); prevRu.setTextSize(szRu); }
  }

  void startSvc() {
    if (svcStarted) return; svcStarted = true;
    Intent i = new Intent(this, TranslatorService.class);
    // Переносим все добавки без разбора: список имён молча терял новые флаги, и снаружи это
    // выглядело как «интент не работает».
    if (getIntent().getExtras() != null) i.putExtras(getIntent().getExtras());
    i.putExtra("fromUi", true);      // обычный запуск: сервис сбросит стендовые режимы
    startForegroundService(i); bindSvc();
  }
  /** Связь с сервисом — только пока экран виден. Сервис, к которому кто-то подключён, не
   *  останавливается даже по собственной команде, а на Android 12+ экран после «назад» не
   *  уничтожается: связь держалась бы вечно, и сервис никогда не отдавал бы память. */
  boolean bound = false, started = false;
  void bindSvc() { if (!bound) { bound = bindService(new Intent(this, TranslatorService.class), conn, Context.BIND_AUTO_CREATE); } }
  void unbindSvc() {
    if (!bound) return;
    if (svc != null) { svc.setListener(null); }
    try { unbindService(conn); } catch (Throwable ignore) {}
    bound = false; svc = null;
  }
  @Override protected void onStart() {
    super.onStart();
    started = true;
    // Первый старт делает startSvc() из onCreate и уже привязан; повторный запуск здесь сбросил
    // бы стендовые флаги первого (например, беззвучный режим прогона записей). Только возвращение.
    if (!svcStarted || bound) return;
    // Сервис мог уйти сам, пока экран был свёрнут: поднимаем его снова, и именно запущенным, а не
    // только привязанным, — иначе слушать в кармане после сворачивания он не сможет.
    startForegroundService(new Intent(this, TranslatorService.class).putExtra("fromUi", true));
    bindSvc();
  }
  @Override protected void onStop() {
    started = false;
    unbindSvc();
    super.onStop();
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
    resumed = false;
    holdingRead = false;
    if (svc != null) svc.setReadingAloud(false);
  }
  @Override protected void onResume() {
    super.onResume();
    resumed = true; meter();
    syncGuard();
    if (setupView != null && setupView.getVisibility() == View.VISIBLE) {   // вернулись из настроек приложения
      if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { micForever = false; startSvc(); }
      refreshSetup();
    }
  }
  /** Стенд: нажатая позиция кнопки удержания без записи — микрофон не открывается, в разговор
   *  ничего не попадает. Экран держится включённым, пока идёт показ. Кнопка в доке видна всегда —
   *  режима, который надо включать для показа, больше нет. */
  void micDemo(String kind, String sec) {
    long ms = 1000L * (sec == null ? 15 : Integer.parseInt(sec));
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    show(0);
    if ("meter".equals(kind)) { meterDemo(ms); return; }
    if ("live".equals(kind)) { liveDemo(ms); return; }
    bMic.demo(kind, ms, () -> getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
  }
  /** Стенд: снимок экрана самим приложением (--es uishot <имя> [--es uiscreen talk|drawer|words|settings|mods|cloud|log]).
   *  Вид раскладывается на размер окна и рисуется в files/<имя>.png. Работает и при погашенном
   *  заблокированном экране, когда системе снимать нечего, а разблокировать чужой телефон нельзя.
   *  Тени и остальное, что рисует только видеокарта, в снимок не попадают. Разговор на снимке —
   *  владельца: снимок остаётся на телефоне и на столе, в репозиторий не идёт. */
  void uiShot(String name, String which) {
    // За блокировкой экран остановлен и от сервиса отключён — без разговора снимать нечего. На время
    // снимка подключаемся (сервис сам отдаёт экрану разговор) и потом отключаемся, если экран всё ещё
    // не виден: связь, которую держит невидимый экран, не даёт сервису отпустить память.
    final boolean tmp = !bound;
    if (tmp) bindSvc();
    boolean dr = "drawer".equals(which); final int was = screen;
    show("words".equals(which) ? 1 : "settings".equals(which) ? 2 : "mods".equals(which) ? 5 : "cloud".equals(which) ? 6 : "log".equals(which) ? 7 : 0);
    if (screen == 5) { refreshModules(); refreshModels(); }
    if (screen == 1) refreshWords();
    if (sidePanel != null) {
      sidePanel.animate().cancel(); scrim.animate().cancel();
      if (dr) ui.postDelayed(this::refreshChats, tmp ? 2000 : 0);
      sidePanel.setVisibility(dr ? View.VISIBLE : View.GONE); sidePanel.setTranslationX(0); scrim.setVisibility(dr ? View.VISIBLE : View.GONE); scrim.setAlpha(1);
      if (dr) refreshChats();
    }
    ui.postDelayed(() -> {
      android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
      int sb = 0, id = getResources().getIdentifier("status_bar_height", "dimen", "android");
      if (id > 0) sb = getResources().getDimensionPixelSize(id);
      int w = rootView.getWidth() > 0 ? rootView.getWidth() : dm.widthPixels, h = rootView.getHeight() > 0 ? rootView.getHeight() : dm.heightPixels - sb;
      // В ListView строки появляются при раскладке — поэтому раскладка дважды.
      for (int k = 0; k < 2; k++) {
        rootView.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        rootView.layout(0, 0, w, h);
      }
      android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
      rootView.draw(new android.graphics.Canvas(bm));
      java.io.File f = new java.io.File(getExternalFilesDir(null), (name == null || name.isEmpty() ? "uishot" : name) + ".png");
      try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) { bm.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); onLog("🧪 снимок экрана: " + f.getName() + " " + w + "×" + h); }
      catch (Exception e) { onLog("🧪 снимок экрана не записан: " + e); }
      bm.recycle();
      if (dr) { sidePanel.setVisibility(View.GONE); scrim.setVisibility(View.GONE); }
      if (screen != was) show(was);                // снимок не оставляет экран на чужом разделе
      if (tmp && !started) unbindSvc();
    }, tmp ? 2500 : 700);
  }
  /** Стенд: кольцо «как слышно» вокруг «Слушать» с уровнем, похожим на речь, — тем же опросом
   *  5 раз в секунду, но без микрофона. Цена кольца на экране (measure_mic_anim.sh, вид meter). */
  void meterDemo(long ms) {
    bListen.setState(true, false);
    final long end = android.os.SystemClock.uptimeMillis() + ms; final java.util.Random rnd = new java.util.Random(7);
    ui.post(new Runnable() { float lv = -50; public void run() {
      if (android.os.SystemClock.uptimeMillis() >= end) {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        markListen(); return;
      }
      lv = Math.max(-55, Math.min(-12, lv + (float) rnd.nextGaussian() * 6));   // слоги то громче, то тише
      bListen.setLevel(lv, (float) Hearing.quality(lv, lv + 48), lv > -38);
      ui.postDelayed(this, 200);
    }});
  }
  /** Стенд: кнопка удержания нажата, уровень и цвет — как у речи, без микрофона: по 3 с тишина
   *  (красный, свечение у края), тихая речь (жёлтый) и хорошая (зелёный, свечение вширь), по кругу.
   *  Снимки экрана и цена свечения (measure_mic_anim.sh, вид live). */
  void liveDemo(long ms) {
    bMic.demo("pulse30", ms, () -> getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
    final long t0 = android.os.SystemClock.uptimeMillis(), end = t0 + ms; final java.util.Random rnd = new java.util.Random(7);
    ui.post(new Runnable() { public void run() {
      long t = android.os.SystemClock.uptimeMillis();
      if (t >= end) return;
      int phase = (int) ((t - t0) / 3000 % 3);
      float q = phase == 0 ? 0 : phase == 1 ? 0.45f : 1, lv = phase == 0 ? -62 : (phase == 1 ? -42 : -22) + (float) rnd.nextGaussian() * 5;
      bMic.setLevel(lv, q);
      ui.postDelayed(this, 100);
    }});
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
  @Override protected void onNewIntent(Intent i) { super.onNewIntent(i);
    if (i.hasExtra("micanim")) { micDemo(i.getStringExtra("micanim"), i.getStringExtra("micsec")); return; }
    // Стенд: перерисовать окно целиком. Перерисовка обычно частичная — только то, что сдвинулось, —
    // и вылезшее за свои границы видно не всегда: проверка прокрутки (test_scroll_device.sh) один
    // раз прошла на сборке с ошибкой. Целиком — вылезшее видно всегда.
    if (i.hasExtra("redraw")) { getWindow().getDecorView().invalidate(); return; }
    if (i.hasExtra("uishot")) { uiShot(i.getStringExtra("uishot"), i.getStringExtra("uiscreen")); return; }
    if (i.hasExtra("ctx") && svc != null) { tCtx.setChecked(true); }
    if (i.getExtras() != null && !i.getExtras().isEmpty()) startService(new Intent(this, TranslatorService.class).putExtras(i));
    else startService(new Intent(this, TranslatorService.class).putExtra("fromUi", true)); }
  @Override public void onReady() {
    if (pendingPhoto != null && svc != null) {       // снимок ждал движков — теперь читаем
      final android.net.Uri u = pendingPhoto; final boolean c = pendingCloud; pendingPhoto = null;
      new Handler(Looper.getMainLooper()).post(() -> handlePhoto(u, c));
    }
    bPin.setEnabled(true); bBetter.setEnabled(true);
    tCtx.setEnabled(true); bClear.setEnabled(true); bKey.setEnabled(true); bVoice.setEnabled(true); bVoice2.setEnabled(true); bWord.setEnabled(true);
    bMic.setEnabled(true); bInput.setEnabled(true); bListen.setEnabled(true);
    uiSync = true;
    tCtx.setChecked(svc != null && svc.contextMode);
    if (svc != null) { tListenPt.setChecked(svc.listenPt); tListenRu.setChecked(svc.listenRu); }
    uiSync = false;
    markListen();
    // Текущий разговор показываем сразу: при запуске список реплик оставался пустым, хотя
    // разговор продолжается — выглядело так, будто вся история пропала.
    if (svc != null && svc.chats != null) showChat(svc.chats.current, svc.chats.load(svc.chats.current));
    if (bigText.length() < 2)
      setHint(svc != null && (svc.listenPt || svc.listenRu) ? "Pode falar · здесь появится перевод"
                                                            : "Микрофон выключен — включите «Слушать» или удержание");
    refreshChats(); refreshKey(null); refreshVoice(); markListen(); refreshBetter(); refreshIntervals(); refreshReadGuard(); applyModules();
    onUpdate(svc == null ? "" : svc.updateState);
  }
  @Override public void onStatus(String s) { status.setText(s); }
  /** Журнал сам прокручивается к последней строке. Без этого он показывал три строки запуска
   *  и больше ничего: ответы приложения уходили за нижний край, и снаружи это выглядело так,
   *  будто кнопки молчат — именно так «потерялся» сохранённый ключ OpenRouter. */
  @Override public void onLog(String s) {
    logView.append(s + "\n\n");
    if (logLast != null) logLast.setText(s.replace('\n', ' '));   // свёрнутый журнал всё равно показывает последний ответ
    if (logScroll != null && screen == 7) logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    if (s.startsWith("🎤") && voiceMsg != null) { voiceMsg.setText(s); refreshVoice(); }
    if (s.startsWith("🧠") && tCtx != null && svc != null && tCtx.isChecked() != svc.contextMode) { uiSync = true; tCtx.setChecked(svc.contextMode); uiSync = false; }
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
  @Override public void onUpdate(String state) {
    if (updLbl == null) return;
    boolean has = svc != null && svc.update != null;
    // Если обновление уже найдено, строка берётся из него, а не из последнего сообщения: сообщение
    // может быть пустым после перезапуска сервиса, и тогда экран противоречил бы собственной кнопке.
    String line = state == null || state.isEmpty()
        ? (has ? Updates.describe(svc.myCode(), svc.update) : "обновления проверяются раз в сутки")
        : state;
    // Вышла новая версия — карточка золотая, кнопка «Обновить»; иначе — «Проверить».
    updTitle.setText(has ? "Вышла Falar " + svc.update.name : "Falar " + (svc == null ? "" : svc.myName()));
    updLbl.setText(line);
    bUpdateGo.setVisibility(has && !svc.updateBusy ? View.VISIBLE : View.GONE);
    bUpdate.setVisibility(has && !svc.updateBusy ? View.GONE : View.VISIBLE);
    bUpdate.setEnabled(svc != null && !svc.updateBusy);
    updCard.setBackground(rows.round(has ? (look.night ? 0xFF2E2416 : 0xFFFBF3E4) : look.bg, 16, has ? (look.night ? 0xFF5A4520 : 0xFFEBD6AE) : look.line));
  }
  @Override public void onModels(ModelStore.State s) {
    mst = s; refreshSetup(); refreshModels(); refreshModules();
    // Обязательное на месте и микрофон разрешён — первый экран больше не нужен; движки грузит сервис.
    if (setupView.getVisibility() == View.VISIBLE && s.checked && s.coreMissing == 0 && !s.busy()
        && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { show(0); setHint("Загружаю движки…"); }
  }

  @Override public void onModules() { applyModules(); }

  static void vis(View v, boolean on) { if (v != null) v.setVisibility(on ? View.VISIBLE : View.GONE); }

  /** Спрятать или показать всё, что принадлежит модулям. Выключенный модуль не должен оставлять
   *  кнопку, которая на нажатие отвечает ошибкой «нет модели». */
  void applyModules() {
    if (svc == null) return;
    java.util.Set<String> on = svc.modules;
    boolean tts = on.contains(Modules.TTS), cloud = on.contains(Modules.CLOUD), llm = on.contains(Modules.LLM);
    vis(ttsBox, tts); vis(revCard, tts);
    if (!tts && revOpen && bRevStart != null) bRevStart.performClick();          // повторение на слух без голоса бессмысленно
    vis(cloudBox, cloud); vis(llmBox, llm); vis(grpTr, cloud || llm);
    vis(bVoices, on.contains(Modules.SPEAKER));
    // Без чтения снимков и облака снимать нечем — кнопка дока только набирает фразу.
    boolean photo = Modules.photo(on);
    if (inputIcon != null) {
      inputIcon.setImageDrawable(icon(photo ? app.falar.R.drawable.ic_camera : app.falar.R.drawable.ic_kbd, look.night ? Look.GOLD : Look.PLUM));
      inputLbl.setText(photo ? "Снимок, текст" : "Набрать");
    }
    vis(bBetter, Modules.better(on));
    refreshModules(); refreshBetter();
  }

  /** Модули: переключатель, размер по манифесту, метка состояния и полоса загрузки — по размерам
   *  файлов на диске, без хэшей: экран перерисовывается часто. */
  void refreshModules() {
    if (svc == null || svc.store == null || modRows.isEmpty()) return;
    ModelStore.State s = mst; boolean busy = s != null && s.busy(), wait = s != null && ModelStore.WAIT.equals(s.phase);
    ModelStore.Item cur = s == null || s.file == null || s.file.isEmpty() ? null : svc.store.byPath(s.file);
    int keys = svc.cloud == null ? 0 : svc.cloud.keys().size();
    uiSync = true;
    for (java.util.Map.Entry<String, ModRow> e : modRows.entrySet()) {
      String m = e.getKey(); ModRow r = e.getValue();
      boolean on = Modules.BASE.equals(m) || svc.mod(m);
      if (!Modules.BASE.equals(m)) r.sw.setChecked(on);
      long b = svc.store.bytes(m);
      r.size.setText(b == 0 ? "" : b >= 1_000_000_000L ? String.format(java.util.Locale.ROOT, "%.1f ГБ", b / 1e9).replace('.', ',') : Math.round(b / 1e6) + " МБ");
      r.size.setVisibility(b == 0 ? View.GONE : View.VISIBLE);
      boolean onPhone = b > 0 && svc.store.onPhone(m), loading = cur != null && m.equals(cur.module) && busy;
      if (b == 0) rows.pill(r.pill, keys == 0 ? "нужен ключ" : keys == 1 ? "ключ есть" : "ключей: " + keys, keys == 0 ? "no" : "ok");
      else if (!on) rows.pill(r.pill, onPhone ? "выключен · файлы на месте" : "выключен", "no");
      else if (onPhone) rows.pill(r.pill, "установлено", "ok");
      else if (loading && !wait) rows.pill(r.pill, "качается", "go");
      else if (wait) rows.pill(r.pill, "ждёт Wi-Fi", "wait");
      else rows.pill(r.pill, busy ? "в очереди" : "не скачано", "no");
      // Полоса — у включённого модуля, файлов которого ещё нет: сколько уже на телефоне.
      boolean bar = on && b > 0 && !onPhone;
      r.barBox.setVisibility(bar ? View.VISIBLE : View.GONE);
      if (bar) {
        long d = Math.min(b, svc.store.doneBytes(m));
        r.bar.set(1, 0, d / (float) b);
        r.barLbl.setText(Math.round(d / 1e6) + " из " + Math.round(b / 1e6) + " МБ · " + (int) (d * 100 / b) + " %");
      }
    }
    uiSync = false;
    long unused = svc.unusedBytes();
    if (bUnused != null) {
      bUnused.setEnabled(unused > 0);
      bUnused.setText(unused > 0 ? "Удалить неиспользуемые · " + ModelStore.mb(unused) + " МБ" : "Неиспользуемых нет");
    }
    if (rowMods != null) {
      int n = 0; for (String m : Modules.CHOICE) if (svc.mod(m)) n++;
      rowMods.sub.setText("включено " + n + " из " + Modules.CHOICE.size() + (s == null || !s.checked ? "" : busy ? " · докачивается" : s.coreMissing == 0 && s.autoMissing == 0 ? " · все модели установлены" : " · не всё скачано"));
      rowMods.sub.setVisibility(View.VISIBLE);
    }
  }

  /** «Удалить неиспользуемые модели» — файлы выключенных модулей. Отдельным действием с вопросом:
   *  выключить модуль и удалить его файлы — разные решения (владелец, 28.09). */
  void unusedDialog() {
    if (svc == null || svc.store == null) return;
    long b = svc.unusedBytes(); if (b == 0) { refreshModules(); return; }
    StringBuilder names = new StringBuilder();
    for (String m : Modules.CHOICE) if (!svc.mod(m) && svc.store.bytes(m) > 0 && svc.store.onPhone(m)) names.append(names.length() > 0 ? ", " : "").append(Modules.title(m));
    new android.app.AlertDialog.Builder(this).setTitle("Удалить неиспользуемые модели?")
        .setMessage("Файлы выключенных модулей: " + (names.length() > 0 ? names : "частично скачанные") + ". Освободится " + ModelStore.mb(b) + " МБ. "
                  + "Если модуль потом включить, его файлы скачаются заново.")
        .setPositiveButton("удалить", (d, w) -> svc.removeUnusedModels())
        .setNegativeButton("отмена", null).show();
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
    // Модули: что ставить. Отмечено под этот телефон (Modules.defaults); всё меняется потом
    // в «Системе» → «Модули», и включённое туда же докачивается само.
    TextView hm = new TextView(this); hm.setTextSize(17); hm.setTypeface(null, Typeface.BOLD); hm.setPadding(0, 24, 0, 0); hm.setText("2. Что нужно"); v.addView(hm);
    TextView base = new TextView(this); base.setTextSize(15); base.setPadding(0, 8, 0, 0);
    base.setText("✓ " + Modules.title(Modules.BASE) + " — " + Modules.what(Modules.BASE)); v.addView(base);
    java.util.Set<String> pre = Modules.defaults(totalRam());
    for (String m : Modules.CHOICE) {
      CheckBox cb = new CheckBox(this); cb.setTextSize(15); cb.setText(Modules.title(m)); cb.setChecked(pre.contains(m)); v.addView(cb); setupChecks.put(m, cb);
      TextView w = new TextView(this); w.setTextSize(12); w.setTextColor(look.soft); w.setPadding(dp(32), 0, 0, dp(4)); w.setText(Modules.what(m)); v.addView(w);
      cb.setOnCheckedChangeListener((vv, on) -> refreshSetup());
    }
    TextView h2 = new TextView(this); h2.setTextSize(17); h2.setTypeface(null, Typeface.BOLD); h2.setPadding(0, 24, 0, 0); h2.setText("3. Модели"); v.addView(h2);
    setupText = new TextView(this); setupText.setTextSize(15); v.addView(setupText);
    tSetupAny = new ToggleButton(this); tSetupAny.setTextOn("качать и по мобильной сети: ВКЛ"); tSetupAny.setTextOff("качать и по мобильной сети: выкл");
    tSetupAny.setChecked(prefs.getBoolean("models_any_net", false)); v.addView(tSetupAny);
    tSetupAny.setOnCheckedChangeListener((vv, on) -> setAnyNet(on));
    LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
    bSetupDl = new Button(this); bSetupDl.setText("скачать"); bSetupDl.setEnabled(false); row.addView(bSetupDl, new LinearLayout.LayoutParams(0, -2, 1f));
    bSetupStop = new Button(this); bSetupStop.setText("стоп"); bSetupStop.setVisibility(View.GONE); row.addView(bSetupStop, new LinearLayout.LayoutParams(-2, -2));
    v.addView(row);
    setupBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); setupBar.setMax(1000); setupBar.setVisibility(View.GONE); v.addView(setupBar);
    setupProg = new TextView(this); setupProg.setTextSize(14); setupProg.setTextColor(look.dim); v.addView(setupProg);
    for (Button b : new Button[]{bSetupMic, bSetupDl, bSetupStop}) style(b);
    bSetupMic.setOnClickListener(vv -> askMic());
    bSetupDl.setOnClickListener(vv -> { if (svc != null) svc.setupModules(setupChoice()); });
    bSetupStop.setOnClickListener(vv -> { if (svc != null) svc.cancelModels(); });
    return sv;
  }
  /** Отмеченное на первом экране. */
  java.util.Set<String> setupChoice() {
    java.util.Set<String> on = new java.util.LinkedHashSet<>();
    for (String m : Modules.CHOICE) { CheckBox cb = setupChecks.get(m); if (cb != null && cb.isChecked()) on.add(m); }
    return on;
  }
  long totalRam() {
    try { android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo(); getSystemService(android.app.ActivityManager.class).getMemoryInfo(mi); return mi.totalMem; }
    catch (Throwable t) { return 0; }
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
    if (!setupSynced && svc != null && svc.modulesChosen) {       // выбор уже был (загрузку прервали) — показать его, а не умолчания
      setupSynced = true;
      for (String m : Modules.CHOICE) { CheckBox cb = setupChecks.get(m); if (cb != null) cb.setChecked(svc.mod(m)); }
    }
    long need = svc != null && svc.store != null ? svc.store.needBytes(setupChoice()) : 0;
    if (!mic) setupText.setText("Модели — после разрешения микрофона: перевод речи и выбранные модули.");
    else if (svc == null || s == null || !s.checked) setupText.setText("Проверяю, что уже лежит на телефоне…");
    else if (s.coreMissing == 0 && !busy) setupText.setText("✓ модели на месте");
    else setupText.setText("Скачать " + ModelStore.mb(need) + " МБ: перевод речи и отмеченные модули. Качается с Hugging Face один раз; "
                         + "по умолчанию только по Wi-Fi. Можно остановить и продолжить позже с того же места.");
    bSetupDl.setEnabled(mic && svc != null && s != null && s.checked && !busy && s.coreMissing > 0);
    bSetupDl.setText(s != null && ModelStore.PAUSED.equals(s.phase) ? "продолжить" : "скачать" + (need > 0 ? " " + ModelStore.mb(need) + " МБ" : ""));
    bSetupStop.setVisibility(busy ? View.VISIBLE : View.GONE);
    setupBar.setVisibility(s != null && (busy || ModelStore.PAUSED.equals(s.phase)) ? View.VISIBLE : View.GONE);
    if (s != null && s.total > 0) setupBar.setProgress((int) (s.done * 1000 / s.total));
    setupProg.setText(s == null ? "" : progressLine(s));
  }
  /** Строка над модулями: «Все модели установлены · 14 из 14» или ход докачки со «Стоп». */
  void refreshModels() {
    if (modsSum == null) return;
    ModelStore.State s = mst;
    int core = svc == null || svc.store == null ? 0 : svc.store.tier("core").size();
    int col = look.night ? Look.MINT : 0xFF1E9E77;
    android.graphics.drawable.Drawable ok = icon(app.falar.R.drawable.ic_check, col), dl = icon(app.falar.R.drawable.ic_download, rows.ink());
    ok.setBounds(0, 0, dp(18), dp(18)); dl.setBounds(0, 0, dp(18), dp(18));
    if (s == null || !s.checked) {
      modsSum.setText("Проверяю файлы…"); modsSum.setCompoundDrawables(null, null, null, null);
      bModelsVerify.setEnabled(false); bModelsStop.setVisibility(View.GONE); modelsLbl.setVisibility(View.GONE); return;
    }
    boolean busy = s.busy(), check = ModelStore.CHECK.equals(s.phase);
    if (check) { modsSum.setText("Проверяю файлы…"); modsSum.setCompoundDrawables(dl, null, null, null); }
    else if (busy) {
      modsSum.setText(ModelStore.WAIT.equals(s.phase) ? "Жду Wi-Fi · осталось " + ModelStore.mb(Math.max(0, s.total - s.done)) + " МБ"
          : "Качаю · " + ModelStore.mb(s.done) + " из " + ModelStore.mb(s.total) + " МБ");
      modsSum.setCompoundDrawables(dl, null, null, null);
    } else if (s.coreMissing == 0 && s.autoMissing == 0) {
      modsSum.setText("Все модели установлены · " + core + " из " + core); modsSum.setCompoundDrawables(ok, null, null, null);
    } else if (s.coreMissing > 0) {
      modsSum.setText("Обязательных нет: " + s.coreMissing + " из " + core + " · " + ModelStore.mb(s.coreBytes) + " МБ"); modsSum.setCompoundDrawables(dl, null, null, null);
    } else { modsSum.setText("Модулям не хватает " + ModelStore.mb(s.autoBytes) + " МБ — докачается само"); modsSum.setCompoundDrawables(dl, null, null, null); }
    // Ошибки и остановка — строкой под ней: причина видна там, где смотрят.
    String extra = !s.errors.isEmpty() && !busy ? String.join("\n", s.errors) : ModelStore.PAUSED.equals(s.phase) ? s.message : "";
    modelsLbl.setText(extra); modelsLbl.setVisibility(extra.isEmpty() ? View.GONE : View.VISIBLE);
    bModelsUp.setVisibility(s.upgrade > 0 && !busy ? View.VISIBLE : View.GONE);
    bModelsUp.setText("Облегчить перевод · " + ModelStore.mb(s.upgradeBytes) + " МБ");
    bModelsStop.setVisibility(busy && !check ? View.VISIBLE : View.GONE);
    bModelsVerify.setEnabled(!busy);
    bModelsVerify.setText(check ? "Проверяю…" : "Проверить файлы");
    refreshModules();
  }
  /** «Облегчить перевод»: новые файлы перевода вместо прежних — перевод занимает на полгигабайта меньше
   *  памяти, и уточнитель чаще помещается рядом. Качается по выбору: 305 МБ — не мелочь. */
  void upgradeDialog() {
    if (svc == null || svc.store == null) return;
    ModelStore.State s = mst; long mb = s == null ? 0 : s.upgradeBytes;
    new android.app.AlertDialog.Builder(this).setTitle("Облегчить перевод")
        .setMessage("Перевод займёт примерно на 500 МБ меньше памяти телефона, а уточнителю чаще хватит места рядом с ним. "
                  + "Скачать " + ModelStore.mb(mb) + " МБ; после проверки прежние файлы перевода удалятся и освободят около 736 МБ. "
                  + "Новый способ заработает при следующем запуске приложения. Переводы те же почти всегда: на проверке совпали 96 % фраз.")
        .setPositiveButton("скачать", (d, w) -> { if (svc != null) new Thread(svc::downloadUpgrade, "upgrade").start(); })
        .setNegativeButton("не сейчас", null).show();
  }
  /** Португальская сторона всегда крупно, русская мелко — независимо от направления перевода. */
  @Override public void onTurn(String dir, String src, String dst, boolean refined) {
    boolean srcIsPt = dir.startsWith("pt");
    String pt = srcIsPt ? src : dst, ru = srcIsPt ? dst : src;
    refreshHist();                      // список строится из самого разговора, а не копится в строке
    showBig(pt, dir, refined);
    setHint((srcIsPt ? "собеседник" : "вы") + (refined ? " · уточнено" : ""));
    smallRu.setText(ru); setWho(dir);
    refreshBetter();
  }

  /** Ход работы. Живой перевод и уточнитель относятся к реплике — их ход в ней самой: отрезки
   *  «распознаю · перевожу · уточняю» и подпись этапа на месте её кнопок (решение владельца 01.10).
   *  Облако пересматривает весь разговор, загрузка — модели, поэтому их ход — в шапке: подпись вместо
   *  строки состояния и полоса по её нижнему краю. Полосы стоят на месте и меняются только с ходом:
   *  бегущая полоса перерисовывала весь экран на каждом кадре. */
  String convBusy; long cloudFrom; long cloudTypical;
  /** Загрузка движков: этап, их число, что грузится, когда этап начался; сколько этапы шли в прошлый
   *  раз (load_ms, пишет сервис): по ним отрезок идёт и внутри этапа, а в подписи — «ещё ≈ N с». */
  int loadK = -1, loadN; String loadWhat; long loadFrom; long[] loadMs;
  final Runnable loadTick = new Runnable() { public void run() {
    if (loadK < 0) return;
    long[] ms = loadMs; int n = loadN, k = Math.min(loadK, n - 1);
    long el = android.os.SystemClock.uptimeMillis() - loadFrom, cur = ms[Math.min(k, ms.length - 1)], before = 0, after = 0, all = 0;
    for (int i = 0; i < n; i++) { long x = ms[Math.min(i, ms.length - 1)]; all += x; if (i < k) before += x; else if (i > k) after += x; }
    long left = Math.max(cur - el, 500) + after;
    stageBar.set(n, k, Math.min(0.95f, el / (float) Math.max(1, cur)));
    busyLbl.setText("Загружаю модели · " + loadWhat + " · ещё ≈ " + Math.max(1, Math.round(left / 1000.0)) + " с");
    ui.postDelayed(this, 500);
  }};
  /** Сколько шли этапы загрузки в прошлый раз: распознавание, перевод, [озвучка,] словарь и разговоры.
   *  Нет замера — Redmi Note 10 Pro, 30.09: 3,7 · 3,7 · 3,0 · 1,5 с. */
  long[] loadExpect(int n) {
    long[] d = {3700, 3700, 3000, 1500};
    try { String[] x = prefs.getString("load_ms", "").split(","); if (x.length == 4) for (int i = 0; i < 4; i++) { long v = Long.parseLong(x[i].trim()); if (v > 0) d[i] = v; } }
    catch (Exception ignore) {}
    return n == 4 ? d : new long[]{d[0], d[1], d[3]};            // без озвучки третьего этапа нет
  }
  final Runnable cloudTick = new Runnable() { public void run() {
    if (convBusy == null || cloudFrom == 0) return;
    long ms = android.os.SystemClock.uptimeMillis() - cloudFrom;
    convBusy = "пересматриваю разговор в облаке · " + ms / 1000 + " с";
    // Точного хода у облака нет — это один запрос. Полоса идёт по времени: прошло / сколько обычно
    // отвечает эта модель, и не доходит до конца, пока ответа нет.
    abProg.set(1, 0, Math.min(0.95f, ms / (float) cloudTypical));
    refreshHint();
    ui.postDelayed(this, 1000);
  }};
  @Override public void onBusy(String kind, String what, int done, int total) {
    if (busyRow == null) return;
    // Загрузка движков — тем же видом, что ход перевода и снимка (владелец 01.10: «надо сохранить
    // стиль»): отрезки этапов «распознавание · перевод · озвучка · словарь» на месте кнопок реплики и
    // подпись с тем, сколько осталось.
    if (what != null && "load".equals(kind)) {
      busyRow.setVisibility(View.VISIBLE); chipsRow.setVisibility(View.INVISIBLE);
      if (loadK < 0) { loadMs = loadExpect(total); ui.post(loadTick); }
      if (done != loadK) { loadK = done; loadFrom = android.os.SystemClock.uptimeMillis(); }
      loadN = Math.max(1, total); loadWhat = what;
      return;
    }
    if (loadK >= 0) { loadK = -1; ui.removeCallbacks(loadTick); }
    boolean reply = what != null && ("live".equals(kind) || "refine".equals(kind));
    busyRow.setVisibility(reply ? View.VISIBLE : View.INVISIBLE);
    chipsRow.setVisibility(reply ? View.INVISIBLE : View.VISIBLE);
    if (reply) {
      // Этап — по тому, что идёт: распознавание (и чтение снимка), перевод, уточнение.
      int stage = "refine".equals(kind) ? 2 : what.startsWith("перевожу") ? 1 : 0;
      stageBar.set(3, stage, total > 0 ? Math.min(1f, done / (float) total) : 0f);
      // «уточняю перевод · 1 из 2»: номер той, что в работе, а не уже сделанных. У процентов свой текст.
      busyLbl.setText(total > 0 && !what.contains("%") ? what + " · " + Math.min(done + 1, total) + " из " + total : what);
    }
    boolean cloud = what != null && "cloud".equals(kind), models = what != null && "models".equals(kind);
    if (cloud) {
      if (cloudFrom == 0) {
        cloudFrom = android.os.SystemClock.uptimeMillis();
        long typ = 0;
        try { Cloud c = svc == null ? null : svc.cloud; if (c != null) typ = c.metaOf(c.next()).ms; } catch (Throwable t) { typ = 0; }
        cloudTypical = typ > 0 ? typ : 20000;
        convBusy = "пересматриваю разговор в облаке · 0 с"; abProg.set(1, 0, 0);
        ui.post(cloudTick);
      }
    } else { cloudFrom = 0; ui.removeCallbacks(cloudTick); }
    if (models) { convBusy = what; abProg.set(1, 0, total > 0 ? done / (float) total : 0f); }
    if (!cloud && !models) convBusy = null;
    refreshHint();
  }
  @Override protected void onDestroy() { unbindSvc(); super.onDestroy(); }
}
