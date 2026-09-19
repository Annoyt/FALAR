# Сквозной конвейер на телефоне (бенч)

> **Архив.** Этот стенд собирал сквозной конвейер из отдельных процессов через `app_process`
> до того, как появилось приложение. Он вытеснен `bench/apk/` и здесь оставлен как история:
> пути вида `/tmp/llbench` указывают на каталоги того прогона и больше не существуют.

Два резидентных процесса через `app_process` (без NDK/Termux/APK), очередь файлов между ними:
- `AsrTts.java` — sherpa-onnx Java API: ASR (nemo transducer) → отправка текста в очередь → ожидание
  перевода → TTS (Piper VITS) с колбэком первого чанка. Таймер: от конца аудио до первого чанка.
- `MtServer.java` + `SpmTokenizer.java` — ONNX Runtime Java: OPUS-MT int8 (encoder / decoder /
  decoder_with_past), жадный декод с KV-кэшем, SentencePiece-unigram Viterbi по `source.spm`
  (пары piece/score в TSV) + `vocab.json` Marian для id.
- `run_e2e.sh` — запуск трёх конфигураций. `OrtBench.java` — предыдущий MT-only драйвер.

Почему два процесса: JNI sherpa-onnx и Java-биндинги ORT не делят одну `libonnxruntime.so` —
у официальной сборки ORT символы версионированные (`@@VERS_1.0.0`), у sherpa нет, bionic не сводит.
Накладные расходы связки измерены: 4–5 мс на фразу.

Сборка: `javac --release 11 -cp android.jar[:ort-classes.jar]` + D8 из `tools/r8.jar`
(`--min-api 28`), запуск `CLASSPATH=x.dex LD_LIBRARY_PATH=... taskset f0 app_process <dir> <Main>`.
