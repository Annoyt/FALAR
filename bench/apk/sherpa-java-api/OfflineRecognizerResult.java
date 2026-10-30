// Copyright 2024 Xiaomi Corporation

package com.k2fsa.sherpa.onnx;

public class OfflineRecognizerResult {
    private final String text;
    private final String[] tokens;
    private final float[] timestamps;
    private final String lang;
    private final String emotion;
    private final String event;
    private final float[] durations;
    // Falar: логарифм вероятности каждого токена (уверенность распознавателя). Его отдаёт наша сборка
    // libsherpa-onnx-jni.so (тот же v1.13.8, в getResult — конструктор с восемью аргументами); у прежней сборки
    // поля нет — здесь пустой массив (results/2026-10-27-asr-confidence.md).
    private final float[] ysLogProbs;

    public OfflineRecognizerResult(String text, String[] tokens, float[] timestamps, String lang, String emotion, String event, float[] durations, float[] ysLogProbs) {
        this.text = text;
        this.tokens = tokens;
        this.timestamps = timestamps;
        this.lang = lang;
        this.emotion = emotion;
        this.event = event;
        this.durations = durations;
        this.ysLogProbs = ysLogProbs == null ? new float[0] : ysLogProbs;
    }

    public OfflineRecognizerResult(String text, String[] tokens, float[] timestamps, String lang, String emotion, String event, float[] durations) {
        this.text = text;
        this.tokens = tokens;
        this.timestamps = timestamps;
        this.lang = lang;
        this.emotion = emotion;
        this.event = event;
        this.durations = durations;
        this.ysLogProbs = new float[0];
    }

    public float[] getYsLogProbs() {
        return ysLogProbs;
    }

    public String getText() {
        return text;
    }

    public String[] getTokens() {
        return tokens;
    }

    public float[] getTimestamps() {
        return timestamps;
    }

    public String getLang() {
        return lang;
    }

    public String getEmotion() {
        return emotion;
    }

    public String getEvent() {
        return event;
    }

    public float[] getDurations() {
        return durations;
    }
}
